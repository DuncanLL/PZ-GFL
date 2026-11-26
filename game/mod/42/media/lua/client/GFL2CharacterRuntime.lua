local key = "GFL2CharacterRuntime"
local window = require "GFL2RoomWindow"
local economy = require "GFL2RoomEconomy"
local multiplayer = require "GFL2MultiplayerClient"
local portraitHud = require "GFL2PortraitHud"
local started = false
local awaiting = {}
local expressionEdits = {}
local voiceSaveEdits = {}
local clientPrefsLoaded = false
local clientPrefs = {
    voiceVolume = 50,
    showSubtitles = true,
    studioPreview = true,
    studioScale = 200,
    idleAmbient = true,
    facialMode = 2,
    portraitHud = false,
    portraitSize = 200,
    portraitZoom = 265,
    portraitYaw = 6,
    portraitPan = 0,
    portraitLock = false,
    portraitFps = 24,
    portraitX = -1,
    portraitY = -1,
    showRemoteModels = true,
    remoteBodyPhysics = false,
    remoteChestStrength = 0,
    remoteVoice = true,
    remoteSubtitles = true,
}

local function clampInt(v, lo, hi, fallback)
    local n = tonumber(v)
    if not n then return fallback end
    return math.max(lo, math.min(hi, math.floor(n + (n >= 0 and 0.5 or -0.5))))
end

local function normalizePortraitFps(v)
    local n = clampInt(v, 0, 61, 24)
    if n <= 0 or n >= 61 then return 0 end
    return clampInt(n, 10, 60, 24)
end

local function normalizeStudioScale(v)
    local n = clampInt(v, 100, 200, 200)
    if n <= 125 then return 100 end
    if n <= 175 then return 150 end
    return 200
end

local function saveClientPrefs()
    if not (GFL2CharacterRuntime and GFL2CharacterRuntime.writeClientSettings) then return end
    local body = string.format(
        '{"voiceVolume":%d,"showSubtitles":%s,"studioPreview":%s,"studioScale":%d,"idleAmbient":%s,"facialMode":%d,"portraitHud":%s,"portraitSize":%d,"portraitZoom":%d,"portraitYaw":%d,"portraitPan":%d,"portraitLock":%s,"portraitFps":%d,"portraitX":%d,"portraitY":%d,"showRemoteModels":%s,"remoteBodyPhysics":%s,"remoteChestStrength":%d,"remoteVoice":%s,"remoteSubtitles":%s}',
        clampInt(clientPrefs.voiceVolume, 0, 100, 50),
        clientPrefs.showSubtitles ~= false and "true" or "false",
        clientPrefs.studioPreview ~= false and "true" or "false",
        normalizeStudioScale(clientPrefs.studioScale),
        clientPrefs.idleAmbient ~= false and "true" or "false",
        clampInt(clientPrefs.facialMode, 0, 2, 2),
        clientPrefs.portraitHud == true and "true" or "false",
        clampInt(clientPrefs.portraitSize, 140, 340, 200),
        clampInt(clientPrefs.portraitZoom, 100, 340, 265),
        clampInt(clientPrefs.portraitYaw, -45, 45, 6),
        clampInt(clientPrefs.portraitPan, -25, 25, 0),
        clientPrefs.portraitLock == true and "true" or "false",
        normalizePortraitFps(clientPrefs.portraitFps),
        clampInt(clientPrefs.portraitX, -1, 16384, -1),
        clampInt(clientPrefs.portraitY, -1, 16384, -1),
        clientPrefs.showRemoteModels ~= false and "true" or "false",
        clientPrefs.remoteBodyPhysics == true and "true" or "false",
        clampInt(clientPrefs.remoteChestStrength, 0, 100, 0),
        clientPrefs.remoteVoice ~= false and "true" or "false",
        clientPrefs.remoteSubtitles ~= false and "true" or "false"
    )
    GFL2CharacterRuntime.writeClientSettings(body)
end

