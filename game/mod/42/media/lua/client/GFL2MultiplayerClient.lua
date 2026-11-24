local M = {}

local protocol = require "GFL2MultiplayerProtocol"
local economy = require "GFL2RoomEconomy"

local slots = {}
local remote = {}
local nextUpdate = 0
local lastSnapshotRevision = 0
local nextHandshake = 0
local showRemoteModels = true

local function disableRemoteActor(actor)
    if GFL2VoiceEngine and GFL2VoiceEngine.syncRemote then GFL2VoiceEngine.syncRemote(actor, false) end
    GFL2CharacterRuntime.disable(actor)
end

local function slot(player)
    if not (isClient and isClient()) or not player then return end
    local id = player:getOnlineID()
    if id < 0 or player:isDead() then return end
    local state = slots[id]
    if not state or state.player ~= player then
        if state then economy.setSnapshot(state.player, nil) end
        nextHandshake = nextHandshake + 1
        state = {player = player, request = 0, ready = false, nextSync = 0, handshake = nextHandshake}
        slots[id] = state
    end
    return state
end

local function send(state, command, args)
    args.version = protocol.version
    args.session = state.session
    sendClientCommand(state.player, protocol.module, command, args)
end

function M.ready(player)
    local state = slot(player)
    return state and state.ready
end

function M.pending(player)
    local state = slot(player)
    return state and state.pending ~= nil
end

function M.transact(player, command, target, count, callback)
    local state = slot(player)
    if not state or not state.ready or state.pending then return false end
    local args = {request = state.request + 1, target = target, count = count}
    state.pending = {command = command, args = args, callback = callback, retries = 0, retryAt = getTimestampMs() + 3000}
    send(state, command, args)
    return true
end

function M.publish(player, saved)
    if not player:isLocalPlayer() then return end
    local state = slot(player)
    if not state then return end
    local model = saved and saved.model or ""
    local signature = model ~= "" and GFL2CharacterRuntime.networkSignature(model) or ""
    local appearance = model ~= "" and GFL2CharacterRuntime.networkAppearance(player) or ""
    local args = {model = model, signature = signature, appearance = appearance}
    if not protocol.appearance(args) then return end
    if not state.ready then
        state.pendingPublish = args
        return
    end
    state.pendingPublish = nil
    local previous = state.lastPublished
    if previous and previous.model == model and previous.signature == signature and previous.appearance == appearance then return end
    state.attempt = (state.attempt or 0) + 1
    args.attempt = state.attempt
    state.lastPublished = args
    send(state, "Appearance", args)
end

function M.publishVoice(player, sound, kind)
    if not (isClient and isClient()) or not player or not player.isLocalPlayer or not player:isLocalPlayer() or not player.getOnlineID then return end
    local state = slot(player)
    if not state or not state.ready then return end
    local args = {sound = sound, kind = kind}
    if not protocol.voice(args) then return end
    send(state, "Voice", args)
end

function M.getShowRemoteModels()
    return showRemoteModels
end

function M.setShowRemoteModels(enabled)
    local nextOn = enabled ~= false
    if showRemoteModels == nextOn then return showRemoteModels end
    showRemoteModels = nextOn
    if not nextOn then
        if GFL2CharacterRuntime then
            for _, state in pairs(remote) do
                if state.actor then
                    disableRemoteActor(state.actor)
                    state.actor = nil
                end
                state.applied = false
                state.usingDefault = false
            end
        end
    else
        for _, state in pairs(remote) do
            if not state.retired then
                state.failed = false
                state.applied = false
                state.usingDefault = false
            end
        end
        nextUpdate = 0
    end
    return showRemoteModels
end

function M.resync()
    if not (isClient and isClient()) then return end
    for _, state in pairs(slots) do state.nextSync = 0 end
    for _, state in pairs(remote) do
        if state.failed or state.usingDefault then
            state.failed = false
            state.usingDefault = false
            state.applied = false
        end
    end
end

local function acceptAppearance(data, fromSnapshot)
    if not protocol.appearance(data) or not protocol.integer(data.player, 0, 32767)
        or not protocol.integer(data.revision, 1, 2147483647) or type(data.owner) ~= "string" then return end
    if not fromSnapshot and data.revision <= lastSnapshotRevision then return end
    local current = remote[data.player]
    if current and current.revision >= data.revision then return end
    local player = getPlayerByOnlineID(data.player)
    if player and player:isLocalPlayer() then return end
    if current then
        local sameSession = current.owner == data.owner and (not current.session or current.session == data.session)
        if sameSession and current.retired and current.session and data.model ~= "" then return end
        data.previousActor = current.previousActor
        if current.actor then
            if sameSession and not current.actor:isDead() then data.actor = current.actor
            else
                disableRemoteActor(current.actor)
                data.previousActor = current.actor
            end
        end
    end
    remote[data.player] = data
