local M={}

function M.install(W,ctx)
    local art=ctx.art
    local button=ctx.button
    local clamp=ctx.clamp
    local drawCornerTicks=ctx.drawCornerTicks
    local drawShadow=ctx.drawShadow
    local hoverAmount=ctx.hoverAmount
    local image=ctx.image
    local label=ctx.label
    local modalProgress=ctx.modalProgress
    local roomSound=ctx.roomSound
    local surface=ctx.surface
    local textInSlot=ctx.textInSlot
    local tr=ctx.tr

    local function drawStarIcon(p,cx,cy,size,filled,alpha)
        local glyph=image(art..(filled and "star-on.png" or "star-off.png"))
        if glyph then
            if filled then p:drawTextureScaled(glyph,cx-size/2,cy-size/2,size,size,alpha,1,1,1)
            else p:drawTextureScaled(glyph,cx-size/2,cy-size/2,size,size,alpha,.66,.78,.80) end
            return
        end
        local r=math.floor(size/2)
        if filled then p:drawRect(cx-r+2,cy-r+2,size-4,size-4,alpha,1,.78,.32)
        else p:drawRectBorder(cx-r+2,cy-r+2,size-4,size-4,alpha,.66,.78,.80) end
    end

    local function isLocalMotion(file)
        return file~=nil and file:find("[/\\]")~=nil
    end

    local function motionDisplayName(file)
        if file:sub(1,7)=="sample:" then return file:sub(8) end
        return file:match("([^/\\]+)$") or file
    end

    local function motionTrack(slider,x,y,width,seek)
        local over=not slider.disabled and slider:isMouseOver() and (not seek or slider:getMouseY()>=13)
        local hover=hoverAmount(slider,over or (not slider.disabled and (slider.dragInside or slider.joypadFocused)))
        local h=slider.dragInside and not slider.disabled and 4 or 3
        slider:drawRect(x,y,width,h,.92,.15+.07*hover,.21+.08*hover,.24+.08*hover)
        if slider.disabled then return end
        local fill=width*clamp(slider.currentValue,0,1)
        if fill>0 then slider:drawRect(x,y,fill,h,1,seek and .94 or .53,seek and .71 or .77,seek and .38 or .73) end
        if seek and over and not slider.dragInside then
            local tick=clamp(slider:getMouseX(),x,x+width-1)
            slider:drawRect(tick,y-3,1,h+6,.76*hover,.85,.90,.88)
        end
        local knob=image(art.."knob.png")
        local size=slider.dragInside and 12 or 8+2*hover
        if knob then slider:drawTextureScaled(knob,x+fill-size/2,y+(h-size)/2,size,size,1,
            seek and 1 or .76,seek and .80 or .92,seek and .52 or .87) end
    end

    local function clockText(frame)
        local seconds=math.floor(frame/30)
        return string.format("%02d:%02d",math.floor(seconds/60),seconds%60)
    end

    function W:createMotionPage()
        self.motionPanelWidth=math.min(586,self.fullWidth)
        self.motionPanelHeight=math.min(530,self.fullHeight)
        self.motionLibTab="motion"
        local height=self.motionPanelHeight-64
        local listWidth=math.floor((self.motionPanelWidth-32)*.44)
        local library=ISPanel:new(12,54,listWidth,height)
        library:initialise();library.background=false;self:addChild(library);self.motionLibraryBox=library
        library.prerender=function(p)
            local w=p.parent
            local isCam=w.motionLibTab=="camera"
            drawShadow(p,0,0,p.width,p.height,.92)
            surface(p,0,0,p.width,p.height,.94)
            drawCornerTicks(p,0,0,p.width,p.height,.90,6)
            p:drawRect(0,0,p.width,2,.92,.95,.54,.16)
            label(p,tr(isCam and "MotionCameraLibrary" or "MotionLibrary"),12,10,20,p.width-24,.96,.97,.97)
            if #w.motionList.items==0 then
                label(p,tr(isCam and "MotionNoCameras" or "MotionNoFiles"),12,80,17,p.width-24,.68,.76,.78)
            end
        end
        local tabW=math.floor((library.width-22)/2)
        self.motionTabBtn=button(library,8,36,tabW,tr("MotionTabAction"),self,function(w)w:setMotionLibTab("motion")end)
        self.motionTabBtn:setHeight(26);self.motionTabBtn.roomActive=true
        self.cameraTabBtn=button(library,14+tabW,36,library.width-22-tabW,tr("MotionTabCamera"),self,function(w)w:setMotionLibTab("camera")end)
        self.cameraTabBtn:setHeight(26);self.cameraTabBtn.roomActive=false

        local list=ISScrollingListBox:new(8,68,library.width-16,height-148)
        list:initialise();list.itemheight=42
        list.backgroundColor={r=.035,g=.055,b=.065,a=0}
        list.doDrawItem=function(s,y,row,alt)
            local scroll=s:getYScroll()
            if y+row.height+scroll<=0 or y+scroll>=s.height then return y+row.height end
            local overRow=s.mouseoverselected==row.index and s:isMouseOver() and not s:isMouseOverScrollBar()
            local hover=hoverAmount(row,overRow)
            if row.index==s.selected then
                s:drawRect(0,y,s.width-14,row.height-2,.93,.15+.04*hover,.19+.05*hover,.20+.05*hover)
                s:drawRect(0,y+4,2,row.height-10,1,1,.71,.38)
            else
                if alt then s:drawRect(0,y,s.width-14,row.height-2,.42,.055,.085,.105) end
                if hover>0 then s:drawRect(0,y,s.width-14,row.height-2,.76*hover,.18,.28,.30) end
            end
            local overStar=overRow and s:getMouseX()<=30
            local starAlpha=row.favorite and 1 or (overStar and .95 or (.40+.35*hover))
            drawStarIcon(s,15,y+math.floor((row.height-2)/2),overStar and 16 or 14,row.favorite==true,starAlpha)
            label(s,row.text,31,y+5,17,s.width-48)
            label(s,row.source,31,y+24,13,s.width-48,.58,.70,.72)
            return y+row.height
        end
        local motionListDown=list.onMouseDown
        list.onMouseDown=function(s,x,y)
            if not s:isMouseOverScrollBar() and x>=0 and x<=30 then
                local rowIdx=s:rowAt(x,y)
                local item=rowIdx and s.items[rowIdx]
                if item and item.item then
                    self:toggleMotionFavorite(item.item)
                    return true
                end
            end
            return motionListDown(s,x,y)
        end
        list:setOnMouseDownFunction(self,function(w,filename)
            if w.motionLibTab=="camera" then w.cameraSelected=filename
            else w.motionSelected=filename end
            w.motionMessage=nil;w:refreshMotionControls()
        end)
        library:addChild(list);self.motionList=list
        local width=library.width-16
        local half=math.floor((width-6)/2)
        self.motionAddPath=button(library,8,height-70,width,tr("MotionAddPath"),self,self.onAddMotionPath)
        self.motionRefresh=button(library,8,height-34,half,tr("MotionRefresh"),self,self.refreshMotionLibrary)
        self.motionRemovePath=button(library,14+half,height-34,width-half-6,tr("MotionRemovePath"),self,self.onRemoveMotionPath)

        local box=ISPanel:new(20+listWidth,54,self.motionPanelWidth-32-listWidth,height)
        box:initialise();box.background=false;self:addChild(box);self.motionBox=box
        box.prerender=function(p)
            local w=p.parent
            local textWidth=p.width-24
            drawShadow(p,0,0,p.width,p.height,.92)
            surface(p,0,0,p.width,p.height,.94)
            drawCornerTicks(p,0,0,p.width,p.height,.90,6)
            p:drawRect(0,0,p.width,2,.92,.95,.54,.16)
            label(p,tr("MotionControls"),12,12,22,textWidth,.96,.97,.97)
            local state=w.motionPlayback
            if state and state.name~="" then
                local frame=state.frame or 0
                for i=0,2 do
                    local h=4+math.floor(5*(.5+.5*math.sin(frame*.24+i*1.3)))
                    p:drawRect(12+i*4,56-h,2,h,1,.96,.58,.18)
                end
            end
            label(p,w.motionStatus or tr("MotionStopped"),28,40,17,textWidth-16,.76,.88,.90)
            if w.motionPlayingLabel then label(p,w.motionPlayingLabel,12,60,17,textWidth,.95,.96,.96) end
            label(p,tr("MotionSpeed"),12,118,15,textWidth-66,.70,.78,.80)
            textInSlot(p,string.format("%.2fx",w.motionSpeed.currentValue),p.width-12,118,15,60,.88,.92,.94,1,1)
            p:drawRect(12,162,textWidth,1,.16,.80,.88,.90)
            label(p,tr("MotionSelected"),12,171,14,textWidth,.70,.78,.80)
            label(p,w.motionSelectedLabel or tr("MotionNoSelection"),12,189,16,textWidth,.94,.96,.96)
            p:drawRect(12,282,textWidth,1,.16,.80,.88,.90)
            label(p,tr("MotionPhysics"),12,290,15,textWidth,.70,.78,.80)
            p:drawRect(12,350,textWidth,1,.16,.80,.88,.90)
            label(p,tr("MotionCamera"),12,358,15,textWidth,.70,.78,.80)
            label(p,w.cameraStatusLabel or tr("MotionCameraNone"),12,376,15,textWidth,
                w.cameraLoadedActive and .95 or .72,w.cameraLoadedActive and .96 or .80,w.cameraLoadedActive and .96 or .82)
            if w.motionMessage then
                label(p,w.motionMessage,12,436,16,textWidth,1,.74,.42)
            end
        end
        width=box.width-24
        local third=math.floor((width-12)/3)
        half=math.floor((width-6)/2)
        local function control(x,y,w,key,command)
            return button(box,x,y,w,tr(key),self,
                function(w)w:onMotionCommand(command)end)
        end
        self.motionButtons={
            play=control(12,211,width,"MotionPlay","play"),
            pause=control(12,245,third,"MotionPause","pause"),
            restart=control(18+third,245,third,"MotionRestart","restart"),
            stop=control(24+2*third,245,width-2*third-12,"MotionStop","stop"),
            physics=control(12,312,half,"MotionPhysicsOn","physics"),
            reset=control(18+half,312,width-half-6,"MotionPhysicsReset","reset"),
            cameraLoad=button(box,12,398,third,tr("MotionCameraLoad"),self,function(w)w:onMotionCommand("camera",w.cameraSelected or "")end),
            cameraToggle=control(18+third,398,third,"MotionCameraOn","cameraToggle"),
            cameraClear=control(24+2*third,398,width-2*third-12,"MotionCameraClear","cameraClear"),
        }
        self.motionButtons.play.roomLook="primary"
        for _,key in ipairs({"play","pause","restart","stop"})do self.motionButtons[key].roomIcon=key end
        self:createMotionTimeline(box,width)
        local speed=ISSliderPanel:new(12,135,width,21,self,function(w,value)
            if math.abs(value-(w.motionPlayback.speed or 1))<.0001 then return end
            w:onMotionCommand("speed",tostring(value))
        end)
        speed:initialise();speed:instantiate()
        speed:setDoButtons(false);speed:setValues(0,1,.01,.1,true)
        speed.sliderBarDim.x=6;speed.sliderBarDim.w=width-12
        speed.currentValue=1;speed.disabled=true
        local speedDown=speed.onMouseDown
        speed.onMouseDown=function(s,x,y)
            if not s.disabled then return speedDown(s,x,y) end
        end
        speed.render=function(s)motionTrack(s,s.sliderBarDim.x,math.floor(s.height/2)-1,s.sliderBarDim.w,false)end
        box:addChild(speed);self.motionSpeed=speed
    end

    function W:createMotionTimeline(box,width)
        local slider=ISSliderPanel:new(12,81,width,34,self)
        slider:initialise();slider:instantiate()
        slider:setDoButtons(false);slider:setValues(0,1,.00001,.01,true)
        slider.currentValue=0;slider.disabled=true
        slider:paginate()
        slider.sliderBarDim.x=6;slider.sliderBarDim.w=width-12
        local mouseDown=slider.onMouseDown
        slider.cancel=function(s)
            if not s.dragInside then return end
            s.dragInside=false;s.seekRevision=nil;s:setCapture(false)
        end
        slider.onMouseDown=function(s,x,y)
            if s.disabled or y<13 then return false end
            mouseDown(s,x,y)
            if not s.dragInside then return false end
            s.seekRevision=s.target.motionPlayback.revision
            s:setCapture(true)
            return true
        end
        slider.onMouseUp=function(s,x)
            if not s.dragInside then return false end
            local revision=s.seekRevision
            local fraction=clamp((x-s.sliderBarDim.x)/s.sliderBarDim.w,0,1)
            s:cancel()
            local w=s.target
            local error=GFL2ReadyRoom.motionCommand(w.player,"seek",revision..":"..tostring(fraction))
            w.motionMessage=error~="" and error or nil;w:refreshMotionControls()
            return true
        end
        slider.onMouseUpOutside=slider.onMouseUp
        slider.render=function(s)
            local state=s.target.motionPlayback
            local duration=state and state.endFrame or 0
            local frame=s.dragInside and duration*s.currentValue or state and state.frame or 0
            motionTrack(s,s.sliderBarDim.x,25,s.sliderBarDim.w,true)
            local current=math.floor(frame/30)
            local total=math.floor(duration/30)
            if current~=s.lastSecond then s.lastSecond=current;s.clock=clockText(frame) end
            if total~=s.lastDuration then s.lastDuration=total;s.total=clockText(duration) end
            textInSlot(s,s.disabled and "--:--" or s.clock,0,0,18,s.width/2,.82,.88,.90,1)
            textInSlot(s,s.disabled and "--:--" or s.total,s.width,0,18,s.width/2,.58,.67,.71,1,1)
        end
        box:addChild(slider);self.motionSeek=slider
    end

    function W:loadMotionFavorites()
        local favs={}
        if GFL2ReadyRoom and GFL2ReadyRoom.motionFavorites then
            local saved=GFL2ReadyRoom.motionFavorites()
            for i=1,#saved do favs[saved[i]]=true end
        else
            local stored=self.player and self.player:getModData().GFL2MotionFavorites
            if type(stored)=="table" then
                for k,v in pairs(stored) do if v then favs[k]=true end end
            end
        end
        self.motionFavorites=favs
        return favs
    end

    function W:toggleMotionFavorite(file)
        if not file or file=="" then return end
        local favs=self.motionFavorites or self:loadMotionFavorites()
        local nextState=not favs[file]
        favs[file]=nextState or nil
        if GFL2ReadyRoom and GFL2ReadyRoom.setMotionFavorite then
            GFL2ReadyRoom.setMotionFavorite(file,nextState)
        end
        if self.player then
            local md=self.player:getModData()
            md.GFL2MotionFavorites=md.GFL2MotionFavorites or {}
            md.GFL2MotionFavorites[file]=nextState or nil
        end
        roomSound("Select")
        self:refreshMotionLibrary()
    end

    function W:setMotionLibTab(tab)
        if self.motionLibTab==tab then return end
        self.motionLibTab=tab
        if self.motionTabBtn then self.motionTabBtn.roomActive=(tab=="motion") end
        if self.cameraTabBtn then self.cameraTabBtn.roomActive=(tab=="camera") end
        roomSound("Select")
        self:refreshMotionLibrary()
    end

    function W:refreshMotionLibrary()
        local isCam=self.motionLibTab=="camera"
        if self.motionTabBtn then self.motionTabBtn.roomActive=not isCam end
        if self.cameraTabBtn then self.cameraTabBtn.roomActive=isCam end
        local files=(isCam and GFL2ReadyRoom.cameraLibrary) and GFL2ReadyRoom.cameraLibrary() or GFL2ReadyRoom.motionLibrary()
        local favs=self:loadMotionFavorites()
        local ordered={}
        for i=1,#files do if favs[files[i]] then ordered[#ordered+1]=files[i] end end
        for i=1,#files do if not favs[files[i]] then ordered[#ordered+1]=files[i] end end
        self.motionList:clear()
        local wanted=isCam and self.cameraSelected or self.motionSelected
        local selected=nil
        for i=1,#ordered do
            local file=ordered[i]
            local row=self.motionList:addItem(motionDisplayName(file),file,
                isLocalMotion(file) and file or motionDisplayName(file))
            local kind=file:sub(1,7)=="sample:" and "MotionExample" or (isLocalMotion(file) and "MotionLocal" or "MotionFolder")
            row.source=tr(kind)
            row.favorite=favs[file]==true
            if file==wanted then selected=i end
        end
        self.motionList.selected=selected or (#ordered>0 and 1 or 0)
        local picked=ordered[self.motionList.selected]
        if isCam then self.cameraSelected=picked else self.motionSelected=picked end
        self.motionMessage=nil;self:refreshMotionControls()
    end

    function W:closeMotionPathDialog()
        if self.motionPathDialog then
            local dialog=self.motionPathDialog
            self.motionPathDialog=nil
            if dialog.isOverlay then
                self:removeChild(dialog)
            elseif dialog:isVisible() then
                dialog:destroy()
            end
        end
    end

    function W:openMotionPathDialog(removing)
        if self.closed or self.motionPathDialog then return end
        if not GFL2ReadyRoom or not GFL2ReadyRoom.browseMotionDirectory or not GFL2ReadyRoom.motionPaths then return end
        self:closeAllModals()

        local openedAt=getTimestampMs()
        local overlay=ISPanel:new(0,0,self.width,self.height)
        overlay:initialise();overlay.background=false;overlay.isOverlay=true
        local cardW=math.min(526,self.width-24)
        local cardH=math.min(416,self.height-28)
        local targetY=math.floor((self.height-cardH)/2)
        local card=ISPanel:new(math.floor((self.width-cardW)/2),targetY,cardW,cardH)
        card:initialise();card.background=false;overlay:addChild(card)
        overlay.prerender=function(p)
            local t=modalProgress(openedAt,150)
            p:drawRect(0,0,p.width,p.height,.76*t,.018,.026,.034)
            card:setY(targetY+math.floor((1-t)*12+.5))
        end
        overlay.onMouseDown=function()return true end
        overlay.onMouseUp=overlay.onMouseDown;overlay.onMouseWheel=overlay.onMouseDown

        local state={removing=removing==true,currentPath="",parentPath="",vmdCount=0,added=false,errorMsg=nil}
        local title=tr(removing and "MotionFolderManageTitle" or "MotionFolderPickerTitle")
        card.prerender=function(p)
            local t=modalProgress(openedAt,150)
            drawShadow(p,0,0,p.width,p.height,.95*t)
            surface(p,0,0,p.width,p.height,.97*t)
            drawCornerTicks(p,0,0,p.width,p.height,t,8)
            p:drawRect(0,0,p.width,2,.96*t,.95,.54,.16)
            label(p,title,14,10,21,p.width-58,.96,.97,.97,t)
            p:drawRect(12,38,p.width-24,1,.16*t,.80,.88,.90)
            if state.errorMsg and state.errorMsg~="" then
                label(p,state.errorMsg,14,p.height-66,15,p.width-28,1,.64,.38,t)
            end
        end
        local closeBtn=button(card,cardW-40,7,28,"X",self,function(w)w:closeMotionPathDialog()end)
        closeBtn:setHeight(26);closeBtn.roomLook="bare"

        local listTop=removing and 48 or 112
        local listH=cardH-listTop-(removing and 48 or 72)
        local folderList=ISScrollingListBox:new(12,listTop,cardW-24,listH)
        folderList:initialise();folderList.itemheight=34
        folderList.backgroundColor={r=.04,g=.065,b=.078,a=.86}
        folderList.borderColor={r=.38,g=.52,b=.56,a=.42}
        card:addChild(folderList)

        local pathEntry,upBtn,addBtn
        local function formatCount(n)
            local fmt=tr("MotionFolderVmdCount")
            local ok,res=pcall(string.format,fmt,n)
            return ok and res or (tostring(n).." VMD")
        end

        local function loadManageList()
            folderList:clear()
            local paths=GFL2ReadyRoom.motionPaths()
            for i=1,#paths do
                local p=paths[i]
                folderList:addItem(p,{path=p,kind="saved"})
            end
            if #paths==0 then
                state.errorMsg=tr("MotionFolderNoneAdded")
            else
                state.errorMsg=nil
            end
        end

        local function browseTo(targetPath)
            local res=GFL2ReadyRoom.browseMotionDirectory(targetPath or "")
            if res and res.error and res.error~="" then
                state.errorMsg=res.error
                return
            end
            state.errorMsg=nil
            state.currentPath=res.path or ""
            state.parentPath=res.parent or ""
            state.root=res.root==true
            state.vmdCount=res.vmdCount or 0
            state.added=res.added==true
            if pathEntry then pathEntry:setText(state.currentPath) end
            if upBtn then upBtn:setEnable(not state.root) end
            if addBtn then
                local canAdd=not state.root and state.currentPath~="" and not state.added
                addBtn:setEnable(canAdd)
                if state.added and not state.root then
                    addBtn.roomTitle=tr("MotionFolderAdded")
                elseif state.vmdCount>0 then
                    addBtn.roomTitle=tr("MotionFolderAddCurrent").." ("..formatCount(state.vmdCount)..")"
                else
                    addBtn.roomTitle=tr("MotionFolderAddCurrent")
                end
            end
            folderList:clear()
            local dirs=res.dirs or {}
            for i=1,#dirs do
                local d=dirs[i]
                local display=d.key and tr(d.key) or d.name
                folderList:addItem(display,d)
            end
            folderList:setYScroll(0)
            if #dirs==0 then
                state.errorMsg=tr("MotionFolderEmpty")
            end
        end

        folderList.doDrawItem=function(s,y,row,alt)
            local scroll=s:getYScroll()
            if y+row.height+scroll<=0 or y+scroll>=s.height then return y+row.height end
            local over=s.mouseoverselected==row.index and s:isMouseOver() and not s:isMouseOverScrollBar()
            local hover=hoverAmount(row,over)
            if alt then s:drawRect(0,y,s.width-14,row.height-2,.36,.055,.085,.102) end
            if hover>0 then s:drawRect(0,y,s.width-14,row.height-2,.72*hover,.16,.27,.30) end
            local item=row.item
            if state.removing then
                local btnW=58
                local btnX=s.width-16-btnW
                textInSlot(s,item.path,10,y+4,row.height-10,btnX-18,.90,.93,.93,1)
                local overBtn=over and s:getMouseX()>=btnX and s:getMouseX()<=btnX+btnW
                s:drawRect(btnX,y+4,btnW,row.height-10,overBtn and .95 or .78,.36,.16,.14)
                s:drawRectBorder(btnX,y+4,btnW,row.height-10,.70,.88,.48,.38)
                textInSlot(s,tr("MotionFolderRemove"),btnX+btnW/2,y+4,row.height-10,btnW-6,.97,.90,.86,1,.5)
            else
                local badgeW=0
                local rightX=s.width-20
                if item.added then
                    local tag=tr("MotionFolderAdded")
                    local w=58
                    s:drawRect(rightX-w,y+6,w,row.height-14,.84,.13,.31,.30)
                    s:drawRectBorder(rightX-w,y+6,w,row.height-14,.65,.48,.82,.78)
                    textInSlot(s,tag,rightX-w/2,y+6,row.height-14,w-4,.86,.97,.94,1,.5)
                    rightX=rightX-w-6;badgeW=badgeW+w+6
                end
                if (item.vmdCount or 0)>0 then
                    local tag=formatCount(item.vmdCount)
                    local w=74
                    s:drawRect(rightX-w,y+6,w,row.height-14,.86,.32,.22,.11)
                    s:drawRectBorder(rightX-w,y+6,w,row.height-14,.72,1,.71,.38)
                    textInSlot(s,tag,rightX-w/2,y+6,row.height-14,w-4,1,.92,.76,1,.5)
                    rightX=rightX-w-6;badgeW=badgeW+w+6
                end
                local tagColor=item.kind=="quick" and {1,.82,.48} or item.kind=="drive" and {.60,.86,.88} or {.91,.94,.93}
                s:drawRect(10,y+11,6,10,.85,tagColor[1],tagColor[2],tagColor[3])
                textInSlot(s,row.text,24,y+4,row.height-10,s.width-48-badgeW,tagColor[1],tagColor[2],tagColor[3],1)
            end
            return y+row.height
        end

        local folderDown=folderList.onMouseDown
        folderList.onMouseDown=function(s,x,y)
            if s:isMouseOverScrollBar() then return folderDown(s,x,y) end
            local idx=s:rowAt(x,y)
            local row=idx and s.items[idx]
            if row and row.item then
                if state.removing then
                    local btnW=58;local btnX=s.width-16-btnW
                    if x>=btnX and x<=btnX+btnW then
                        local err=GFL2ReadyRoom.removeMotionPath(row.item.path)
                        if err and err~="" then state.errorMsg=err
                        else
                            roomSound("Select")
                            self:refreshMotionLibrary()
                            loadManageList()
                        end
                        return true
                    end
                else
                    roomSound("Select")
                    browseTo(row.item.path)
                    return true
                end
            end
            return folderDown(s,x,y)
        end

        if removing then
            local closeBottom=button(card,cardW-116,cardH-38,104,tr("RoomExchangeCancel"),self,function(w)w:closeMotionPathDialog()end)
            closeBottom:setHeight(28)
            loadManageList()
        else
            local quickBtn=button(card,12,46,92,tr("MotionFolderQuick"),self,function()browseTo("")end)
            quickBtn:setHeight(26)
            upBtn=button(card,110,46,72,tr("MotionFolderUp"),self,function()browseTo(state.parentPath)end)
            upBtn:setHeight(26)
            local goW=58
            if ISTextEntryBox then
                pathEntry=ISTextEntryBox:new("",12,78,cardW-24-goW-6,26)
                pathEntry:initialise();pathEntry:instantiate()
                card:addChild(pathEntry)
            end
            local goBtn=button(card,cardW-12-goW,78,goW,tr("MotionFolderGo"),self,function()
                if pathEntry then browseTo(pathEntry:getText()) end
            end)
            goBtn:setHeight(26)

            local cancelBtn=button(card,12,cardH-38,96,tr("RoomExchangeCancel"),self,function(w)w:closeMotionPathDialog()end)
            cancelBtn:setHeight(28)
            addBtn=button(card,116,cardH-38,cardW-128,tr("MotionFolderAddCurrent"),self,function(w)
                local target=state.currentPath
                if (not target or target=="") and pathEntry then target=pathEntry:getText() end
                local res=GFL2ReadyRoom.addMotionPath(target or "")
                if res and res.error and res.error~="" then
                    state.errorMsg=res.error
                    return
                end
                w:refreshMotionLibrary()
                w:closeMotionPathDialog()
            end)
            addBtn:setHeight(28);addBtn.roomLook="primary"
            browseTo("")
        end

        self.motionPathDialog=overlay
        self:addChild(overlay)
        overlay:bringToTop()
    end

    function W:onAddMotionPath()
        self:openMotionPathDialog(false)
    end

    function W:onRemoveMotionPath()
        self:openMotionPathDialog(true)
    end

    function W:refreshMotionControls()
        local state=GFL2ReadyRoom.motionState(self.player)
        self.motionPlayback=state
        local camName=state.camera or ""
        local hasCam=camName~=""
        local camOn=hasCam and state.cameraEnabled==true
        local active=state.name~=""
        local trackActive=active or camOn
        local b=self.motionButtons
        local slider=self.motionSeek
        local duration=state.endFrame or 0
        slider.disabled=not trackActive or duration<=0 or self.waiting
        if slider.dragInside and (slider.disabled or slider.seekRevision~=state.revision) then slider:cancel() end
        if not slider.dragInside then slider.currentValue=not slider.disabled and clamp((state.frame or 0)/duration,0,1) or 0 end
        local speed=self.motionSpeed
        speed.disabled=not trackActive or self.waiting
        if speed.disabled then speed.dragInside=false end
        speed.currentValue=state.speed or 1
        self.motionStatus=tr(trackActive and ((state.paused or state.speed==0) and "MotionPaused" or "MotionPlaying") or "MotionStopped")
        if self.motionPlayingFile~=state.name then
            self.motionPlayingFile=state.name
            self.motionPlayingLabel=active and motionDisplayName(state.name) or nil
        end
        if self.motionSelectionFile~=self.motionSelected then
            self.motionSelectionFile=self.motionSelected
            self.motionSelectedLabel=self.motionSelected and motionDisplayName(self.motionSelected) or nil
        end
        local enabledChar=not self.waiting and GFL2CharacterRuntime.isEnabled(self.player)
        b.play:setEnable(self.motionSelected~=nil and enabledChar)
        for _,key in ipairs({"pause","restart","stop"})do b[key]:setEnable(trackActive and not self.waiting)end
        b.physics:setEnable(active and not self.waiting)
        b.reset:setEnable(active and state.physics and not self.waiting)
        b.pause.roomTitle=tr(state.paused and "MotionResume" or "MotionPause")
        b.pause.roomActive=trackActive and state.paused
        b.pause.roomIcon=state.paused and "play" or "pause"
        b.physics.roomTitle=tr(state.physics and "MotionPhysicsOn" or "MotionPhysicsOff")
        self.cameraLoadedActive=hasCam
        if hasCam then
            self.cameraStatusLabel=motionDisplayName(camName)
        elseif self.cameraSelected then
            self.cameraStatusLabel=motionDisplayName(self.cameraSelected).." ("..tr("MotionCameraReady")..")"
        else
            self.cameraStatusLabel=tr("MotionCameraNone")
        end
        b.cameraLoad:setEnable(self.cameraSelected~=nil and enabledChar)
        b.cameraToggle:setEnable(hasCam and not self.waiting)
        b.cameraToggle.roomActive=camOn
        b.cameraToggle.roomTitle=tr(camOn and "MotionCameraOn" or "MotionCameraOff")
        b.cameraClear:setEnable(hasCam and not self.waiting)
    end

    function W:onMotionCommand(command,value)
        local error=GFL2ReadyRoom.motionCommand(self.player,command,value or self.motionSelected or "")
        self.motionMessage=error~="" and error or nil;self:refreshMotionControls()
    end
end

return M