local function syncPortrait(player)
    if not portraitHud or not portraitHud.sync then return end
    portraitHud.sync(player, clientPrefs, function(state)
        clientPrefs.portraitHud = state.enabled == true
        clientPrefs.portraitSize = clampInt(state.size, 140, 340, 200)
        clientPrefs.portraitZoom = clampInt(state.zoom, 100, 340, 265)
        clientPrefs.portraitYaw = clampInt(state.yaw, -45, 45, 6)
        clientPrefs.portraitPan = clampInt(state.pan, -25, 25, 0)
        clientPrefs.portraitLock = state.lock == true
        clientPrefs.portraitX = clampInt(state.x, -1, 16384, -1)
        clientPrefs.portraitY = clampInt(state.y, -1, 16384, -1)
        saveClientPrefs()
    end)
end

local function applyClientPrefs(player)
    if GFL2VoiceEngine then
        if GFL2VoiceEngine.setVolume then GFL2VoiceEngine.setVolume(player, clientPrefs.voiceVolume) end
        if GFL2VoiceEngine.setShowSubtitles then GFL2VoiceEngine.setShowSubtitles(clientPrefs.showSubtitles) end
        if GFL2VoiceEngine.setRemoteVoiceEnabled then GFL2VoiceEngine.setRemoteVoiceEnabled(clientPrefs.remoteVoice) end
        if GFL2VoiceEngine.setRemoteSubtitlesEnabled then GFL2VoiceEngine.setRemoteSubtitlesEnabled(clientPrefs.remoteSubtitles) end
    end
    if multiplayer and multiplayer.setShowRemoteModels then
        multiplayer.setShowRemoteModels(clientPrefs.showRemoteModels)
    end
    if GFL2CharacterRuntime then
        if GFL2CharacterRuntime.setStudioPreviewEnabled then GFL2CharacterRuntime.setStudioPreviewEnabled(clientPrefs.studioPreview ~= false) end
        if GFL2CharacterRuntime.setStudioResolutionPercent then GFL2CharacterRuntime.setStudioResolutionPercent(normalizeStudioScale(clientPrefs.studioScale)) end
        if GFL2CharacterRuntime.setIdleAmbientEnabled then GFL2CharacterRuntime.setIdleAmbientEnabled(clientPrefs.idleAmbient ~= false) end
        if GFL2CharacterRuntime.setFacialMotionMode then GFL2CharacterRuntime.setFacialMotionMode(clampInt(clientPrefs.facialMode, 0, 2, 2)) end
        if GFL2CharacterRuntime.setRemoteBodyPhysics then GFL2CharacterRuntime.setRemoteBodyPhysics(clientPrefs.remoteBodyPhysics == true) end
        if GFL2CharacterRuntime.setRemoteChestStrength then GFL2CharacterRuntime.setRemoteChestStrength(clientPrefs.remoteChestStrength or 0) end
    end
    syncPortrait(player)
end

local function loadClientPrefs(player)
    if not clientPrefsLoaded and GFL2CharacterRuntime and GFL2CharacterRuntime.readClientSettings then
        clientPrefsLoaded = true
        local raw = GFL2CharacterRuntime.readClientSettings()
        if type(raw) == "string" and raw ~= "" then
            local vv = tonumber(raw:match('"voiceVolume"%s*:%s*(%-?%d+)'))
            if vv then clientPrefs.voiceVolume = clampInt(vv, 0, 100, 50) end
            local ss = tonumber(raw:match('"studioScale"%s*:%s*(%-?%d+)'))
            if ss then clientPrefs.studioScale = normalizeStudioScale(ss) end
            local fm = tonumber(raw:match('"facialMode"%s*:%s*(%-?%d+)'))
            if fm then clientPrefs.facialMode = clampInt(fm, 0, 2, 2) end
            local ps = tonumber(raw:match('"portraitSize"%s*:%s*(%-?%d+)'))
            if ps then clientPrefs.portraitSize = clampInt(ps, 140, 340, 200) end
            local pz = tonumber(raw:match('"portraitZoom"%s*:%s*(%-?%d+)'))
            if pz then clientPrefs.portraitZoom = clampInt(pz, 100, 340, 265) end
            local pyw = tonumber(raw:match('"portraitYaw"%s*:%s*(%-?%d+)'))
            if pyw then clientPrefs.portraitYaw = clampInt(pyw, -45, 45, 6) end
            local pp = tonumber(raw:match('"portraitPan"%s*:%s*(%-?%d+)'))
            if pp then clientPrefs.portraitPan = clampInt(pp, -25, 25, 0) end
            local pf = tonumber(raw:match('"portraitFps"%s*:%s*(%-?%d+)'))
            if pf then clientPrefs.portraitFps = normalizePortraitFps(pf) end
            local px = tonumber(raw:match('"portraitX"%s*:%s*(%-?%d+)'))
            if px then clientPrefs.portraitX = clampInt(px, -1, 16384, -1) end
            local py = tonumber(raw:match('"portraitY"%s*:%s*(%-?%d+)'))
            if py then clientPrefs.portraitY = clampInt(py, -1, 16384, -1) end
            local rc = tonumber(raw:match('"remoteChestStrength"%s*:%s*(%-?%d+)'))
            if rc then clientPrefs.remoteChestStrength = clampInt(rc, 0, 100, 0) end
            for _, field in ipairs({"showSubtitles", "studioPreview", "idleAmbient", "portraitHud", "portraitLock", "showRemoteModels", "remoteBodyPhysics", "remoteVoice", "remoteSubtitles"}) do
                local v = raw:match('"' .. field .. '"%s*:%s*(%a+)')
                if v == "true" then clientPrefs[field] = true
                elseif v == "false" then clientPrefs[field] = false end
            end
        end
    end
    applyClientPrefs(player)
