local callbacks = {}
local function event(name)
    return {Add = function(fn) callbacks[name] = fn end}
end
Events = {OnServerCommand=event("server"), OnTick=event("tick"),
    OnDisconnect=event("disconnect"), OnMainMenuEnter=event("menu")}
local clientOnline = false
function isClient() return clientOnline end
function isServer() return false end
local actor = {data={},onlineId=4,dead=false}
function actor:getOnlineID() return self.onlineId end
function actor:getModData() return self.data end
function actor:isDead() return self.dead end
function actor:isLocalPlayer() return true end
function getSpecificPlayer(index) if index==0 then return actor end end
local peer = {}
function peer:isLocalPlayer() return false end
function peer:getUsername() return "remote" end
function peer:isDead() return self.dead==true end
function getPlayerByOnlineID(id) if id==4 then return actor elseif id==5 then return peer end end
local sent = {}
function sendClientCommand(_,_,command,args)
    sent[#sent+1] = {command=command,request=args.request,model=args.model,appearance=args.appearance,attempt=args.attempt,
        handshake=args.handshake,session=args.session,sound=args.sound,kind=args.kind}
end

local actions, face, requestedSettings, voiceUpdates
local model="pmx-abe3eaaaa9d04264"
local renderedModel,renderedReady,queuedModel
local clock=1000
function getTimestampMs() return clock end
function prepareExpressionEdits()
    Events = {OnTick=event("previewTick"),OnDisconnect=event("previewDisconnect"),
        OnGameStart=event("start"),OnCreatePlayer=event("create"),OnSave=event("save"),
        OnPlayerDeath=event("death"),OnFillWorldObjectContextMenu=event("context")}
    GFL2RoomWindow={open=function(p,a) assert(p==actor);actions=a end,closeAll=function()end}
    function getText(key) return key end
    GFL2CharacterRuntime.clear=function()end
    renderedModel,renderedReady=model,true
    GFL2CharacterRuntime.isReady=function() return renderedReady end
    GFL2CharacterRuntime.modelId=function() return renderedModel end
    GFL2CharacterRuntime.modelIdAt=function() return "pmx-40dbcd572c1fb3f8" end
    GFL2CharacterRuntime.disable=function(p)
        assert(p==actor)
        renderedModel,renderedReady="",false
    end
    GFL2CharacterRuntime.queueOwnedAppearance=function(p,id,appearance,settings)
        assert(p==actor and id==model)
        queuedModel=id;requestedSettings=settings
        return ""
    end
    GFL2CharacterRuntime.appearanceJson=function() return face end
    GFL2CharacterRuntime.settingsJson=function() return face end
    GFL2CharacterRuntime.networkAppearance=function() return face end
    GFL2CharacterRuntime.enableWithSettings=function(p,id,settings)
        assert(p==actor and id==1)
        requestedSettings=settings
        renderedReady=false
        return ""
    end
    voiceUpdates=0
    GFL2VoiceEngine={modelChanged=function(p) assert(p==actor);voiceUpdates=voiceUpdates+1 end}
    actor.data.GFL2CharacterRuntime.profiles["pmx-40dbcd572c1fb3f8"]="other profile"
end