end

local function updateRemote()
    if not showRemoteModels then
        for _, state in pairs(remote) do
            if state.actor then
                disableRemoteActor(state.actor)
                state.actor = nil
                state.applied = false
                state.usingDefault = false
            end
        end
        return
    end
    for id, state in pairs(remote) do
        local raw = getPlayerByOnlineID(id)
        local actor = raw
        if actor and (actor == state.previousActor or actor:isLocalPlayer() or actor:isDead()
                or actor:getUsername() ~= state.owner) then actor = nil end
        if state.actor and state.actor ~= actor then
            local retired = state.actor:isDead()
                or (raw ~= nil and (raw:isLocalPlayer() or raw:getUsername() ~= state.owner))
            disableRemoteActor(state.actor)
            if retired then
                state.previousActor = state.actor
                state.retired = true
            end
            state.actor = nil
            state.applied = false
            state.usingDefault = false
        end
        if state.actor and state.applied and GFL2CharacterRuntime.hasModelFailure(state.actor) then
            disableRemoteActor(state.actor)
            state.actor = nil
            state.failed = true
            state.usingDefault = false
        end
        if actor and not state.applied and not state.failed and not state.retired then
            state.usingDefault = false
            local problem = GFL2CharacterRuntime.queueRemoteAppearance(actor, state.model, state.signature, state.appearance)
            if problem ~= "" and state.model ~= "" and GFL2CharacterRuntime.queueRemoteDefault then
                local localSignature = GFL2CharacterRuntime.networkSignature(state.model)
                if type(localSignature) == "string" and localSignature ~= "" and localSignature ~= state.signature then
                    problem = GFL2CharacterRuntime.queueRemoteDefault(actor, state.model)
                    if problem == "" then state.usingDefault = true end
                end
            end
            if problem == "" then
                state.actor = state.model ~= "" and actor or nil
                state.applied = true
                state.previousActor = nil
                if GFL2VoiceEngine and GFL2VoiceEngine.syncRemote then
                    GFL2VoiceEngine.syncRemote(actor, state.model ~= "")
                end
            else
                state.failed = true
                print("[GFL2Exilium] Remote appearance: " .. problem)
                disableRemoteActor(actor)
            end
        end
    end
end

local function restoreAppearance(state, equipped)
    if not protocol.appearance(equipped) or equipped.model == "" then return end
    local localSignature = GFL2CharacterRuntime.networkSignature(equipped.model)
    if type(localSignature) ~= "string" or localSignature == "" then return end
    local saved = state.player:getModData().GFL2CharacterRuntime
    local settings = saved and (saved.model == equipped.model and saved.settings
        or saved.profiles and saved.profiles[equipped.model]) or ""
    local result = GFL2CharacterRuntime.queueOwnedAppearance(state.player, equipped.model, equipped.appearance, settings)
    if result ~= "" and localSignature ~= equipped.signature then
        if settings ~= "" and GFL2CharacterRuntime.queueSettings then
            result = GFL2CharacterRuntime.queueSettings(state.player, equipped.model, settings)
        end
        if result ~= "" and GFL2CharacterRuntime.queueRestore then
            result = GFL2CharacterRuntime.queueRestore(state.player, equipped.model, "")
        end
    end
    if result == "" then state.restoring = true
    else print("[GFL2Exilium] Restore appearance: " .. result) end
end

local function finishRejection(state)
    local rejection = state.rejection
    if not rejection then return end
    local player = state.player
    if state.lastPublished ~= rejection.sent or GFL2CharacterRuntime.modelId(player) ~= rejection.sent.model then
        state.rejection = nil
        return
    end
    if not GFL2CharacterRuntime.isReady(player) and not GFL2CharacterRuntime.hasModelFailure(player) then return end
    state.rejection = nil
    state.lastPublished = nil
    state.restoring = false
    M.onRejected(player)
    restoreAppearance(state, rejection.equipped)
end

local function acceptWallet(state, data)
    local first = not state.ready
    local sessionChanged = state.ready and state.session ~= nil and data.session ~= nil and data.session ~= state.session
    if not sessionChanged and state.ready and data.request < state.request then return end
    state.ready = true
    state.session = data.session
    state.request = data.request
    economy.setSnapshot(state.player, data.wallet)
    if sessionChanged then state.lastPublished = nil end
    -- A wallet snapshot may arrive before the transaction reply. Replay that request
    -- to get the cached result instead of reporting a purchase failure.
    if state.pending and (sessionChanged or state.pending.args.request < data.request or state.pending.args.request > data.request + 1) then
        local callback = state.pending.callback
        state.pending = nil
        callback({status = "unavailable"})
    end
    local queued = state.pendingPublish
    if queued then
        state.pendingPublish = nil
        state.attempt = (state.attempt or 0) + 1
        queued.attempt = state.attempt
        state.lastPublished = queued
        send(state, "Appearance", queued)
    elseif first then
        restoreAppearance(state, data.equipped)
    elseif sessionChanged and GFL2CharacterRuntime.isReady and GFL2CharacterRuntime.isReady(state.player) and M.onRestored then
        M.onRestored(state.player, true)
    end