end

local function problem(player, message)
    if message and message ~= "" then
        print("[GFL2Exilium] " .. message)
        if player then player:Say(getText("ContextMenu_GFL2Character_Error")) end
        return true
    end
    return false
end

local function publish(player, saved)
    if isClient() then multiplayer.publish(player,saved) end
end

local function voiceChanged(player)
    if GFL2VoiceEngine then GFL2VoiceEngine.modelChanged(player) end
end

local function remember(player,quiet)
    expressionEdits[player]=nil
    voiceSaveEdits[player]=nil
    local previous=player:getModData()[key]
    local profiles=previous and previous.profiles or {}
    local saved = {
        model = GFL2CharacterRuntime.modelId(player),
        appearance = GFL2CharacterRuntime.appearanceJson(player),
        settings = GFL2CharacterRuntime.settingsJson(player),
        profiles = profiles,
        voiceVolume = previous and previous.voiceVolume,
    }
    profiles[saved.model]=saved.settings
    player:getModData()[key] = saved
    economy.saveLocal(player)
    publish(player, saved)
    if not quiet then voiceChanged(player) end
end

multiplayer.onRestored=remember

local function flushExpression(player)
    if not expressionEdits[player] then return end
    expressionEdits[player]=nil
    if GFL2CharacterRuntime and GFL2CharacterRuntime.isReady(player) then remember(player,true) end
end

local function enable(player, index)
    flushExpression(player)
    local previous=player:getModData()[key]
    local profiles=previous and previous.profiles or {}
    if previous and previous.model and previous.settings then profiles[previous.model]=previous.settings end
    if problem(player, GFL2CharacterRuntime.enableWithSettings(player,index,profiles[GFL2CharacterRuntime.modelIdAt(index)] or "")) then return false end
    local saved=player:getModData()[key] or {};saved.profiles=profiles;player:getModData()[key]=saved
    awaiting[player]=true;return true
end

Events.OnTick.Add(function()
    local now=getTimestampMs()
    for player,deadline in pairs(expressionEdits) do
        if now>=deadline then flushExpression(player) end
    end
    for player,deadline in pairs(voiceSaveEdits) do
        if now>=deadline then
            voiceSaveEdits[player]=nil
            economy.saveLocal(player)
            saveClientPrefs()
        end
    end
    for player in pairs(awaiting) do
        if GFL2CharacterRuntime.hasModelFailure(player) then
            awaiting[player]=nil
            problem(player,GFL2CharacterRuntime.status());voiceChanged(player)
        elseif GFL2CharacterRuntime.isReady(player) then
            awaiting[player]=nil;remember(player);syncPortrait(player)
        end
    end
end)

local function choice(player, group, option)
    if problem(player, GFL2CharacterRuntime.setChoice(player, group, option)) then return false end
    remember(player);return true
end

local function preset(player, index)
    if problem(player, GFL2CharacterRuntime.setPreset(player, index)) then return false end
    remember(player);return true
end

local function reload(player)
    if problem(player, GFL2CharacterRuntime.reloadAppearance(player)) then return false end
    remember(player);return true
end

local function material(player,index,visible)
    if problem(player,GFL2CharacterRuntime.setMaterialVisible(player,index,visible)) then return false end
    remember(player);return true
