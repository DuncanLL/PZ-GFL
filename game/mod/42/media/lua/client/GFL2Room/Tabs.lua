local M={}

function M.install(W,ctx)
    local button=ctx.button
    local clamp=ctx.clamp
    local descriptionHeight=ctx.descriptionHeight
    local getData=ctx.data
    local hideTooltip=ctx.hideTooltip
    local label=ctx.label
    local styleSlider=ctx.styleSlider
    local textInSlot=ctx.textInSlot
    local tr=ctx.tr

    local function expressionPreset(preset,rows)
        local fields={}
        for axis=2,#preset do
            for _,channel in ipairs(preset[axis]) do
                if rows[channel[1]]~=nil then
                    fields[#fields+1]='"'..channel[2]..'":'..tostring(channel[3])
                    break
                end
            end
        end
        if #preset==1 or #fields>0 then return "{"..table.concat(fields,",").."}" end
    end

    local function localizedLabel(kind,value)
        return (getTextOrNull and getTextOrNull("UI_GFL2_"..kind.."_"..value)) or value
    end

    local function attachSliderTooltip(slider,text)
        slider.doToolTip=false
        slider.tooltip=text
        local basePrerender=slider.prerender
        slider.prerender=function(s)
            if basePrerender then basePrerender(s) end
            if s.dragInside then hideTooltip(s)
            elseif ISButton and ISButton.updateTooltip then ISButton.updateTooltip(s) end
        end
    end

    function W:partRows(extrasOnly)
        local data=getData()
        local names=self.selected and self.selected.meta.primaryCategories or {data.text.Head,data.text.Top,data.text.Bottom}
        local primary={};for _,name in ipairs(names)do primary[name]=true end
        local groups=GFL2CharacterRuntime.groupCount(self.player);local categories={};local extra={}
        for group=0,groups-1 do
            local name=GFL2CharacterRuntime.groupLabel(self.player,group)
            local normalized=data.categories[name] or name
            local blink=false
            for option=0,GFL2CharacterRuntime.optionCount(self.player,group)-1 do
                local optionLabel=GFL2CharacterRuntime.optionLabel(self.player,group,option)
                if optionLabel==data.text.EyeOpen or optionLabel==data.text.EyeClosed then blink=true;break end
            end
            if not blink then
                local row={index=group,label=normalized}
                if primary[normalized] then categories[normalized]=row else table.insert(extra,row) end
            else
                primary[normalized]=nil
            end
        end
        local ordered={}
        if extrasOnly then ordered=extra else
            for _,name in ipairs(names)do
                if primary[name] then table.insert(ordered,categories[name] or {label=name}) end
            end
        end
        return ordered
    end

    function W:drawGroups(ordered,y,width)
        local step=descriptionHeight()
        local textHeight=math.max(20,step-2)
        local tileHeight=math.max(77,step+48)
        local rowStep=tileHeight+8
        for _,row in ipairs(ordered) do
            self:addLabel(localizedLabel("PartGroup",row.label),y,textHeight)
            y=y+textHeight+10
            if row.index then
                local group=row.index
                local count=GFL2CharacterRuntime.optionCount(self.player,group)
                local selectedOption=GFL2CharacterRuntime.selectedOption(self.player,group)
                local pictures=self.selected.meta.partImages
                local groupPictures=pictures and pictures[GFL2CharacterRuntime.groupLabel(self.player,group)]
                for option=0,count-1 do
                    local cardWidth=(width-16)/3
                    local x=(option%3)*(cardWidth+8)
                    local yy=y+math.floor(option/3)*rowStep
                    local optionLabel=GFL2CharacterRuntime.optionLabel(self.player,group,option)
                    self:addTile(x,yy,cardWidth,tileHeight,localizedLabel("PartOption",optionLabel),
                        groupPictures and groupPictures[optionLabel],selectedOption==option,function(s)
                            if s.actions.choice(s.player,group,option) then s:refreshControls(true) end
                        end)
                end
                y=y+math.ceil(count/3)*rowStep+10
            else
                local p=ISPanel:new(0,y,width,textHeight+16);p:initialise();p.background=false
                p.prerender=function(s)label(s,tr("NoCategory"),5,6,17,s.width-10,.56,.66,.69)end
                self.content:addChild(p);table.insert(self.widgets,p);y=y+textHeight+26
            end
        end
        return y
    end

    function W:addSettingsSection(title,y,width)
        local p=ISPanel:new(0,y,width,26);p:initialise();p.background=false
        p.prerender=function(s)
            s:drawRect(0,6,2,13,.94,.95,.49,.14)
            label(s,title,9,2,18,s.width-12,.92,.94,.95)
            s:drawRect(0,24,s.width,1,.14,.84,.86,.88)
        end
        self.content:addChild(p);table.insert(self.widgets,p)
        return y+34
    end

    function W:buildSettingsTab(y,width,editable)
        local function addToggle(titleText,active,tipOn,tipOff,onToggle)
            local btn=button(self.content,0,y,width,titleText,self,function(w,b)
                local nextOn=not b.roomActive
                if onToggle(w,nextOn)~=false then
                    b.roomActive=nextOn
                    b:setTooltip(tipOn and getText(nextOn and tipOn or tipOff) or nil)
                end
            end)
            btn:setHeight(36)
            btn.roomLook="settingsToggle"
            btn.roomActive=not not active
            if tipOn then btn:setTooltip(getText(active and tipOn or tipOff)) end
            table.insert(self.widgets,btn)
            y=y+44
            return btn
        end

        y=self:addSettingsSection(tr("SettingsAudio"),y,width)
        local voiceVal=self.actions.getVoiceVolume and self.actions.getVoiceVolume(self.player) or 50
        self:addLabel(tr("VoiceVolume"),y,18,width-82)
        local voiceSlider=ISSliderPanel:new(5,y+25,width-10,20,self,function(w,value)
            if w.actions.setVoiceVolume then w.actions.setVoiceVolume(w.player,value,false) end
        end)
        voiceSlider:initialise();voiceSlider:instantiate();voiceSlider:setDoButtons(false)
        voiceSlider:setValues(0,100,1,10,true);voiceSlider:setCurrentValue(voiceVal,true)
        if styleSlider then styleSlider(voiceSlider) end
        attachSliderTooltip(voiceSlider,getText("ContextMenu_GFL2_VoiceVolumeTip"))
        local renderVoice=voiceSlider.render
        voiceSlider.render=function(s)
            renderVoice(s)
            local v=math.floor(s.currentValue+.5)
            local txt=v<=0 and tr("VoiceMuted") or tostring(v)
            local r,g,b=v<=0 and .98 or .86,v<=0 and .58 or .89,v<=0 and .22 or .92
            textInSlot(s,txt,s.width,-22,18,68,r,g,b,1,1)
        end
        voiceSlider.roomSlider=true;self.content:addChild(voiceSlider);table.insert(self.widgets,voiceSlider)
        y=y+58

        local function addSegmented(labelText,options,currentVal,onSelect)
            self:addLabel(labelText,y,18,width);y=y+24
            local count=#options
            local segGap=6
            local segW=math.floor((width-(count-1)*segGap)/count)
            for idx,opt in ipairs(options) do
                local sx=(idx-1)*(segW+segGap)
                local b=button(self.content,sx,y,segW,tr(opt[1]),self,function(w)
                    onSelect(w,opt[2])
                    w:refreshControls(true)
                end)
                b:setHeight(32)
                b.roomActive=(currentVal==opt[2])
                if opt[3] then b:setTooltip(getText(opt[3])) end
                table.insert(self.widgets,b)
            end
            y=y+42
        end

        local subOn=not self.actions.getShowSubtitles or self.actions.getShowSubtitles()
        addToggle(tr("VoiceSubtitles"),subOn,"ContextMenu_GFL2_SubtitlesOn","ContextMenu_GFL2_SubtitlesOff",function(w,nextOn)
            if w.actions.setShowSubtitles then w.actions.setShowSubtitles(nextOn) end
        end)
        local studioOn=not self.actions.getStudioPreview or self.actions.getStudioPreview()
        addToggle(tr("StudioPreview"),studioOn,"ContextMenu_GFL2_StudioPreviewOn","ContextMenu_GFL2_StudioPreviewOff",function(w,nextOn)
            if w.actions.setStudioPreview then w.actions.setStudioPreview(nextOn) end
        end)
        local studioScale=self.actions.getStudioScale and self.actions.getStudioScale() or 200
        addSegmented(tr("StudioResolution"),{
            {"StudioRes100",100,"ContextMenu_GFL2_StudioRes100Tip"},
            {"StudioRes150",150,"ContextMenu_GFL2_StudioRes150Tip"},
            {"StudioRes200",200,"ContextMenu_GFL2_StudioRes200Tip"},
        },studioScale,function(w,val)
            if w.actions.setStudioScale then w.actions.setStudioScale(val) end
        end)
        y=y+6

        y=self:addSettingsSection(tr("SettingsPresentation"),y,width)
        local idleOn=not self.actions.getIdleAmbient or self.actions.getIdleAmbient()
        addToggle(tr("IdleAmbient"),idleOn,"ContextMenu_GFL2_IdleAmbientOn","ContextMenu_GFL2_IdleAmbientOff",function(w,nextOn)
            if w.actions.setIdleAmbient then w.actions.setIdleAmbient(nextOn) end
        end)

        local facialMode=self.actions.getFacialMode and self.actions.getFacialMode() or 2
        addSegmented(tr("FacialMode"),{
            {"FacialOff",0,"ContextMenu_GFL2_FacialOffTip"},
            {"FacialLight",1,"ContextMenu_GFL2_FacialLightTip"},
            {"FacialFull",2,"ContextMenu_GFL2_FacialFullTip"},
        },facialMode,function(w,val)
            if w.actions.setFacialMode then w.actions.setFacialMode(val) end
        end)

        local hudOn=self.actions.getPortraitHud and self.actions.getPortraitHud() or false
        addToggle(tr("PortraitHud"),hudOn,"ContextMenu_GFL2_PortraitHudOn","ContextMenu_GFL2_PortraitHudOff",function(w,nextOn)
            if w.actions.setPortraitHud then w.actions.setPortraitHud(w.player,nextOn) end
        end)

        local fpsRaw=self.actions.getPortraitFps and self.actions.getPortraitFps() or 24
        local fpsSliderVal=(fpsRaw<=0) and 61 or clamp(fpsRaw,10,60)
        self:addLabel(tr("PortraitFps"),y,18,width-82)
        local fpsSlider=ISSliderPanel:new(5,y+25,width-10,20,self,function(w,value)
            local v=math.floor(value+.5)
            local target=(v>=61) and 0 or clamp(v,10,60)
            if w.actions.setPortraitFps then w.actions.setPortraitFps(w.player,target,false) end
        end)
        fpsSlider:initialise();fpsSlider:instantiate();fpsSlider:setDoButtons(false)
        fpsSlider:setValues(10,61,1,5,true);fpsSlider:setCurrentValue(fpsSliderVal,true)
        if styleSlider then styleSlider(fpsSlider) end
        attachSliderTooltip(fpsSlider,getText("ContextMenu_GFL2_PortraitFpsSliderTip"))
        local renderFps=fpsSlider.render
        fpsSlider.render=function(s)
            renderFps(s)
            local v=math.floor(s.currentValue+.5)
            local live=(v>=61)
            local txt=live and tr("PortraitFpsLive") or string.format("%d FPS",v)
            local r,g,b=live and .98 or .86,live and .62 or .89,live and .24 or .92
            textInSlot(s,txt,s.width,-22,18,76,r,g,b,1,1)
        end
        fpsSlider.roomSlider=true;self.content:addChild(fpsSlider);table.insert(self.widgets,fpsSlider)
        y=y+58
        y=y+6

        y=self:addSettingsSection(tr("SettingsPhysics"),y,width)
        if not editable then
            self:addLabel(self:toolMessage(),y,16,width);y=y+34
        else
            local hasChest=GFL2CharacterRuntime.hasChestPhysics and GFL2CharacterRuntime.hasChestPhysics(self.player)
            if hasChest then
                local chestVal=GFL2CharacterRuntime.chestStrength and GFL2CharacterRuntime.chestStrength(self.player) or 100
                self:addLabel(tr("ChestPhysics"),y,18,width-82)
                local chestSlider=ISSliderPanel:new(5,y+25,width-10,20,self,function(w,value)
                    if w.actions.chestStrength then w.actions.chestStrength(w.player,value,false) end
                end)
                chestSlider:initialise();chestSlider:instantiate();chestSlider:setDoButtons(false)
                chestSlider:setValues(0,100,1,10,true);chestSlider:setCurrentValue(chestVal,true)
                if styleSlider then styleSlider(chestSlider,"amber") end
                attachSliderTooltip(chestSlider,getText("ContextMenu_GFL2_ChestPhysicsTip"))
                local renderChest=chestSlider.render
                chestSlider.render=function(s)
                    renderChest(s)
                    local v=math.floor(s.currentValue+.5)
                    local txt=v<=0 and tr("ChestOff") or string.format("%d%%",v)
                    local r,g,b=v<=0 and .68 or .98,v<=0 and .72 or .58,v<=0 and .76 or .22
                    textInSlot(s,txt,s.width,-22,18,68,r,g,b,1,1)
                end
                chestSlider.roomSlider=true;self.content:addChild(chestSlider);table.insert(self.widgets,chestSlider)
                y=y+56
            else
                self:addLabel(tr("ChestPhysics"),y,18,width);y=y+24
                self:addLabel(tr("RoomChestUnavailable"),y,15,width);y=y+26
            end

            local physicsOn=GFL2CharacterRuntime.modelPhysicsEnabled and GFL2CharacterRuntime.modelPhysicsEnabled(self.player) or false
            addToggle(tr("ClothHairPhysics"),physicsOn,"ContextMenu_GFL2_ClothHairPhysicsOn","ContextMenu_GFL2_ClothHairPhysicsOff",function(w,nextOn)
                return w.actions.bodyPhysics(w.player,nextOn)
            end)
        end

        if self.multiplayerSession then
            y=y+6
            y=self:addSettingsSection(tr("SettingsMultiplayer"),y,width)
            local remoteModelsOn=not self.actions.getShowRemoteModels or self.actions.getShowRemoteModels()
            addToggle(tr("RemoteModels"),remoteModelsOn,"ContextMenu_GFL2_RemoteModelsOn","ContextMenu_GFL2_RemoteModelsOff",function(w,nextOn)
                if w.actions.setShowRemoteModels then w.actions.setShowRemoteModels(nextOn) end
                w:refreshControls(true)
            end)
            if remoteModelsOn then
                local rChestVal=self.actions.getRemoteChestStrength and self.actions.getRemoteChestStrength() or 0
                self:addLabel(tr("RemoteChestPhysics"),y,18,width-82)
                local rChestSlider=ISSliderPanel:new(5,y+25,width-10,20,self,function(w,value)
                    if w.actions.setRemoteChestStrength then w.actions.setRemoteChestStrength(w.player,value,false) end
                end)
                rChestSlider:initialise();rChestSlider:instantiate();rChestSlider:setDoButtons(false)
                rChestSlider:setValues(0,100,1,10,true);rChestSlider:setCurrentValue(rChestVal,true)
                if styleSlider then styleSlider(rChestSlider,"amber") end
                attachSliderTooltip(rChestSlider,getText("ContextMenu_GFL2_RemoteChestPhysicsTip"))
                local renderRChest=rChestSlider.render
                rChestSlider.render=function(s)
                    renderRChest(s)
                    local v=math.floor(s.currentValue+.5)
                    local txt=v<=0 and tr("ChestOff") or string.format("%d%%",v)
                    local r,g,b=v<=0 and .68 or .98,v<=0 and .72 or .58,v<=0 and .76 or .22
                    textInSlot(s,txt,s.width,-22,18,68,r,g,b,1,1)
                end
                rChestSlider.roomSlider=true;self.content:addChild(rChestSlider);table.insert(self.widgets,rChestSlider)
                y=y+56

                local rPhysOn=self.actions.getRemoteBodyPhysics and self.actions.getRemoteBodyPhysics()
                addToggle(tr("RemoteClothHairPhysics"),rPhysOn,"ContextMenu_GFL2_RemoteClothHairOn","ContextMenu_GFL2_RemoteClothHairOff",function(w,nextOn)
                    if w.actions.setRemoteBodyPhysics then w.actions.setRemoteBodyPhysics(nextOn) end
                end)
            end
            local rVoiceOn=not self.actions.getRemoteVoiceEnabled or self.actions.getRemoteVoiceEnabled()
            addToggle(tr("RemoteVoice"),rVoiceOn,"ContextMenu_GFL2_RemoteVoiceOn","ContextMenu_GFL2_RemoteVoiceOff",function(w,nextOn)
                if w.actions.setRemoteVoiceEnabled then w.actions.setRemoteVoiceEnabled(nextOn) end
                w:refreshControls(true)
            end)
            if rVoiceOn then
                local rSubOn=not self.actions.getRemoteSubtitlesEnabled or self.actions.getRemoteSubtitlesEnabled()
                addToggle(tr("RemoteSubtitles"),rSubOn,"ContextMenu_GFL2_RemoteSubtitlesOn","ContextMenu_GFL2_RemoteSubtitlesOff",function(w,nextOn)
                    if w.actions.setRemoteSubtitlesEnabled then w.actions.setRemoteSubtitlesEnabled(nextOn) end
                end)
            end
            local hint=ISPanel:new(0,y,width,34);hint:initialise();hint.background=false
            hint.prerender=function(s)label(s,tr("SettingsSandboxHint"),4,2,14,s.width-8,.58,.68,.72)end
            self.content:addChild(hint);table.insert(self.widgets,hint)
            y=y+36
        end

        y=y+4
        local resetBtn=button(self.content,0,y,width,tr("SettingsReset"),self,function(w)
            if w.actions.setVoiceVolume then w.actions.setVoiceVolume(w.player,50,true) end
            if w.actions.setShowSubtitles then w.actions.setShowSubtitles(true) end
            if w.actions.setStudioPreview then w.actions.setStudioPreview(true) end
            if w.actions.setStudioScale then w.actions.setStudioScale(200) end
            if w.actions.setIdleAmbient then w.actions.setIdleAmbient(true) end
            if w.actions.setFacialMode then w.actions.setFacialMode(2) end
            if w.actions.resetPortraitHud then w.actions.resetPortraitHud(w.player) end
            if w.actions.setPortraitHud then w.actions.setPortraitHud(w.player,false) end
            if w:canEditCurrent() then
                if GFL2CharacterRuntime.hasChestPhysics and GFL2CharacterRuntime.hasChestPhysics(w.player) and w.actions.chestStrength then
                    w.actions.chestStrength(w.player,100,true)
                end
                if not GFL2CharacterRuntime.modelPhysicsEnabled(w.player) then
                    w.actions.bodyPhysics(w.player,true)
                end
            end
            if w.multiplayerSession then
                if w.actions.setShowRemoteModels then w.actions.setShowRemoteModels(true) end
                if w.actions.setRemoteBodyPhysics then w.actions.setRemoteBodyPhysics(false) end
                if w.actions.setRemoteChestStrength then w.actions.setRemoteChestStrength(w.player,0,true) end
                if w.actions.setRemoteVoiceEnabled then w.actions.setRemoteVoiceEnabled(true) end
                if w.actions.setRemoteSubtitlesEnabled then w.actions.setRemoteSubtitlesEnabled(true) end
            end
            w:refreshControls(true)
        end)
        resetBtn:setHeight(34);resetBtn.roomLook="quiet"
        table.insert(self.widgets,resetBtn)
        return y+42
    end

    function W:drawExpressionPresets(rows,y,width)
        local data=getData()
        local labels={}
        for _,row in ipairs(rows)do labels[row.label]=row.index end
        local eyes=self.selected.meta.groups and self.selected.meta.groups.eyes
        local group
        if eyes then
            for i=0,GFL2CharacterRuntime.groupCount(self.player)-1 do
                if GFL2CharacterRuntime.groupLabel(self.player,i)==eyes.label then group=i;break end
            end
        end
        local blinkLabels=data.roomBlinkLabels or {}
        local blink=labels[blinkLabels[1]] or labels[blinkLabels[2]]
        if group~=nil or blink~=nil then
            local half=math.floor((width-8)/2)
            for i,key in ipairs({"EyeOpen","EyeClosed"})do
                local option=i-1
                local b=button(self.content,option*(half+8),y,half,tr(key),self,function(w)
                    if not w:canEditCurrent() then return end
                    if group~=nil then
                        if not w.actions.choice(w.player,group,option)then return end
                    else
                        if GFL2ReadyRoom.expressionSet(w.player,w.currentId,blink,option)~="" then return end
                        w.actions.expressionRemember(w.player)
                    end
                    w:refreshControls(true)
                end)
                b.roomActive=group~=nil and GFL2CharacterRuntime.selectedOption(self.player,group)==option
                table.insert(self.widgets,b)
            end
            y=y+36
        end
        local presets=data.roomFacialPresets or {}
        if #rows==0 or #presets==0 then return y end
        self:addLabel(tr("RoomFacePresets"),y,19);y=y+29
        local cell=math.floor((width-(#presets-1)*6)/#presets)
        for i,preset in ipairs(presets)do
            local payload=expressionPreset(preset,labels)
            if payload then
                local b=button(self.content,(i-1)*(cell+6),y,cell,tr(preset[1]),self,function(w)
                    if not w:canEditCurrent() then return end
                    local error=GFL2ReadyRoom.expressionLoad(w.player,w.currentId,payload)
                    if error~="" then print("[GFL2Exilium] "..error);return end
                    w.actions.expressionRemember(w.player)
                    w:refreshControls(true)
                end)
                table.insert(self.widgets,b)
            end
        end
        return y+38
    end

    local function buildExpressionTab(w,y,width)
        local id=w.currentId
        local rows=GFL2ReadyRoom.expressionRows(w.player,id)
        y=w:drawExpressionPresets(rows,y,width)
        local showExpressionSlots=#rows>0
        if showExpressionSlots then
            w.content:setHeight(w.fullHeight-116-w.content.y-6)
            if GFL2ReadyRoom.motionState(w.player).name~="" then
                w:addLabel(tr("ExpressionVmd"),y);y=y+28
            end
            local group
            for _,row in ipairs(rows)do
                if group~=row.group then group=row.group;w:addLabel(tr(group),y);y=y+31 end
                local field=row.index
                w:addLabel(localizedLabel("ExpressionControl",row.label),y,18,width-60)
                local slider=ISSliderPanel:new(5,y+24,width-10,20,w,function(win,value)
                    local error=GFL2ReadyRoom.expressionSet(win.player,id,field,value/100)
                    if error~="" then print("[GFL2Exilium] "..error);win.status=tr("Error")
                    else win.actions.expressionEdited(win.player) end
                end)
                slider:initialise();slider:instantiate();slider:setDoButtons(false);slider:setValues(0,100,1,10,true)
                slider:setCurrentValue(row.value*100,true)
                if styleSlider then styleSlider(slider) end
                local render=slider.render
                slider.render=function(s)
                    render(s)
                    local v=math.floor(s.currentValue+.5)
                    textInSlot(s,string.format("%d%%",v),s.width,-21,18,50,v>0 and .94 or .72,v>0 and .96 or .80,v>0 and .96 or .84,1,1)
                end
                slider.roomSlider=true;w.content:addChild(slider);table.insert(w.widgets,slider);y=y+49
            end
        else
            w:addLabel(tr("ExpressionEmpty"),y);y=y+30
        end
        return y,showExpressionSlots
    end

    local function buildGripTab(w,y,width)
        local id=w.currentId
        local family=GFL2ReadyRoom.gripFamily(w.player)
        w.gripFamily=family
        if family=="" then
            w:addLabel(getText("ContextMenu_GFL2_GripEquipItem"),y);return y+32
        end
        local canAim=GFL2ReadyRoom.canAimGrip(w.player)
        if not canAim then w.gripAiming=false end
        local aiming=w.gripAiming==true
        if canAim then
            local mode=aiming and "ContextMenu_GFL2_GripStanding" or "ContextMenu_GFL2_GripAim"
            w.gripAimButton.roomTitle=getText(mode)
            w.gripAimButton.roomActive=aiming
            w.gripAimButton:setVisible(true)
        end
        w:addLabel(GFL2ReadyRoom.gripItemName(w.player),y);y=y+32
        local values=GFL2ReadyRoom.gripValues(w.player,family,aiming)
        local groups={
            {getText("ContextMenu_GFL2_GripItemPosition"),12,20,{"X","Y","Z"},""},
            {tr("GripSupport"),0,12,{"X","Y","Z"},""},
            {tr("GripSupportWrist"),6,90,{"X","Y","Z"},"°"},
            {tr("GripPrimary"),3,12,{"X","Y","Z"},""},
            {tr("GripPrimaryWrist"),9,90,{"X","Y","Z"},"°"},
            {getText("ContextMenu_GFL2_GripCurl"),15,100,
                {getText("ContextMenu_GFL2_GripLeftHand"),getText("ContextMenu_GFL2_GripRightHand")},"%"},
        }
        local resetWidth=64
        for _,group in ipairs(groups)do
            local title,start,limit,axes,unit=unpack(group)
            w:addLabel(title,y);y=y+28
            local inset=unit=="%" and 76 or 34
            for i,axis in ipairs(axes)do
                local field=start+i-1
                w:addLabel(axis,y,17)
                local slider=ISSliderPanel:new(inset,y+2,width-inset-resetWidth-8,20,w,function(win,value)
                    if not GFL2ReadyRoom.gripSet(win.player,id,family,field,value,aiming)then win.status=tr("Error") end
                end)
                slider:initialise();slider:instantiate();slider:setDoButtons(true);slider:setValues(-limit,limit,.1,1,true)
                slider:setCurrentValue(values[field+1] or 0,true)
                if styleSlider then styleSlider(slider,aiming and "amber" or nil) end
                local render=slider.render
                slider.render=function(s)
                    render(s)
                    local nz=math.abs(s.currentValue)>=.05
                    textInSlot(s,string.format("%+.1f",s.currentValue)..unit,s.width,20,18,76,
                        nz and (aiming and .98 or .88) or .66,nz and (aiming and .58 or .90) or .70,nz and (aiming and .22 or .92) or .74,1,1)
                end
                slider.roomSlider=true;w.content:addChild(slider);table.insert(w.widgets,slider)
                local reset=button(w.content,width-resetWidth,y,resetWidth,getText("ContextMenu_GFL2_GripResetValue"),w,function(win)
                    if GFL2ReadyRoom.gripSet(win.player,id,family,field,0,aiming) then
                        slider:setCurrentValue(0,true)
                    else win.status=tr("Error") end
                end)
                table.insert(w.widgets,reset);y=y+46
            end
        end
        local b=button(w.content,0,y+4,width,tr("Reset"),w,function(win)
            if GFL2ReadyRoom.gripSet(win.player,id,family,-1,0,aiming)then win:refreshControls(true) end
        end)
        table.insert(w.widgets,b)
        return y+42
    end

    local function buildPartsTab(w,y,width)
        y=w:drawGroups(w:partRows(false),y,width)
        y=w:drawGroups(w:partRows(true),y,width)
        for row=0,GFL2CharacterRuntime.materialCount(w.player)-1 do
            local index=GFL2CharacterRuntime.materialIndex(w.player,row)
            local available=GFL2CharacterRuntime.materialAvailable(w.player,index)
            local b=button(w.content,0,y,width,GFL2CharacterRuntime.materialLabel(w.player,index),w,function(s)
                if s.actions.material(s.player,index,not GFL2CharacterRuntime.materialVisible(s.player,index))then s:refreshControls(true)end
            end)
            b:setEnable(available);b.roomActive=GFL2CharacterRuntime.materialVisible(w.player,index)
            table.insert(w.widgets,b);y=y+34
        end
        if GFL2CharacterRuntime.presetCount(w.player)>0 then
            w:addLabel(tr("Presets"),y);y=y+30
            for index=0,GFL2CharacterRuntime.presetCount(w.player)-1 do
                local b=button(w.content,0,y,width,GFL2CharacterRuntime.presetLabel(w.player,index),w,function(s)
                    if s.actions.preset(s.player,index)then s:refreshControls(true)end
                end)
                table.insert(w.widgets,b);y=y+34
            end
        end
        local half=math.floor((width-8)/2)
        local reset=button(w.content,0,y,half,tr("Reset"),w,w.onReset)
        local reload=button(w.content,half+8,y,half,tr("Reload"),w,w.onReload)
        table.insert(w.widgets,reset);table.insert(w.widgets,reload)
        return y+38
    end

    function W:refreshControls(keepScroll)
        self:endTabMotion()
        if self.folded or self.foldStart then return end
        if self.roomPage=="motions" then self:refreshMotionControls();return end
        local editable=self:canEditCurrent()
        self:layoutPreview()
        self.gripAimButton:setVisible(false)
        self.preview:setVisible(self.tab~="weaponDraw")
        local scroll=keepScroll and self.content:getYScroll() or 0
        self:clearContent()
        local enabled=GFL2CharacterRuntime.isEnabled(self.player)
        self.restoreButton:setEnable(enabled)
        self.content:setX(self.tab=="skins" and self.overviewX-57 or self.rightX)
        self.content:setWidth(self.tab=="skins" and self.overviewWidth+64 or self.rightWidth)
        self.content:setY(self.tab=="skins" and math.floor(self.height*.19) or 91)
        self.content:setHeight(self.tab=="skins" and math.floor(self.height*.57) or self.height-self.content.y-(self.tab=="settings" and 24 or 112))
        local y=8;local width=self.rightWidth-32;local showExpressionSlots=false
        if self.tab=="overview" or self.tab=="outfitDraw" or self.tab=="weaponDraw" then
            y=0
        elseif self.tab=="skins" then
            local character=self.byCharacter[self.character]
            if character and not character.locked then self:carousel(character) end
            y=self.content.height
        elseif self.tab=="settings" then
            y=self:buildSettingsTab(y,width,editable)
        elseif not editable then
            self:addLabel(self:toolMessage(),8);y=44
        elseif self.tab=="expression" then
            y,showExpressionSlots=buildExpressionTab(self,y,width)
        elseif self.tab=="grip" then
            y=buildGripTab(self,y,width)
        elseif self.tab=="parts" then
            y=buildPartsTab(self,y,width)
        end
        if self.tab=="grip" or self.tab=="expression" or self.tab=="parts" or self.tab=="settings" then
            for _,widget in ipairs(self.widgets) do widget:setX(widget.x+12) end
            y=y+8
            self.content:setHeight(math.min(self.content.height,y))
        end
        if showExpressionSlots then self.expressionFooter:setY(self.content.y+self.content.height+6) end
        self:refreshExpressionSlots(showExpressionSlots)
        self:updateWear()
        self.content:setScrollHeight(math.max(y,self.content.height))
        self.content:setYScroll(clamp(scroll,self.content.height-self.content:getScrollHeight(),0))
        self.status=self.waiting and tr("Loading") or enabled and tr("Saved") or tr("Original")
        self:layoutOverview()
        self.content:setVisible(self.tab~="overview" and self.tab~="outfitDraw" and (self.tab~="weaponDraw" or self.signatureWeapon~=nil))
    end
end

return M