function verifyExpressionEdits()
    callbacks.start()
    callbacks.context(0,{addOption=function(_,_,p,open)open(p)end},{},false)
    callbacks.disconnect()
    face='{"expressions":{"Smile":0.45}}'
    callbacks.tick()
    callbacks.server("GFL2Exilium","Wallet",{version=1,player=6,request=3,wallet={},
        equipped={model=model,signature=string.rep("a",64),appearance=face}})
    assert(voiceUpdates==0 and actor.data.GFL2CharacterRuntime.model~=model)
    clock=clock+250;callbacks.tick()
    assert(voiceUpdates==1 and actor.data.GFL2CharacterRuntime.model==model)
    assert(actor.data.GFL2CharacterRuntime.settings==face and sent[#sent].appearance==face)
    local sentBefore=#sent
    face='{"expressions":{"Smile":0.2}}';actions.expressionEdited(actor)
    clock=clock+200;callbacks.previewTick()
    assert(#sent==sentBefore)
    face='{"expressions":{"Smile":0.9}}';actions.expressionEdited(actor)
    clock=clock+249;callbacks.previewTick()
    assert(#sent==sentBefore)
    clock=clock+1;callbacks.previewTick()
    assert(#sent==sentBefore+1 and sent[#sent].appearance==face)
    assert(actor.data.GFL2CharacterRuntime.profiles[model]==face)
    callbacks.previewTick()
    assert(#sent==sentBefore+1)
    face='{"expressions":{}}';actions.expressionEdited(actor)
    assert(actions.enable(actor,1) and requestedSettings=="other profile")
    assert(#sent==sentBefore+2 and sent[#sent].appearance==face)
    assert(actor.data.GFL2CharacterRuntime.profiles[model]==face)
    assert(voiceUpdates==1)
    return "PASS reconnect restores local profile and voice once; expression edits save once and flush before changing outfit"
end

function verifyAppearanceRejections()
    local function reject(attempt,equipped)
        callbacks.server("GFL2Exilium","AppearanceRejected",{version=1,player=6,attempt=attempt,equipped=equipped})
    end
    local older=sent[#sent].attempt or 7
    reject(older)
    assert(renderedModel==model and voiceUpdates==1,"an old rejection interrupted a new model load")
    renderedModel,renderedReady="pmx-40dbcd572c1fb3f8",true
    callbacks.previewTick()
    assert(older~=sent[#sent].attempt and actor.data.GFL2CharacterRuntime.model==renderedModel)
    local current=sent[#sent].attempt
    reject(older)
    clock=clock+250;callbacks.tick()
    assert(renderedModel=="pmx-40dbcd572c1fb3f8" and voiceUpdates==2,"the last authorized model was cleared")
    local sentBefore=#sent
    queuedModel=nil
    local savedProfile=actor.data.GFL2CharacterRuntime.profiles[model]
    reject(current,{model=model,signature=string.rep("a",64),appearance=face})
    assert(renderedModel=="" and voiceUpdates==3 and #sent==sentBefore)
    assert(actor.data.GFL2CharacterRuntime.model==nil and actor.data.GFL2CharacterRuntime.profiles[model]==savedProfile)
    assert(queuedModel==model and requestedSettings==savedProfile)
    renderedModel,renderedReady=queuedModel,true
    clock=clock+250;callbacks.tick()
    assert(actor.data.GFL2CharacterRuntime.model==model and voiceUpdates==4 and #sent==sentBefore+1)
    reject(sent[#sent].attempt)
    assert(renderedModel=="" and voiceUpdates==5 and #sent==sentBefore+1)
    assert(actor.data.GFL2CharacterRuntime.model==nil and actor.data.GFL2CharacterRuntime.profiles[model]==face)
    return "PASS late appearance rejection leaves the newer model intact, current rejection restores the approved model and its voice"
end
local applied, disabled, cleared, restoredSettings = 0, 0, 0, nil
local expectedDisablePeer = peer
local signature = string.rep("a",64)
GFL2CharacterRuntime = {
    hasModelFailure=function() return false end,
    networkSignature=function() return signature end,
    queueOwnedAppearance=function(player,model,appearance,settings)
        assert(player==actor and model=="pmx-abe3eaaaa9d04264")
        restoredSettings=settings
        return ""
    end,
    queueRemoteAppearance=function(player,model)
        assert(player==peer)
        if model=="" then cleared=cleared+1
        else assert(model=="pmx-abe3eaaaa9d04264");applied=applied+1 end
        return ""
    end,
    disable=function(player) assert(player==(peer or expectedDisablePeer));disabled=disabled+1 end,
}
function verifyClient(client,economy)
    local function receive(command,data)
        data.version=1
        callbacks.server("GFL2Exilium",command,data)
    end
    -- Loaded while isClient()==false (Main Menu pre-load); API must exist and stay dormant until MP starts.
    assert(type(client.ready)=="function" and type(client.pending)=="function" and type(client.transact)=="function")
    assert(not client.ready(actor) and not client.pending(actor))
    assert(not client.transact(actor,"DrawCharacter","pmx-abe3eaaaa9d04264",1,function()end))
    callbacks.tick()
    assert(#sent==0)
    clientOnline=true

    callbacks.tick()
    assert(sent[1].command=="Request" and not client.ready(actor))
    receive("Wallet",{player=4,request=0,wallet={targeted=10}})
    assert(client.ready(actor) and economy.balance(actor,"targeted")==10)
    local results=0
    local function onResult(result)
        assert(result.status=="unlocked")
        results=results+1
    end
    assert(client.transact(actor,"DrawCharacter","pmx-abe3eaaaa9d04264",1,onResult))
    assert(not client.transact(actor,"DrawCharacter","pmx-abe3eaaaa9d04264",1,onResult))
    clock=4100;callbacks.tick()
    assert(sent[2].request==1 and sent[3].request==1)
    local result={player=4,request=1,wallet={targeted=9},result={status="unlocked"}}
    receive("Transaction",result)
    receive("Transaction",result)
    assert(results==1 and economy.balance(actor,"targeted")==9)

    assert(client.transact(actor,"BuyOutfit","pmx-abe3eaaaa9d04264",nil,onResult))
    receive("Wallet",{player=4,request=2,wallet={targeted=9}})
    assert(client.pending(actor) and results==1)
    clock=7200;callbacks.tick()
    assert(sent[#sent].command=="BuyOutfit" and sent[#sent].request==2)
    receive("Transaction",{player=4,request=2,wallet={targeted=9},result={status="unlocked"}})
    assert(not client.pending(actor) and results==2)

    local model="pmx-abe3eaaaa9d04264"
    receive("Appearance",{player=5,owner="remote",revision=2,model=model,
        signature=string.rep("a",64),appearance="{}"})
    clock=7500;callbacks.tick()
    assert(applied==1)
    receive("Appearance",{player=5,owner="remote",revision=1,model=model,
        signature=string.rep("a",64),appearance="{}"})
    clock=7800;callbacks.tick()
    assert(applied==1)
    receive("Snapshot",{player=4,revision=3,states={}})
    assert(disabled==1)
    receive("Appearance",{player=5,owner="remote",revision=2,model=model,
        signature=string.rep("a",64),appearance="{}"})
    clock=8100;callbacks.tick()
    assert(applied==1)
    receive("Appearance",{player=5,owner="remote",revision=4,model=model,signature=signature,appearance="{}",session=24})
    clock=8400;callbacks.tick()
    assert(applied==2)
    peer.dead=true
    clock=8700;callbacks.tick()
    assert(disabled==2)
    receive("Appearance",{player=5,owner="remote",revision=5,model=model,signature=signature,appearance="{}",session=24})
    peer=setmetatable({}, {__index={isLocalPlayer=peer.isLocalPlayer,getUsername=peer.getUsername,isDead=peer.isDead}})
    clock=9000;callbacks.tick()
    assert(applied==2,"the new remote survivor inherited the dead actor's appearance")
    receive("Appearance",{player=5,owner="remote",revision=6,model=model,signature=signature,appearance="{}",session=25})
    clock=9300;callbacks.tick()
    assert(applied==3)
    receive("Appearance",{player=5,owner="remote",revision=7,model=model,signature=signature,appearance="{}",session=26})
    clock=9600;callbacks.tick()
    assert(applied==3 and disabled==3,"new appearance was applied to the previous life before the PZ player arrived")
    peer=setmetatable({}, {__index={isLocalPlayer=peer.isLocalPlayer,getUsername=peer.getUsername,isDead=peer.isDead}})
    clock=9900;callbacks.tick()
    assert(applied==4)
    receive("Appearance",{player=5,owner="remote",revision=8,model="",signature="",appearance="",session=26})
    clock=10200;callbacks.tick()
    assert(applied==4 and cleared==1)
    receive("Appearance",{player=5,owner="remote",revision=9,model=model,signature=signature,appearance="{}",session=26})
    clock=10500;callbacks.tick()
    assert(applied==5,"the same live player could not re-enable an appearance")
    expectedDisablePeer=peer;peer=nil
    clock=10800;callbacks.tick()
    assert(disabled==4 and applied==5,"stream-out must release the live remote model without changing applied count")
    peer=setmetatable({},{__index={isLocalPlayer=expectedDisablePeer.isLocalPlayer,getUsername=expectedDisablePeer.getUsername,isDead=expectedDisablePeer.isDead}})
    clock=11100;callbacks.tick()
    assert(applied==6,"driving back into streaming range with a fresh peer instance did not re-apply PMX")
    expectedDisablePeer=peer;peer=nil
    clock=11400;callbacks.tick()
    assert(disabled==5 and applied==6)
    peer=expectedDisablePeer
    clock=11700;callbacks.tick()
    assert(applied==7,"returning into streaming range with the same peer instance did not re-apply PMX")
    peer.dead=true
    clock=12000;callbacks.tick()
    assert(disabled==6)
    peer=setmetatable({},{__index={isLocalPlayer=peer.isLocalPlayer,getUsername=peer.getUsername,isDead=peer.isDead}})
    clock=12300;callbacks.tick()
    assert(applied==7,"a dead player's respawned actor on the same session must still be blocked")
    receive("Appearance",{player=5,owner="remote",revision=10,model="",signature="",appearance="",session=26})
    clock=12600;callbacks.tick()
    callbacks.disconnect()
    assert(not client.ready(actor) and economy.balance(actor,"targeted")==0)
    actor.data.GFL2CharacterRuntime={model="other-model",profiles={[model]="saved settings for that outfit"}}
    callbacks.tick()
    receive("Wallet",{player=4,request=2,wallet={},equipped={model=model,signature=signature,appearance="{}"}})
    assert(restoredSettings=="saved settings for that outfit")
    actor.dead=true
    assert(not client.ready(actor))
    clock=clock+300;callbacks.tick()
    receive("Wallet",{player=4,request=3,wallet={targeted=25}})
    assert(economy.balance(actor,"targeted")==0)
    actor.onlineId=6;actor.dead=false
    local oldHandshake=sent[#sent].handshake
    clock=clock+300;callbacks.tick()
    assert(sent[#sent].command=="Request" and not client.ready(actor))
    local newHandshake=sent[#sent].handshake
    assert(oldHandshake and newHandshake and oldHandshake~=newHandshake)
    receive("Wallet",{player=6,request=9,wallet={targeted=25},handshake=oldHandshake,session=34})
    assert(not client.ready(actor) and economy.balance(actor,"targeted")==0)
    receive("Wallet",{player=6,request=3,wallet={targeted=2},handshake=newHandshake,session=35})
    assert(client.ready(actor) and economy.balance(actor,"targeted")==2)
    assert(client.transact(actor,"DrawCharacter",model,1,onResult) and sent[#sent].session==35)
    receive("Transaction",{player=6,request=4,wallet={targeted=25},session=34,result={status="unlocked"}})
    assert(client.pending(actor) and economy.balance(actor,"targeted")==2 and results==2)
    receive("Transaction",{player=6,request=4,wallet={targeted=1},session=35,result={status="unlocked"}})
    assert(not client.pending(actor) and economy.balance(actor,"targeted")==1 and results==3)

    -- Pending transaction stops retrying after 4 resends, fails cleanly, and triggers Request sync.
    local timedOut=0
    assert(client.transact(actor,"DrawCharacter",model,1,function(r) assert(r.status=="unavailable");timedOut=timedOut+1 end))
    for _=1,4 do
        clock=clock+3000;callbacks.tick()
        assert(client.pending(actor) and timedOut==0)
    end
    clock=clock+3000;callbacks.tick()
    assert(not client.pending(actor) and timedOut==1)
    clock=clock+300;callbacks.tick()
    assert(sent[#sent].command=="Request")
    local rotatedHs=sent[#sent].handshake
    receive("Wallet",{player=6,request=5,wallet={targeted=7},handshake=rotatedHs,session=36})
    assert(economy.balance(actor,"targeted")==7)
    assert(client.transact(actor,"DrawCharacter",model,1,onResult) and sent[#sent].session==36)
    receive("Transaction",{player=6,request=6,wallet={targeted=99},session=35,result={status="unlocked"}})
    assert(client.pending(actor) and economy.balance(actor,"targeted")==7 and results==3)
    receive("Transaction",{player=6,request=6,wallet={targeted=6},session=36,result={status="unlocked"}})
    assert(not client.pending(actor) and economy.balance(actor,"targeted")==6 and results==4)

    -- Changed pack signature still restores equipped character and falls back to queueSettings on material mismatch.
    callbacks.disconnect()
    actor.data.GFL2CharacterRuntime={model="other-model",profiles={[model]="updated local profile"}}
    local prevOwned=GFL2CharacterRuntime.queueOwnedAppearance
    local fallbackSettings
    GFL2CharacterRuntime.queueOwnedAppearance=function() return "Material count mismatch" end
    GFL2CharacterRuntime.queueSettings=function(p,id,s) assert(p==actor and id==model);fallbackSettings=s;return "" end
    callbacks.tick()
    receive("Wallet",{player=6,request=6,wallet={},handshake=sent[#sent].handshake,session=37,
        equipped={model=model,signature=string.rep("b",64),appearance="{}"}})
    assert(fallbackSettings=="updated local profile")
    GFL2CharacterRuntime.queueOwnedAppearance,GFL2CharacterRuntime.queueSettings=prevOwned,nil

    -- Pre-ready publish is queued and flushed on Wallet without being overwritten by stale server equipped.
    callbacks.disconnect()
    GFL2CharacterRuntime.networkAppearance=function() return '{"choices":{},"material_visibility":[]}' end
    local sentBeforePublish=#sent
    client.publish(actor,{model=model})
    assert(#sent==sentBeforePublish)
    callbacks.tick()
    restoredSettings=nil
    receive("Wallet",{player=6,request=0,wallet={},handshake=sent[#sent].handshake,session=38,
        equipped={model="pmx-0000000000000000",signature=signature,appearance="{}"}})
    assert(sent[#sent].command=="Appearance" and sent[#sent].model==model and restoredSettings==nil)
    GFL2CharacterRuntime.networkAppearance=nil

    -- Remote pack version mismatch falls back to queueRemoteDefault, syncs remote voice mute, and relays Voice.
    peer=setmetatable({},{__index={isLocalPlayer=function()return false end,getUsername=function()return "remote" end,isDead=function()return false end}})
    expectedDisablePeer=peer
    local prevRemote=GFL2CharacterRuntime.queueRemoteAppearance
    local defaultApplied,remoteMuted,remoteSpoken=0,nil,nil
    GFL2VoiceEngine={
        syncRemote=function(p,on) assert(p==peer);remoteMuted=on end,
        playRemote=function(p,snd,k) assert(p==peer);remoteSpoken={sound=snd,kind=k} end,
    }
    GFL2CharacterRuntime.queueRemoteAppearance=function(p,id,sig)
        assert(p==peer)
        if id=="" then cleared=cleared+1;return "" end
        if sig~=signature then return "Character pack version mismatch" end
        applied=applied+1;return ""
    end
    GFL2CharacterRuntime.queueRemoteDefault=function(p,id)
        assert(p==peer and id==model)
        defaultApplied=defaultApplied+1
        return ""
    end
    receive("Appearance",{player=5,owner="remote",revision=20,model=model,signature=string.rep("c",64),appearance="{}",session=40})
    clock=clock+300;callbacks.tick()
    assert(defaultApplied==1 and remoteMuted==true)
    client.publishVoice(actor,"GFL2PmxLaiya_Shout_A","shout")
    assert(sent[#sent].command=="Voice" and sent[#sent].sound=="GFL2PmxLaiya_Shout_A" and sent[#sent].kind=="shout")
    receive("Voice",{player=5,owner="remote",session=40,sound="GFL2PmxLaiya_Shout_A",kind="shout"})
    assert(remoteSpoken and remoteSpoken.sound=="GFL2PmxLaiya_Shout_A" and remoteSpoken.kind=="shout")
    remoteSpoken=nil
    receive("Voice",{player=5,owner="remote",session=99,sound="GFL2PmxLaiya_Shout_A",kind="shout"})
    assert(remoteSpoken==nil,"mismatched session triggered remote voice")
    client.resync()
    clock=clock+300;callbacks.tick()
    assert(defaultApplied==2,"resync did not retry full sync for default-fallback remote actor")
    receive("Appearance",{player=5,owner="remote",revision=21,model="",signature="",appearance="",session=40})
    clock=clock+300;callbacks.tick()
    assert(remoteMuted==false)
    receive("Voice",{player=5,owner="remote",session=40,sound="GFL2PmxLaiya_Shout_A",kind="shout"})
    assert(remoteSpoken==nil,"disabled remote GFL2 actor still played GFL2 voice")
    GFL2CharacterRuntime.queueRemoteAppearance,GFL2CharacterRuntime.queueRemoteDefault,GFL2VoiceEngine=prevRemote,nil,nil

    return "PASS client pre-load safety, handshake, remote pack-mismatch fallback, voice sync, disconnect and respawn"
end