end
local function reset(player)
    if problem(player,GFL2CharacterRuntime.resetAppearance(player)) then return false end
    remember(player);return true
end

local function resetPhysics(player)
    return not problem(player,GFL2CharacterRuntime.resetModelPhysics(player))
end


local function setBodyPhysics(player,enabled)
    if problem(player,GFL2CharacterRuntime.setModelPhysics(player,enabled))then return false end
    remember(player,true)
    return true
end

local function setChestStrength(player,strength,immediate)
    local clamped=math.max(0,math.min(100,math.floor((tonumber(strength) or 100)+0.5)))
    if problem(player,GFL2CharacterRuntime.setChestStrength(player,clamped)) then return false end
    if immediate then remember(player,true)
    else expressionEdits[player]=getTimestampMs()+250 end
    return true
end

local function getVoiceVolume(player)
    if GFL2VoiceEngine and GFL2VoiceEngine.getVolume then return GFL2VoiceEngine.getVolume(player) end
    local saved=player and player:getModData()[key]
    if type(saved)=="table" and saved.voiceVolume~=nil then return math.max(0,math.min(100,math.floor((tonumber(saved.voiceVolume) or 50)+0.5))) end
    return 50
end

local function setVoiceVolume(player,volume,immediate)
    local clamped=math.max(0,math.min(100,math.floor((tonumber(volume) or 50)+0.5)))
    clientPrefs.voiceVolume=clamped
    if GFL2VoiceEngine and GFL2VoiceEngine.setVolume then
        clamped=GFL2VoiceEngine.setVolume(player,clamped)
    elseif player then
        local md=player:getModData()
        local saved=md[key]
        if type(saved)~="table" then saved={};md[key]=saved end
        saved.voiceVolume=clamped
    end
    if immediate then
        voiceSaveEdits[player]=nil
        economy.saveLocal(player)
        saveClientPrefs()
    else
        voiceSaveEdits[player]=getTimestampMs()+250
    end
    return clamped
end

local function getShowSubtitles()
    if GFL2VoiceEngine and GFL2VoiceEngine.getShowSubtitles then return GFL2VoiceEngine.getShowSubtitles() end
    return clientPrefs.showSubtitles ~= false
end

local function setShowSubtitles(enabled)
    local nextOn = enabled ~= false
    clientPrefs.showSubtitles = nextOn
    if GFL2VoiceEngine and GFL2VoiceEngine.setShowSubtitles then GFL2VoiceEngine.setShowSubtitles(nextOn) end
    saveClientPrefs()
    return nextOn
end

local function getStudioPreview()
    if GFL2CharacterRuntime and GFL2CharacterRuntime.studioPreviewEnabled then return GFL2CharacterRuntime.studioPreviewEnabled() end
    return clientPrefs.studioPreview ~= false
end

local function setStudioPreview(enabled)
    local nextOn = enabled ~= false
    clientPrefs.studioPreview = nextOn
    if GFL2CharacterRuntime and GFL2CharacterRuntime.setStudioPreviewEnabled then GFL2CharacterRuntime.setStudioPreviewEnabled(nextOn) end
    saveClientPrefs()
    return nextOn
end

local function getStudioScale()
    if GFL2CharacterRuntime and GFL2CharacterRuntime.studioResolutionPercent then return normalizeStudioScale(GFL2CharacterRuntime.studioResolutionPercent()) end
    return normalizeStudioScale(clientPrefs.studioScale)
end

local function setStudioScale(scale)
    local clamped = normalizeStudioScale(scale)
    clientPrefs.studioScale = clamped
    if GFL2CharacterRuntime and GFL2CharacterRuntime.setStudioResolutionPercent then GFL2CharacterRuntime.setStudioResolutionPercent(clamped) end
    saveClientPrefs()
    return clamped
end

local function getIdleAmbient()
    if GFL2CharacterRuntime and GFL2CharacterRuntime.idleAmbientEnabled then return GFL2CharacterRuntime.idleAmbientEnabled() end
    return clientPrefs.idleAmbient ~= false
end

local function setIdleAmbient(enabled)
    local nextOn = enabled ~= false
    clientPrefs.idleAmbient = nextOn
    if GFL2CharacterRuntime and GFL2CharacterRuntime.setIdleAmbientEnabled then GFL2CharacterRuntime.setIdleAmbientEnabled(nextOn) end
    saveClientPrefs()
    return nextOn
