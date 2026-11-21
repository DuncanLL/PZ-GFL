local M={}

function M.install(W,ctx)
    local button=ctx.button
    local clamp=ctx.clamp
    local descriptionHeight=ctx.descriptionHeight
    local getData=ctx.data
    local hideTooltip=ctx.hideTooltip
    local label=ctx.label
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
            s:drawRect(0,6,3,13,.90,.53,.79,.76)
            label(s,title,9,2,18,s.width-12,.88,.94,.93)
            s:drawRect(0,24,s.width,1,.24,.58,.74,.74)
        end
        self.content:addChild(p);table.insert(self.widgets,p)
        return y+34
    end

    function W:buildSettingsTab(y,width,editable)
        y=self:addSettingsSection(tr("SettingsAudio"),y,width)
        local voiceVal=self.actions.getVoiceVolume and self.actions.getVoiceVolume(self.player) or 50
        self:addLabel(tr("VoiceVolume"),y,18,width-82)
        local voiceSlider=ISSliderPanel:new(5,y+25,width-10,20,self,function(w,value)
            if w.actions.setVoiceVolume then w.actions.setVoiceVolume(w.player,value,false) end
        end)
        voiceSlider:initialise();voiceSlider:instantiate();voiceSlider:setDoButtons(false)
        voiceSlider:setValues(0,100,1,10,true);voiceSlider:setCurrentValue(voiceVal,true)
        attachSliderTooltip(voiceSlider,getText("ContextMenu_GFL2_VoiceVolumeTip"))
        local renderVoice=voiceSlider.render
        voiceSlider.render=function(s)
            renderVoice(s)
            local v=math.floor(s.currentValue+.5)
            local txt=v<=0 and tr("VoiceMuted") or tostring(v)
            local r,g,b=v<=0 and .96 or .81,v<=0 and .74 or .90,v<=0 and .44 or .92
            textInSlot(s,txt,s.width,-22,18,68,r,g,b,1,1)
        end
        voiceSlider.roomSlider=true;self.content:addChild(voiceSlider);table.insert(self.widgets,voiceSlider)
        y=y+60

        y=self:addSettingsSection(tr("SettingsPhysics"),y,width)
        if not editable then
            self:addLabel(self:toolMessage(),y,17,width);y=y+36
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
                attachSliderTooltip(chestSlider,getText("ContextMenu_GFL2_ChestPhysicsTip"))
                local renderChest=chestSlider.render
                chestSlider.render=function(s)
                    renderChest(s)
                    local v=math.floor(s.currentValue+.5)
                    local txt=v<=0 and tr("ChestOff") or string.format("%d%%",v)
                    local r,g,b=v<=0 and .96 or .81,v<=0 and .74 or .90,v<=0 and .44 or .92
                    textInSlot(s,txt,s.width,-22,18,68,r,g,b,1,1)
                end
                chestSlider.roomSlider=true;self.content:addChild(chestSlider);table.insert(self.widgets,chestSlider)
                y=y+58
            else
                self:addLabel(tr("ChestPhysics"),y,18,width);y=y+26
                self:addLabel(tr("RoomChestUnavailable"),y,15,width);y=y+28
            end

            local physicsOn=GFL2CharacterRuntime.modelPhysicsEnabled(self.player)
            local clothBtn=button(self.content,0,y,width,tr("ClothHairPhysics"),self,function(w,btn)
                local nextOn=not GFL2CharacterRuntime.modelPhysicsEnabled(w.player)
                if w.actions.bodyPhysics(w.player,nextOn) then
                    btn.roomActive=nextOn
                    btn:setTooltip(getText(nextOn and "ContextMenu_GFL2_ClothHairPhysicsOn" or "ContextMenu_GFL2_ClothHairPhysicsOff"))
                end
            end)
            clothBtn:setHeight(38)
            clothBtn.roomLook="settingsToggle"
            clothBtn.roomActive=not not physicsOn
            clothBtn:setTooltip(getText(physicsOn and "ContextMenu_GFL2_ClothHairPhysicsOn" or "ContextMenu_GFL2_ClothHairPhysicsOff"))
            table.insert(self.widgets,clothBtn)
            y=y+54
        end

        local resetBtn=button(self.content,0,y,width,tr("SettingsReset"),self,function(w)
            if w.actions.setVoiceVolume then w.actions.setVoiceVolume(w.player,50,true) end
            if w:canEditCurrent() then
                if GFL2CharacterRuntime.hasChestPhysics and GFL2CharacterRuntime.hasChestPhysics(w.player) and w.actions.chestStrength then
                    w.actions.chestStrength(w.player,100,true)
                end
                if not GFL2CharacterRuntime.modelPhysicsEnabled(w.player) then
                    w.actions.bodyPhysics(w.player,true)
                end
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
                local render=slider.render
                slider.render=function(s)
                    render(s)
                    textInSlot(s,string.format("%d%%",math.floor(s.currentValue+.5)),s.width,-21,18,50,.81,.88,.92,1,1)
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
                local render=slider.render
                slider.render=function(s)
                    render(s)
                    textInSlot(s,string.format("%+.1f",s.currentValue)..unit,s.width,20,18,76,.81,.88,.92,1,1)
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
        self.content:setHeight(self.tab=="skins" and math.floor(self.height*.57) or self.height-self.content.y-112)
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