end

Events.OnServerCommand.Add(function(module, command, data)
    if not (isClient and isClient()) then return end
    if module ~= protocol.module or type(data) ~= "table" or data.version ~= protocol.version or not GFL2CharacterRuntime then return end
    if command == "Appearance" then
        acceptAppearance(data)
        return
    end
    if command == "Voice" then
        if not protocol.voice(data) or not protocol.integer(data.player, 0, 32767) or type(data.owner) ~= "string" then return end
        local state = remote[data.player]
        if not state or state.model == "" or not state.applied or state.retired or not state.actor or state.owner ~= data.owner then return end
        if state.session and data.session and state.session ~= data.session then return end
        local actor = state.actor
        if actor:isLocalPlayer() or actor:isDead() or actor:getUsername() ~= data.owner then return end
        if GFL2VoiceEngine and GFL2VoiceEngine.playRemote then
            GFL2VoiceEngine.playRemote(actor, data.sound, data.kind)
        end
        return
    end
    local state = slots[data.player]
    if not state or state.player:isDead() or state.player:getOnlineID() ~= data.player then return end
    local handshakeMatched = data.handshake ~= nil and data.handshake == state.handshake
    if data.handshake ~= nil and not handshakeMatched then return end
    if state.session ~= nil and state.session ~= data.session
        and not (handshakeMatched and (command == "Wallet" or command == "Snapshot")) then return end
    if command == "Snapshot" then
        if data.revision < lastSnapshotRevision then return end
        lastSnapshotRevision = data.revision
        local present = {}
        for _, appearance in ipairs(data.states or {}) do
            acceptAppearance(appearance, true)
            present[appearance.player] = true
        end
        for id, appearance in pairs(remote) do
            if not present[id] and appearance.revision <= data.revision then
                if appearance.actor then disableRemoteActor(appearance.actor) end
                remote[id] = nil
            end
        end
    elseif command == "Wallet" then
        acceptWallet(state, data)
    elseif command == "Transaction" and state.pending and state.pending.args.request == data.request then
        local callback = state.pending.callback
        state.pending = nil
        state.request = data.request
        economy.setSnapshot(state.player, data.wallet)
        callback(data.result)
    elseif command == "AppearanceRejected" then
        local sent = state.lastPublished
        if sent and (data.attempt == nil or data.attempt == sent.attempt) then
            state.rejection = {sent = sent, equipped = data.equipped}
            finishRejection(state)
        end
    end
end)

Events.OnTick.Add(function()
    if not (isClient and isClient()) or not GFL2CharacterRuntime then return end
    local now = getTimestampMs()
    if now < nextUpdate then return end
    nextUpdate = now + 250
    for index = 0, 3 do
        local player = getSpecificPlayer(index)
        if player and not player:isDead() then
            local state = slot(player)
            if state then
                state.seenAt = now
                if now >= state.nextSync then
                    state.nextSync = now + (state.ready and 15000 or 3000)
                    if not state.pending then send(state, "Request", {handshake = state.handshake}) end
                end
                if state.pending and now >= state.pending.retryAt then
                    local retries = state.pending.retries or 0
                    if retries >= 4 then
                        local callback = state.pending.callback
                        state.pending = nil
                        state.nextSync = 0
                        callback({status = "unavailable"})
                    else
                        state.pending.retries = retries + 1
                        state.pending.retryAt = now + 3000
                        send(state, state.pending.command, state.pending.args)
                    end
                end
                finishRejection(state)
                if state.restoring then
                    if GFL2CharacterRuntime.isReady(player) then
                        state.restoring = false
                        M.onRestored(player)
                    elseif GFL2CharacterRuntime.hasModelFailure(player) then state.restoring = false end
                end
            end
        end
    end
    for id, state in pairs(slots) do
        if state.seenAt ~= now then
            economy.setSnapshot(state.player, nil)
            slots[id] = nil
        end
    end
    updateRemote()
end)

local function clear()
    if GFL2CharacterRuntime then
        for _, state in pairs(remote) do
            if state.actor then disableRemoteActor(state.actor) end
        end
    end
    for _, state in pairs(slots) do economy.setSnapshot(state.player, nil) end
    slots = {}
    remote = {}
    nextUpdate = 0
    lastSnapshotRevision = 0
end

Events.OnDisconnect.Add(clear)
if Events.OnMainMenuEnter then Events.OnMainMenuEnter.Add(clear) end

return M