end

local function getFacialMode()
    if GFL2CharacterRuntime and GFL2CharacterRuntime.facialMotionMode then return GFL2CharacterRuntime.facialMotionMode() end
    return clampInt(clientPrefs.facialMode, 0, 2, 2)
end

local function setFacialMode(mode)
    local clamped = clampInt(mode, 0, 2, 2)
    clientPrefs.facialMode = clamped
    if GFL2CharacterRuntime and GFL2CharacterRuntime.setFacialMotionMode then GFL2CharacterRuntime.setFacialMotionMode(clamped) end
    saveClientPrefs()
    return clamped
end

local function getPortraitHud()
    return clientPrefs.portraitHud == true
end

local function setPortraitHud(player, enabled)
    local nextOn = enabled == true
    clientPrefs.portraitHud = nextOn
    syncPortrait(player)
    saveClientPrefs()
    return nextOn
end

local function getPortraitSize() return clampInt(clientPrefs.portraitSize, 140, 360, 220) end
local function setPortraitSize(player, size, immediate)
    local clamped = clampInt(size, 140, 360, 220)
    clientPrefs.portraitSize = clamped
    syncPortrait(player)
    if immediate then
        voiceSaveEdits[player] = nil
        saveClientPrefs()
    else
        voiceSaveEdits[player] = getTimestampMs() + 250
    end
    return clamped
end

local function getPortraitZoom() return clampInt(clientPrefs.portraitZoom, 100, 320, 210) end
local function setPortraitZoom(player, zoom, immediate)
    local clamped = clampInt(zoom, 100, 320, 210)
    clientPrefs.portraitZoom = clamped
    syncPortrait(player)
    if immediate then
        voiceSaveEdits[player] = nil
        saveClientPrefs()
    else
        voiceSaveEdits[player] = getTimestampMs() + 250
    end
    return clamped
end

local function getPortraitYaw() return clampInt(clientPrefs.portraitYaw, -45, 45, 8) end
local function setPortraitYaw(player, yaw, immediate)
    local clamped = clampInt(yaw, -45, 45, 8)
    clientPrefs.portraitYaw = clamped
    syncPortrait(player)
    if immediate then
        voiceSaveEdits[player] = nil
        saveClientPrefs()
    else
        voiceSaveEdits[player] = getTimestampMs() + 250
    end
    return clamped
end

local function getPortraitFrame() return clientPrefs.portraitFrame == true end
local function setPortraitFrame(player, enabled)
    local nextOn = enabled == true
    clientPrefs.portraitFrame = nextOn
    syncPortrait(player)
    saveClientPrefs()
    return nextOn
end

local function getPortraitLock() return clientPrefs.portraitLock == true end
local function setPortraitLock(player, enabled)
    local nextOn = enabled == true
    clientPrefs.portraitLock = nextOn
    syncPortrait(player)
    saveClientPrefs()
    return nextOn
end

local function getPortraitFps() return normalizePortraitFps(clientPrefs.portraitFps) end
local function setPortraitFps(player, fps, immediate)
    local clamped = normalizePortraitFps(fps)
    clientPrefs.portraitFps = clamped
    syncPortrait(player)
    if immediate ~= false then
        voiceSaveEdits[player] = nil
        saveClientPrefs()
    else
        voiceSaveEdits[player] = getTimestampMs() + 250
    end
    return clamped
end

local function resetPortraitHud(player)
    clientPrefs.portraitSize = 200
    clientPrefs.portraitZoom = 265
    clientPrefs.portraitYaw = 6
    clientPrefs.portraitPan = 0
    clientPrefs.portraitLock = false
    clientPrefs.portraitFps = 24
    clientPrefs.portraitX = -1
    clientPrefs.portraitY = -1
    syncPortrait(player)
    saveClientPrefs()
end

local function getShowRemoteModels()
    if multiplayer and multiplayer.getShowRemoteModels then return multiplayer.getShowRemoteModels() end
    return clientPrefs.showRemoteModels ~= false
end

local function setShowRemoteModels(enabled)
    local nextOn = enabled ~= false
    clientPrefs.showRemoteModels = nextOn
    if multiplayer and multiplayer.setShowRemoteModels then multiplayer.setShowRemoteModels(nextOn) end
    saveClientPrefs()
    return nextOn
