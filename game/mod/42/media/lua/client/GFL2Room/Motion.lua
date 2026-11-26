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
        if not file or file=="" then return "" end
        if file:sub(1,7)=="sample:" then return file:sub(8) end
        return file:match("([^/\\]+)$") or file
    end

    local function motionTrack(slider,x,y,width,seek)
        local over=not slider.disabled and slider:isMouseOver() and (not seek or slider:getMouseY()>=13)
        local hover=hoverAmount(slider,over or (not slider.disabled and (slider.dragInside or slider.joypadFocused)))
        local h=slider.dragInside and not slider.disabled and 4 or 3
        slider:drawRect(x,y,width,h,.90,.13+.04*hover,.14+.04*hover,.16+.04*hover)
        if slider.disabled then return end
        local fill=width*clamp(slider.currentValue,0,1)
        if fill>0 then slider:drawRect(x,y,fill,h,.96,seek and .95 or .62,seek and .49 or .66,seek and .14 or .71) end
        if seek and over and not slider.dragInside then
            local tick=clamp(slider:getMouseX(),x,x+width-1)
            slider:drawRect(tick,y-3,1,h+6,.78*hover,.88,.90,.92)
        end
        local knob=image(art.."knob.png")
        local size=slider.dragInside and 11 or 8+2*hover
        if knob then slider:drawTextureScaled(knob,x+fill-size/2,y+(h-size)/2,size,size,1,.86,.87,.89) end
    end

    local function clockText(frame)
        local seconds=math.floor(math.max(0,frame or 0)/30)
        return string.format("%02d:%02d",math.floor(seconds/60),seconds%60)
    end

    local function drawSlotCard(p,x,y,w,h,tag,valueText,active,accentColor,rightReserved)
        p:drawRect(x,y,w,h,.86,.106,.116,.132)
        p:drawRectBorder(x,y,w,h,active and .24 or .10,active and accentColor[1] or .80,active and accentColor[2] or .82,active and accentColor[3] or .85)
        p:drawRect(x,y,2,h,active and .86 or .28,active and accentColor[1] or .52,active and accentColor[2] or .56,active and accentColor[3] or .60)
        local pillW=44
        p:drawRect(x+8,y+9,pillW,h-18,active and .88 or .54,active and .16 or .12,active and .17 or .13,active and .19 or .15)
        p:drawRectBorder(x+8,y+9,pillW,h-18,active and .32 or .14,active and accentColor[1] or .68,active and accentColor[2] or .70,active and accentColor[3] or .74)
        textInSlot(p,tag,x+8+pillW/2,y+9,h-18,pillW-4,active and .88 or .62,active and .89 or .66,active and .90 or .70,1,.5)
        local textX=x+8+pillW+8
        local textW=math.max(24,w-(textX-x)-rightReserved-8)
        label(p,valueText,textX,y+math.floor((h-16)/2),16,textW,
            active and .88 or .54,active and .90 or .58,active and .92 or .62)
    end

    function W:createMotionPage()
        self.motionPanelWidth=math.min(624,self.fullWidth)
        self.motionPanelHeight=math.min(544,self.fullHeight)
        self.motionLibTab="motion"
        self.motionMusicAuto=self.motionMusicAuto~=false
        self.motionCameraAuto=self.motionCameraAuto~=false
        local header=self:titleBarHeight()
        local bodyY=header+4
        local height=self.motionPanelHeight-bodyY-8
        local listWidth=math.floor((self.motionPanelWidth-28)*.43)
        self.motionDividerX=10+listWidth+6

        -- Seamless left pane (inside unified outer window frame)
        local library=ISPanel:new(10,bodyY,listWidth,height)
        library:initialise();library.background=false;self:addChild(library);self.motionLibraryBox=library
        library.prerender=function(p)
            local w=p.parent
            local tab=w.motionLibTab
            local emptyKey=tab=="camera" and "MotionNoCameras" or (tab=="face" and "MotionNoFaces" or "MotionNoFiles")
            if #w.motionList.items==0 then
                label(p,tr(emptyKey),10,84,16,p.width-20,.68,.76,.78)
            end
        end
        local tabW=math.floor((library.width-20)/3)
        self.motionTabBtn=button(library,4,6,tabW,tr("MotionTabAction"),self,function(w)w:setMotionLibTab("motion")end)
        self.motionTabBtn:setHeight(26);self.motionTabBtn.roomActive=true
        self.faceTabBtn=button(library,10+tabW,6,tabW,tr("MotionTabFace"),self,function(w)w:setMotionLibTab("face")end)
        self.faceTabBtn:setHeight(26);self.faceTabBtn.roomActive=false
        self.cameraTabBtn=button(library,16+2*tabW,6,library.width-20-2*tabW,tr("MotionTabCamera"),self,function(w)w:setMotionLibTab("camera")end)
        self.cameraTabBtn:setHeight(26);self.cameraTabBtn.roomActive=false

        local list=ISScrollingListBox:new(4,38,library.width-8,height-110)
        list:initialise();list.itemheight=42
        list.backgroundColor={r=.064,g=.072,b=.082,a=.72}
        list.borderColor={r=.82,g=.85,b=.88,a=.10}
        list.doDrawItem=function(s,y,row,alt)
            local scroll=s:getYScroll()
            if y+row.height+scroll<=0 or y+scroll>=s.height then return y+row.height end
            local overRow=s.mouseoverselected==row.index and s:isMouseOver() and not s:isMouseOverScrollBar()
            local hover=hoverAmount(row,overRow)
            if row.index==s.selected then
                s:drawRect(0,y,s.width-14,row.height-2,.94,.155+.03*hover,.170+.03*hover,.195+.03*hover)
                s:drawRect(0,y+4,2,row.height-10,.92,.95,.49,.14)
            else
                if alt then s:drawRect(0,y,s.width-14,row.height-2,.36,.10,.11,.13) end
                if hover>0 then s:drawRect(0,y,s.width-14,row.height-2,.65*hover,.16,.18,.21) end
            end
            local overStar=overRow and s:getMouseX()<=28
            local starAlpha=row.favorite and 1 or (overStar and .90 or (.34+.30*hover))
            drawStarIcon(s,14,y+math.floor((row.height-2)/2),overStar and 16 or 14,row.favorite==true,starAlpha)
            label(s,row.text,28,y+4,16,s.width-44,.88,.90,.92)
            local subColor=row.hasCompanions and {.95,.49,.14} or {.54,.58,.63}
            label(s,row.source,28,y+23,13,s.width-44,subColor[1],subColor[2],subColor[3])
            return y+row.height
        end
        local motionListDown=list.onMouseDown
        list.onMouseDown=function(s,x,y)
            if not s:isMouseOverScrollBar() and x>=0 and x<=28 then
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
            if w.motionLibTab=="camera" then
                w.cameraSelected=filename
                w.motionCameraAuto=true
                w.motionMessage=nil
                local cur=GFL2ReadyRoom.motionState(w.player)
                if cur and cur.name~="" then
                    w:onMotionCommand("camera",filename)
                    return
                end
            elseif w.motionLibTab=="face" then
                local err=GFL2VmdFace and GFL2VmdFace.validate and GFL2VmdFace.validate(filename) or ""
                if err~="" then
                    w.motionMessage=err
                else
                    w.faceSelected=filename
                    w.motionMessage=nil
                end
            else
                w:selectMotionWithBundle(filename)
                return
            end
            w:refreshMotionControls()
        end)
        library:addChild(list);self.motionList=list
        local lw=library.width-8
        local lhalf=math.floor((lw-6)/2)
        self.motionAddPath=button(library,4,height-66,lw,tr("MotionAddPath"),self,self.onAddMotionPath)
        self.motionAddPath:setHeight(28)
        self.motionRefresh=button(library,4,height-32,lhalf,tr("MotionRefresh"),self,self.refreshMotionLibrary)
        self.motionRefresh:setHeight(28)
        self.motionRemovePath=button(library,10+lhalf,height-32,lw-lhalf-6,tr("MotionRemovePath"),self,self.onRemoveMotionPath)
        self.motionRemovePath:setHeight(28)

        -- Seamless right pane (Unified Master Transport + 4 Linked Slots)
        local rightX=self.motionDividerX+7
        local rightW=self.motionPanelWidth-rightX-10
        local box=ISPanel:new(rightX,bodyY,rightW,height)
        box:initialise();box.background=false;self:addChild(box);self.motionBox=box
        box.prerender=function(p)
            local w=p.parent
            local textWidth=p.width-16
            label(p,tr("MotionControls"),8,6,19,textWidth-110,.96,.97,.97)
            local state=w.motionPlayback
            local active=state and state.name~=""
            if active then
                local frame=state.frame or 0
                for i=0,2 do
                    local bh=4+math.floor(6*(.5+.5*math.sin(frame*.24+i*1.3)))
                    p:drawRect(p.width-96+i*4,22-bh,2,bh,.92,.95,.49,.14)
                end
            end
            textInSlot(p,w.motionStatus or tr("MotionStopped"),p.width-8,8,16,84,
                active and .78 or .58,active and .70 or .62,active and .60 or .66,1,1)

            local titleLine=w.motionPlayingLabel or w.motionSelectedLabel or tr("MotionNoSelection")
            label(p,titleLine,8,30,16,textWidth,.88,.90,.92)

            label(p,tr("MotionSpeed"),8,92,14,textWidth-66,.58,.62,.67)
            textInSlot(p,string.format("%.2fx",w.motionSpeed.currentValue),p.width-8,92,14,60,.82,.85,.88,1,1)

            p:drawRect(8,244,textWidth,1,.12,.84,.86,.88)
            label(p,tr("MotionBundleTitle"),8,252,15,textWidth,.74,.78,.82)

            -- 4 Linked Slots (Action / Camera / Face / Music)
            local hasMotion=w.motionSelected~=nil
            local hasCam=w.cameraSelected~=nil
            local camOn=hasCam and w.motionCameraAuto==true
            local hasFace=w.faceSelected~=nil
            local embeddedFace=not hasFace and w.motionEmbeddedFace==true
            local hasMusic=w.musicSelected~=nil
            local musicOn=hasMusic and w.motionMusicAuto==true
            local accent={.95,.49,.14}

            drawSlotCard(p,8,276,textWidth,38,tr("MotionTabAction"),
                w.motionSelectedLabel or tr("MotionNoSelection"),hasMotion,accent,0)

            local camText=hasCam and motionDisplayName(w.cameraSelected) or tr("MotionCameraNone")
            drawSlotCard(p,8,320,textWidth,38,tr("MotionTabCamera"),
                camText,camOn,accent,98)

            local faceText=hasFace and motionDisplayName(w.faceSelected)
                or (embeddedFace and tr("MotionFaceEmbedded") or tr("MotionFaceNone"))
            drawSlotCard(p,8,364,textWidth,38,tr("MotionTabFace"),
                faceText,hasFace or embeddedFace,accent,50)

            local musicText=hasMusic and (w.musicSelectedLabel or motionDisplayName(w.musicSelected)) or tr("MotionMusicNone")
            drawSlotCard(p,8,408,textWidth,38,tr("MotionTagMusic"),
                musicText,musicOn,accent,126)

            local msg=w.motionMessage or w.motionMusicMessage
            if msg and msg~="" then
                label(p,msg,8,454,15,textWidth,.86,.66,.50)
            end
        end

        local rw=box.width-16
        local third=math.floor((rw-12)/3)
        local half=math.floor((rw-6)/2)
        local function control(x,y,w,key,command)
            return button(box,x,y,w,tr(key),self,
                function(win)win:onMotionCommand(command)end)
        end

        local playBtn=control(8,136,rw,"MotionPlay","play")
        playBtn:setHeight(32);playBtn.roomLook="primary"
        local pauseBtn=control(8,174,third,"MotionPause","pause")
        pauseBtn:setHeight(28)
        local restartBtn=control(14+third,174,third,"MotionRestart","restart")
        restartBtn:setHeight(28)
        local stopBtn=control(20+2*third,174,rw-2*third-12,"MotionStop","stop")
        stopBtn:setHeight(28)
        local physBtn=control(8,208,half,"MotionPhysicsOn","physics")
        physBtn:setHeight(26)
        local resetBtn=control(14+half,208,rw-half-6,"MotionPhysicsReset","reset")
        resetBtn:setHeight(26)

        -- Slot inline controls
        local slotRight=8+rw-6
        local camClearBtn=button(box,slotRight-42,327,42,tr("MotionCameraClear"),self,function(w)
            w.cameraSelected=nil
            w.motionMessage=nil
            if w.motionLibTab=="camera" and w.motionList then w.motionList.selected=0 end
            w:onMotionCommand("cameraClear","")
        end)
        camClearBtn:setHeight(24)
        local camToggleBtn=button(box,slotRight-92,327,46,tr("MotionLinkOn"),self,function(w)
            w:toggleMotionCameraAuto()
        end)
        camToggleBtn:setHeight(24)

        local faceClearBtn=button(box,slotRight-42,371,42,tr("MotionFaceClear"),self,function(w)
            w.faceSelected=nil
            w.motionMessage=nil
            if w.motionLibTab=="face" and w.motionList then w.motionList.selected=0 end
            w:refreshMotionControls()
        end)
        faceClearBtn:setHeight(24)

        local musicClearBtn=button(box,slotRight-24,415,24,"X",self,function(w)
            w.musicSelected=nil
            w.musicSelectedLabel=nil
            w.motionAutoMusicArmed=false
            w.motionMusicMessage=nil
            if GFL2VmdMusic then GFL2VmdMusic.stop() end
            w:refreshMotionControls()
        end)
        musicClearBtn:setHeight(24)
        local musicBrowseBtn=button(box,slotRight-70,415,42,tr("MotionSlotPick"),self,function(w)
            w:openMotionMusicDialog()
        end)
        musicBrowseBtn:setHeight(24)
        local musicAutoBtn=button(box,slotRight-120,415,46,tr("MotionLinkOn"),self,function(w)
            w:toggleMotionMusicAuto()
        end)
        musicAutoBtn:setHeight(24)

        self.motionButtons={
            play=playBtn,
            pause=pauseBtn,
            restart=restartBtn,
            stop=stopBtn,
            physics=physBtn,
            reset=resetBtn,
            cameraToggle=camToggleBtn,
            cameraClear=camClearBtn,
            faceClear=faceClearBtn,
            musicAuto=musicAutoBtn,
            musicBrowse=musicBrowseBtn,
            musicClear=musicClearBtn,
        }
        for _,key in ipairs({"play","pause","restart","stop"})do self.motionButtons[key].roomIcon=key end

        self:createMotionTimeline(box,rw)
        local speed=ISSliderPanel:new(8,110,rw,20,self,function(w,value)
            if math.abs(value-(w.motionPlayback.speed or 1))<.0001 then return end
            w:onMotionCommand("speed",tostring(value))
        end)
        speed:initialise();speed:instantiate()
        speed:setDoButtons(false);speed:setValues(0,1,.01,.1,true)
        speed.sliderBarDim.x=6;speed.sliderBarDim.w=rw-12
        speed.currentValue=1;speed.disabled=true
        local speedDown=speed.onMouseDown
        speed.onMouseDown=function(s,x,y)
            if not s.disabled then return speedDown(s,x,y) end
        end
        speed.render=function(s)motionTrack(s,s.sliderBarDim.x,math.floor(s.height/2)-1,s.sliderBarDim.w,false)end
        box:addChild(speed);self.motionSpeed=speed
        self.motionMusicSeek=nil
    end

    function W:createMotionTimeline(box,width)
        local slider=ISSliderPanel:new(8,52,width,34,self)
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
            w:onMotionCommand("seek",revision..":"..tostring(fraction))
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
            textInSlot(s,s.disabled and "--:--" or s.clock,0,0,16,s.width/2,.82,.88,.90,1)
            textInSlot(s,s.disabled and "--:--" or s.total,s.width,0,16,s.width/2,.58,.67,.71,1,1)
        end
        box:addChild(slider);self.motionSeek=slider
    end

    function W:selectMotionWithBundle(filename)
        self.motionSelected=filename
        self.motionMessage=nil
        self.motionMusicMessage=nil
        self.motionEmbeddedFace=false
        self.motionBundleDir=nil
        if filename and filename~="" and GFL2ReadyRoom and GFL2ReadyRoom.motionBundle then
            local bundle=GFL2ReadyRoom.motionBundle(filename)
            if bundle then
                self.motionBundleDir=(bundle.dir and bundle.dir~="") and bundle.dir or nil
                self.motionEmbeddedFace=bundle.embeddedFace==true
                self.cameraSelected=(bundle.camera and bundle.camera~="") and bundle.camera or nil
                self.faceSelected=(bundle.face and bundle.face~="") and bundle.face or nil
                if bundle.music and bundle.music~="" then
                    self.musicSelected=bundle.music
                    self.musicSelectedLabel=motionDisplayName(bundle.music)
                else
                    self.musicSelected=nil
                    self.musicSelectedLabel=nil
                end
            end
        else
            self.cameraSelected=nil
            self.faceSelected=nil
            self.musicSelected=nil
            self.musicSelectedLabel=nil
        end
        self:refreshMotionControls()
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
        if self.faceTabBtn then self.faceTabBtn.roomActive=(tab=="face") end
        if self.cameraTabBtn then self.cameraTabBtn.roomActive=(tab=="camera") end
        roomSound("Select")
        self:refreshMotionLibrary()
    end

    local function buildCompanionSubtitle(file,meta)
        if file:sub(1,7)=="sample:" then return tr("MotionExample"),false end
        local tags={}
        if meta then
            if meta.camera and meta.camera~="" then tags[#tags+1]=tr("MotionTabCamera") end
            if (meta.face and meta.face~="") or meta.embeddedFace then tags[#tags+1]=tr("MotionTabFace") end
            if meta.music and meta.music~="" then tags[#tags+1]=tr("MotionTagMusic") end
        end
        local prefix=(meta and meta.folder and meta.folder~="") and meta.folder
            or tr(isLocalMotion(file) and "MotionLocal" or "MotionFolder")
        if #tags>0 then
            return prefix.." · "..table.concat(tags,"/"),true
        end
        return prefix,false
    end

    function W:refreshMotionLibrary()
        local tab=self.motionLibTab
        local isCam=tab=="camera"
        local isFace=tab=="face"
        if self.motionTabBtn then self.motionTabBtn.roomActive=(tab=="motion") end
        if self.faceTabBtn then self.faceTabBtn.roomActive=isFace end
        if self.cameraTabBtn then self.cameraTabBtn.roomActive=isCam end
        local files
        local metaMap=nil
        if isCam and GFL2ReadyRoom.cameraLibrary then
            files=GFL2ReadyRoom.cameraLibrary()
        elseif isFace and GFL2VmdFace and GFL2VmdFace.library then
            files=GFL2VmdFace.library()
        else
            files=GFL2ReadyRoom.motionLibrary()
            if GFL2ReadyRoom.motionLibraryMeta then
                metaMap=GFL2ReadyRoom.motionLibraryMeta()
            end
        end
        local favs=self:loadMotionFavorites()
        local ordered={}
        for i=1,#files do if favs[files[i]] then ordered[#ordered+1]=files[i] end end
        for i=1,#files do if not favs[files[i]] then ordered[#ordered+1]=files[i] end end
        self.motionList:clear()
        local wanted=isCam and self.cameraSelected or (isFace and self.faceSelected or self.motionSelected)
        local selected=nil
        for i=1,#ordered do
            local file=ordered[i]
            local row=self.motionList:addItem(motionDisplayName(file),file,
                isLocalMotion(file) and file or motionDisplayName(file))
            if not isCam and not isFace then
                local sub,hasComp=buildCompanionSubtitle(file,metaMap and metaMap[file])
                row.source=sub
                row.hasCompanions=hasComp
            else
                local folder=isLocalMotion(file) and file:match("([^/\\]+)[/\\][^/\\]+$") or nil
                local kind=file:sub(1,7)=="sample:" and tr("MotionExample")
                    or (folder or tr(isLocalMotion(file) and "MotionLocal" or "MotionFolder"))
                row.source=kind
                row.hasCompanions=false
            end
            row.favorite=favs[file]==true
            if file==wanted then selected=i end
        end
        if isFace or isCam then
            self.motionList.selected=selected or 0
        else
            self.motionList.selected=selected or (#ordered>0 and 1 or 0)
            local picked=ordered[self.motionList.selected]
            if picked~=self.motionSelected or not self.motionBundleInitialized then
                self.motionBundleInitialized=true
                self:selectMotionWithBundle(picked)
                return
            end
        end
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

    function W:closeMotionMusicDialog()
        if not self.motionMusicDialog then return end
        local dialog=self.motionMusicDialog
        self.motionMusicDialog=nil
        if dialog.isOverlay then
            self:removeChild(dialog)
        elseif dialog:isVisible() then
            dialog:destroy()
        end
    end

    function W:openMotionMusicDialog()
        if self.closed or self.motionMusicDialog or not GFL2VmdMusic then return end
        self:closeAllModals()
        self:closeMotionPathDialog()

        local openedAt=getTimestampMs()
        local overlay=ISPanel:new(0,0,self.width,self.height)
        overlay:initialise();overlay.background=false;overlay.isOverlay=true
        local cardW=math.min(526,self.width-24)
        local cardH=math.min(440,self.height-28)
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

        local state={currentPath="",parentPath="",root=true,selectedPath="",errorMsg=nil}
        card.prerender=function(p)
            local t=modalProgress(openedAt,150)
            drawShadow(p,0,0,p.width,p.height,.95*t)
            surface(p,0,0,p.width,p.height,.97*t)
            drawCornerTicks(p,0,0,p.width,p.height,t,8)
            p:drawRect(0,0,p.width,2,.96*t,.95,.54,.16)
            label(p,tr("MotionMusicPickerTitle"),14,10,21,p.width-58,.96,.97,.97,t)
            p:drawRect(12,38,p.width-24,1,.16*t,.80,.88,.90)
            if state.errorMsg and state.errorMsg~="" then
                label(p,state.errorMsg,14,p.height-66,15,p.width-28,1,.64,.38,t)
            end
        end

        local closeBtn=button(card,cardW-40,7,28,"X",self,function(w)w:closeMotionMusicDialog()end)
        closeBtn:setHeight(26);closeBtn.roomLook="bare"

        local folderList=ISScrollingListBox:new(12,112,cardW-24,cardH-196)
        folderList:initialise();folderList.itemheight=32
        folderList.backgroundColor={r=.04,g=.065,b=.078,a=.86}
        folderList.borderColor={r=.38,g=.52,b=.56,a=.42}
        card:addChild(folderList)

        local pathEntry,upBtn,selectBtn
        local function updateSelectionButton()
            if selectBtn then selectBtn:setEnable(state.selectedPath~="") end
        end
        local function browseTo(targetPath)
            local ok,result=pcall(GFL2VmdMusic.browseDirectory,targetPath or "")
            if not ok or not result then
                state.errorMsg=tr("MotionMusicUnavailable")
                return
            end
            if result.error and result.error~="" then
                state.errorMsg=result.error
                return
            end
            state.errorMsg=nil
            state.currentPath=result.path or ""
            state.parentPath=result.parent or ""
            state.root=result.root==true
            state.selectedPath=result.selected or ""
            if pathEntry then pathEntry:setText(state.currentPath) end
            if upBtn then upBtn:setEnable(not state.root) end
            updateSelectionButton()

            folderList:clear()
            local dirs=result.dirs or {}
            for i=1,#dirs do
                local directory=dirs[i]
                folderList:addItem(directory.key and tr(directory.key) or directory.name,directory)
            end
            local files=result.files or {}
            for i=1,#files do
                local file=files[i]
                folderList:addItem(file.name,file)
            end
            folderList:setYScroll(0)
            if #dirs==0 and #files==0 then
                state.errorMsg=tr("MotionMusicNoFiles")
            end
        end

        folderList.doDrawItem=function(s,y,row,alt)
            local scroll=s:getYScroll()
            if y+row.height+scroll<=0 or y+scroll>=s.height then return y+row.height end
            local item=row.item
            local over=s.mouseoverselected==row.index and s:isMouseOver() and not s:isMouseOverScrollBar()
            local selected=item.kind=="file" and state.selectedPath==item.path
            if alt then s:drawRect(0,y,s.width-14,row.height-2,.36,.055,.085,.102) end
            if over then s:drawRect(0,y,s.width-14,row.height-2,hoverAmount(row,true)*.72,.16,.27,.30) end
            if selected then s:drawRect(0,y,s.width-14,row.height-2,.68,.25,.40,.35) end
            local color=item.kind=="dir" and {1,.82,.48} or {.91,.94,.93}
            s:drawRect(10,y+10,6,10,.85,color[1],color[2],color[3])
            textInSlot(s,row.text,24,y+3,row.height-8,s.width-48,color[1],color[2],color[3],1)
            return y+row.height
        end

        local folderDown=folderList.onMouseDown
        folderList.onMouseDown=function(s,x,y)
            if s:isMouseOverScrollBar() then return folderDown(s,x,y) end
            local index=s:rowAt(x,y)
            local row=index and s.items[index]
            if row and row.item then
                local item=row.item
                roomSound("Select")
                if item.kind=="dir" or item.kind=="drive" or item.kind=="quick" then
                    browseTo(item.path)
                elseif item.kind=="file" then
                    state.selectedPath=item.path
                    state.errorMsg=nil
                    updateSelectionButton()
                end
                return true
            end
            return folderDown(s,x,y)
        end

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

        local cancelBtn=button(card,12,cardH-38,96,tr("RoomExchangeCancel"),self,function(w)w:closeMotionMusicDialog()end)
        cancelBtn:setHeight(28)
        selectBtn=button(card,116,cardH-38,cardW-128,tr("MotionMusicUseFile"),self,function(w)
            if state.selectedPath=="" then return end
            w.musicSelected=state.selectedPath
            w.musicSelectedLabel=motionDisplayName(state.selectedPath)
            w.motionMusicAuto=true
            w.motionMusicMessage=nil
            local motion=GFL2ReadyRoom.motionState(w.player)
            if motion.name~="" and not motion.paused and (motion.speed or 1)>0 then
                w:playMotionMusicAtFrame(motion.frame or 0)
            end
            w:refreshMotionControls()
            w:closeMotionMusicDialog()
        end)
        selectBtn:setHeight(28);selectBtn.roomLook="primary"
        selectBtn:setEnable(false)

        self.motionMusicDialog=overlay
        self:addChild(overlay)
        overlay:bringToTop()
        -- Open directly in the current dance folder when available
        browseTo(self.musicSelected or self.motionBundleDir or "")
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
        local hasLiveCam=camName~=""
        local camOn=hasLiveCam and state.cameraEnabled==true
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

        -- Slot controls
        local hasCamSlot=self.cameraSelected~=nil or hasLiveCam
        b.cameraToggle:setEnable(hasCamSlot and not self.waiting)
        b.cameraToggle.roomActive=hasCamSlot and self.motionCameraAuto==true
        b.cameraToggle.roomTitle=tr(self.motionCameraAuto and "MotionLinkOn" or "MotionLinkOff")
        b.cameraClear:setEnable(hasCamSlot and not self.waiting)

        b.faceClear:setEnable(self.faceSelected~=nil and not self.waiting)

        if self.motionAutoMusicArmed and not active then
            self.motionAutoMusicArmed=false
            if GFL2VmdMusic then GFL2VmdMusic.stop() end
        end
        local musicState=GFL2VmdMusic and GFL2VmdMusic.state and GFL2VmdMusic.state() or nil
        if self.motionAutoMusicArmed and (not musicState or not musicState.loaded) then
            self.motionAutoMusicArmed=false
        end
        self.motionMusicPlayback=musicState
        b.musicAuto:setEnable(self.musicSelected~=nil and not self.waiting)
        b.musicAuto.roomActive=self.musicSelected~=nil and self.motionMusicAuto==true
        b.musicAuto.roomTitle=tr(self.motionMusicAuto and "MotionLinkOn" or "MotionLinkOff")
        b.musicBrowse:setEnable(not self.waiting)
        b.musicClear:setEnable(self.musicSelected~=nil and not self.waiting)
    end

    function W:playMotionMusicAtFrame(frame)
        if not GFL2VmdMusic or not self.musicSelected then return end
        local result=GFL2VmdMusic.play(self.musicSelected)
        self.motionMusicMessage=result~="" and result or nil
        self.motionAutoMusicArmed=(result=="")
        if result=="" and frame and frame>0 then
            local ms=(frame/30)*1000
            local st=GFL2VmdMusic.state()
            if st and st.duration>0 then
                GFL2VmdMusic.seek(clamp(ms,0,st.duration))
            end
        end
    end

    function W:toggleMotionCameraAuto()
        self.motionCameraAuto=not self.motionCameraAuto
        self.motionMessage=nil
        local state=self.motionPlayback or GFL2ReadyRoom.motionState(self.player)
        if state and state.name~="" then
            if self.motionCameraAuto then
                if self.cameraSelected then
                    local err=GFL2ReadyRoom.motionCommand(self.player,"camera",self.cameraSelected)
                    if err~="" then self.motionMessage=err end
                end
            else
                if state.cameraEnabled then
                    GFL2ReadyRoom.motionCommand(self.player,"cameraToggle","")
                end
            end
        end
        self:refreshMotionControls()
    end

    function W:toggleMotionMusicAuto()
        self.motionMusicAuto=not self.motionMusicAuto
        self.motionMusicMessage=nil
        if not self.motionMusicAuto then
            self.motionAutoMusicArmed=false
            if GFL2VmdMusic then GFL2VmdMusic.stop() end
        else
            local state=self.motionPlayback or GFL2ReadyRoom.motionState(self.player)
            if state and state.name~="" and not state.paused and (state.speed or 1)>0 and self.musicSelected then
                self:playMotionMusicAtFrame(state.frame or 0)
            end
        end
        self:refreshMotionControls()
    end

    function W:onMotionCommand(command,value)
        local previous=GFL2ReadyRoom.motionState(self.player)
        local action=value or self.motionSelected or ""
        local error=""

        if command=="play" then
            -- Auto-sync linked camera slot before starting playback so frame 0 matches cleanly
            if self.motionCameraAuto and self.cameraSelected then
                local camErr=GFL2ReadyRoom.motionCommand(self.player,"camera",self.cameraSelected)
                if camErr~="" then self.motionMessage=camErr end
            else
                GFL2ReadyRoom.motionCommand(self.player,"cameraClear","")
            end
            if self.faceSelected and GFL2VmdFace and GFL2VmdFace.play then
                error=GFL2VmdFace.play(self.player,action,self.faceSelected)
            else
                error=GFL2ReadyRoom.motionCommand(self.player,"play",action)
            end
        else
            error=GFL2ReadyRoom.motionCommand(self.player,command,action)
        end

        self.motionMessage=error~="" and error or nil
        if error=="" then
            local current=GFL2ReadyRoom.motionState(self.player)
            if command=="play" or command=="restart" then
                if self.motionMusicAuto and self.musicSelected
                    and current.name~="" and not current.paused and (current.speed or 1)>0 then
                    self:playMotionMusicAtFrame(0)
                else
                    self.motionAutoMusicArmed=false
                    if GFL2VmdMusic then GFL2VmdMusic.stop() end
                end
            elseif command=="pause" or command=="speed" then
                if current.paused or (current.speed or 1)<=0 then
                    if GFL2VmdMusic then self.motionMusicMessage=GFL2VmdMusic.pause() end
                elseif previous.paused or (previous.speed or 1)<=0 then
                    if GFL2VmdMusic then self.motionMusicMessage=GFL2VmdMusic.resume() end
                end
                if self.motionMusicMessage=="" then self.motionMusicMessage=nil end
            elseif command=="seek" and self.motionMusicAuto and GFL2VmdMusic
                and GFL2VmdMusic.state().loaded then
                local fraction=tonumber((value or ""):match("^[^:]+:(.+)$"))
                local musicState=GFL2VmdMusic.state()
                if fraction and musicState.duration>0 then
                    local clamped=clamp(fraction,0,1)
                    local targetMs=(current.endFrame or 0)>0
                        and clamp((clamped*current.endFrame/30)*1000,0,musicState.duration)
                        or (musicState.duration*clamped)
                    self.motionMusicMessage=GFL2VmdMusic.seek(targetMs)
                    if self.motionMusicMessage=="" then self.motionMusicMessage=nil end
                end
            end
        end
        if command=="stop" then
            self.motionAutoMusicArmed=false
            if GFL2VmdMusic then GFL2VmdMusic.stop() end
            self.motionMusicMessage=nil
            if previous.cameraEnabled or (previous.camera and previous.camera~="") then
                GFL2ReadyRoom.motionCommand(self.player,"cameraClear","")
            end
        end
        self:refreshMotionControls()
    end
end

return M