end

local function getRemoteBodyPhysics()
    if GFL2CharacterRuntime and GFL2CharacterRuntime.remoteBodyPhysicsEnabled then return GFL2CharacterRuntime.remoteBodyPhysicsEnabled() end
    return clientPrefs.remoteBodyPhysics == true
end

local function setRemoteBodyPhysics(enabled)
    local nextOn = enabled == true
    clientPrefs.remoteBodyPhysics = nextOn
    if GFL2CharacterRuntime and GFL2CharacterRuntime.setRemoteBodyPhysics then GFL2CharacterRuntime.setRemoteBodyPhysics(nextOn) end
    saveClientPrefs()
    return nextOn
end

local function getRemoteChestStrength()
    if GFL2CharacterRuntime and GFL2CharacterRuntime.remoteChestStrength then return GFL2CharacterRuntime.remoteChestStrength() end
    return clientPrefs.remoteChestStrength or 0
end

local function setRemoteChestStrength(player, strength, immediate)
    local clamped = math.max(0, math.min(100, math.floor((tonumber(strength) or 0) + 0.5)))
    clientPrefs.remoteChestStrength = clamped
    if GFL2CharacterRuntime and GFL2CharacterRuntime.setRemoteChestStrength then GFL2CharacterRuntime.setRemoteChestStrength(clamped) end
    if immediate then
        voiceSaveEdits[player] = nil
        saveClientPrefs()
    else
        voiceSaveEdits[player] = getTimestampMs() + 250
    end
    return clamped
end

local function getRemoteVoiceEnabled()
    if GFL2VoiceEngine and GFL2VoiceEngine.getRemoteVoiceEnabled then return GFL2VoiceEngine.getRemoteVoiceEnabled() end
    return clientPrefs.remoteVoice ~= false
end

local function setRemoteVoiceEnabled(enabled)
    local nextOn = enabled ~= false
    clientPrefs.remoteVoice = nextOn
    if GFL2VoiceEngine and GFL2VoiceEngine.setRemoteVoiceEnabled then GFL2VoiceEngine.setRemoteVoiceEnabled(nextOn) end
    saveClientPrefs()
    return nextOn
end

local function getRemoteSubtitlesEnabled()
    if GFL2VoiceEngine and GFL2VoiceEngine.getRemoteSubtitlesEnabled then return GFL2VoiceEngine.getRemoteSubtitlesEnabled() end
    return clientPrefs.remoteSubtitles ~= false
end

local function setRemoteSubtitlesEnabled(enabled)
    local nextOn = enabled ~= false
    clientPrefs.remoteSubtitles = nextOn
    if GFL2VoiceEngine and GFL2VoiceEngine.setRemoteSubtitlesEnabled then GFL2VoiceEngine.setRemoteSubtitlesEnabled(nextOn) end
    saveClientPrefs()
    return nextOn
end

local function clearAppearance(player)
    expressionEdits[player]=nil
    voiceSaveEdits[player]=nil
    awaiting[player]=nil
    GFL2CharacterRuntime.disable(player)
    if portraitHud and portraitHud.hide then portraitHud.hide() end
    local saved=player:getModData()[key]
    player:getModData()[key] = saved and {profiles=saved.profiles,voiceVolume=saved.voiceVolume} or nil
    economy.saveLocal(player)
    voiceChanged(player)
end

multiplayer.onRejected=clearAppearance

local function disable(player)
    flushExpression(player)
    clearAppearance(player)
    publish(player, nil)
end

local function refresh(player)
    if problem(player,GFL2CharacterRuntime.refreshLibrary()) then return false end
    if isClient() then multiplayer.resync() end
    return true
end

local function restore(player)
    if not player or not GFL2CharacterRuntime then return end
    economy.restoreLocal(player)
    loadClientPrefs(player)
    if isClient() then return end
    local saved = player:getModData()[key]
    if type(saved) == "table" and type(saved.model) == "string" then
        local message
        if saved.settings then message = GFL2CharacterRuntime.queueSettings(player, saved.model, saved.settings)
        else message = GFL2CharacterRuntime.queueRestore(player, saved.model, saved.appearance or "") end
        if not problem(nil, message) then publish(player, saved) end
    else
        publish(player, nil)
    end
end

local function open(player,target)
    loadClientPrefs(player)
    window.open(player,{enable=enable,choice=choice,preset=preset,reload=reload,disable=disable,material=material,reset=reset,refresh=refresh,physicsReset=resetPhysics,bodyPhysics=setBodyPhysics,
        chestStrength=setChestStrength,getVoiceVolume=getVoiceVolume,setVoiceVolume=setVoiceVolume,
        getShowSubtitles=getShowSubtitles,setShowSubtitles=setShowSubtitles,
        getStudioPreview=getStudioPreview,setStudioPreview=setStudioPreview,
        getStudioScale=getStudioScale,setStudioScale=setStudioScale,
        getIdleAmbient=getIdleAmbient,setIdleAmbient=setIdleAmbient,
        getFacialMode=getFacialMode,setFacialMode=setFacialMode,
        getPortraitHud=getPortraitHud,setPortraitHud=setPortraitHud,
        getPortraitSize=getPortraitSize,setPortraitSize=setPortraitSize,
        getPortraitZoom=getPortraitZoom,setPortraitZoom=setPortraitZoom,
        getPortraitYaw=getPortraitYaw,setPortraitYaw=setPortraitYaw,
        getPortraitFrame=getPortraitFrame,setPortraitFrame=setPortraitFrame,
        getPortraitLock=getPortraitLock,setPortraitLock=setPortraitLock,
        getPortraitFps=getPortraitFps,setPortraitFps=setPortraitFps,
        resetPortraitHud=resetPortraitHud,
        getShowRemoteModels=getShowRemoteModels,setShowRemoteModels=setShowRemoteModels,
        getRemoteBodyPhysics=getRemoteBodyPhysics,setRemoteBodyPhysics=setRemoteBodyPhysics,
        getRemoteChestStrength=getRemoteChestStrength,setRemoteChestStrength=setRemoteChestStrength,
        getRemoteVoiceEnabled=getRemoteVoiceEnabled,setRemoteVoiceEnabled=setRemoteVoiceEnabled,
        getRemoteSubtitlesEnabled=getRemoteSubtitlesEnabled,setRemoteSubtitlesEnabled=setRemoteSubtitlesEnabled,
        expressionRemember=function(p)remember(p,true)end,
        expressionEdited=function(p)expressionEdits[p]=getTimestampMs()+250 end},target)
end

local javaHelpModal = nil
local javaHelpAutoShown = false

local function closeJavaHelp()
    if javaHelpModal then
        pcall(function() javaHelpModal:destroy() end)
        javaHelpModal = nil
    end
end

local function detectJavaStatus()
    local mods = getActivatedMods and getActivatedMods()
    local zbMod = mods ~= nil and mods:contains("ZombieBuddy") == true
    local zbVersion = nil
    if ZombieBuddy and type(ZombieBuddy.getVersion) == "function" then
        local ok, ver = pcall(ZombieBuddy.getVersion)
        if ok and ver ~= nil and tostring(ver) ~= "" then
            zbVersion = tostring(ver)
        end
    end
    return zbMod, zbVersion
end

local function showJavaHelp(player)
    if not (ISModalRichText and getCore) then return end
    closeJavaHelp()
    local zbMod, zbVersion = detectJavaStatus()
    local okColor = "<RGB:0.45,0.92,0.55> "
    local badColor = "<RGB:1,0.45,0.45> "
    local headColor = "<RGB:1,0.78,0.28> "
    local bodyColor = "<RGB:1,1,1> "
    local modLine = (zbMod and okColor or badColor)
        .. getText(zbMod and "ContextMenu_GFL2_JavaHelp_ZbModOn" or "ContextMenu_GFL2_JavaHelp_ZbModOff")
    local runtimeLine = zbVersion
        and (okColor .. getText("ContextMenu_GFL2_JavaHelp_ZbRuntimeOn", zbVersion))
        or (badColor .. getText("ContextMenu_GFL2_JavaHelp_ZbRuntimeOff"))
    local jarLine = badColor .. getText("ContextMenu_GFL2_JavaHelp_JarOff")
    local text = table.concat({
        " <CENTRE> <SIZE:medium> " .. headColor .. getText("ContextMenu_GFL2_JavaHelp_Title"),
        "",
        " <LEFT> <SIZE:small> " .. bodyColor .. getText("ContextMenu_GFL2_JavaHelp_Intro"),
        "",
        headColor .. getText("ContextMenu_GFL2_JavaHelp_StatusHeader"),
        modLine,
        runtimeLine,
        jarLine,
        "",
        headColor .. getText("ContextMenu_GFL2_JavaHelp_StepsHeader"),
        bodyColor .. getText("ContextMenu_GFL2_JavaHelp_Step1"),
        bodyColor .. getText("ContextMenu_GFL2_JavaHelp_Step2"),
        bodyColor .. getText("ContextMenu_GFL2_JavaHelp_Step3"),
        bodyColor .. getText("ContextMenu_GFL2_JavaHelp_Step4"),
    }, " <LINE> ")
    local core = getCore()
    local sw, sh = core:getScreenWidth(), core:getScreenHeight()
    local width = math.min(620, math.max(420, math.floor(sw * 0.45)))
    local height = 340
    local x = math.floor((sw - width) / 2)
    local y = math.floor((sh - height) / 2)
    local playerIndex = (player and player.getPlayerNum) and player:getPlayerNum() or 0
    local modal = ISModalRichText:new(x, y, width, height, text, false, nil, function() javaHelpModal = nil end, playerIndex)
    modal:initialise()
    modal:addToUIManager()
    modal:setAlwaysOnTop(true)
    javaHelpModal = modal
end

GFL2CharacterActions = {
    open = open,
    resetPhysics = resetPhysics,
    setBodyPhysics = setBodyPhysics,
    isPortraitHudEnabled = getPortraitHud,
    togglePortraitHud = function(player)
        loadClientPrefs(player)
        return setPortraitHud(player, not getPortraitHud())
    end,
    showJavaHelp = showJavaHelp,
}

local function menu(playerIndex, context, worldObjects, test)
    if test then return ISWorldObjectContextMenu.setTest() end
    local player = getSpecificPlayer(playerIndex)
    if not player then return end
    if not GFL2CharacterRuntime then
        context:addOption(getText("ContextMenu_GFL2Character_JavaUnavailable"), player, showJavaHelp)
        return
    end
    context:addOption(getText("ContextMenu_GFL2Character_Menu"),player,open)
end

Events.OnFillWorldObjectContextMenu.Add(menu)
Events.OnGameStart.Add(function()
    awaiting={}
    expressionEdits={}
    voiceSaveEdits={}
    window.closeAll()
    closeJavaHelp()
    started = true
    if not GFL2CharacterRuntime then
        if not javaHelpAutoShown then
            javaHelpAutoShown = true
            showJavaHelp(getSpecificPlayer and getSpecificPlayer(0) or nil)
        end
        return
    end
    GFL2CharacterRuntime.clear()
    for i = 0, 3 do restore(getSpecificPlayer(i)) end
end)
Events.OnCreatePlayer.Add(function(index, player)
    if started then
        restore(player)
    end
end)
Events.OnSave.Add(function()
    if started then
        for i = 0, 3 do
            local player = getSpecificPlayer(i)
            if player and not player:isDead() then
                flushExpression(player)
                voiceSaveEdits[player]=nil
                economy.saveLocal(player)
            end
        end
    end
end)
Events.OnPlayerDeath.Add(function(player)
    flushExpression(player)
    voiceSaveEdits[player]=nil
    economy.saveLocal(player)
    awaiting[player]=nil
    window.closeFor(player)
    if portraitHud and portraitHud.hide then portraitHud.hide() end
    if GFL2CharacterRuntime then
        GFL2CharacterRuntime.disable(player)
    end
end)
if Events.OnMainMenuEnter then
    Events.OnMainMenuEnter.Add(function()
        started = false
        javaHelpAutoShown = false
        awaiting={}
        expressionEdits={}
        voiceSaveEdits={}
        window.closeAll()
        closeJavaHelp()
        if portraitHud and portraitHud.hide then portraitHud.hide() end
        if GFL2CharacterRuntime then GFL2CharacterRuntime.clear() end
    end)
end
Events.OnDisconnect.Add(function()
    awaiting={}
    expressionEdits={}
    voiceSaveEdits={}
    window.closeAll()
    closeJavaHelp()
    if portraitHud and portraitHud.hide then portraitHud.hide() end
    started = false
    javaHelpAutoShown = false
    if GFL2CharacterRuntime then GFL2CharacterRuntime.clear() end
end)
