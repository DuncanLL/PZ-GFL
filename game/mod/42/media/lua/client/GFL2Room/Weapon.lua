local M={}

function M.install(W,ctx)
    local button=ctx.button
    local clamp=ctx.clamp
    local describe=ctx.describe
    local descriptionHeight=ctx.descriptionHeight
    local economy=ctx.economy
    local getData=ctx.data
    local weaponSkins=ctx.weaponSkins

    function W:claimSignatureWeapon()
        local signatureWeapon=self.signatureWeapon
        if not signatureWeapon or self.tab~="weaponDraw" then return end
        self:roomTransaction("ClaimWeapon",signatureWeapon.id,nil,function()
            return economy.claimWeapon(self.player,signatureWeapon)
        end,function(status)
            if status=="ok" then
                self.drawNotice=nil
                self.player:Say(getText("ContextMenu_GFL2_WeaponClaimed"))
            elseif status=="alreadyCarried" then
                self.drawNotice=getText("ContextMenu_GFL2_WeaponCarried")
            else
                self.drawNotice=getText("ContextMenu_GFL2_WeaponClaimFailed")
            end
            self:layoutOverview()
        end)
    end

    function W:claimSignatureMagazine()
        local signatureWeapon=self.signatureWeapon
        if not signatureWeapon or self.tab~="weaponDraw" or self.drawAnimation or self.exchange then return end
        self:roomTransaction("ClaimMagazine",signatureWeapon.id,nil,function()
            return economy.claimMagazine(self.player,signatureWeapon)
        end,function(status)
            self.drawNotice=getText(status=="ok" and "ContextMenu_GFL2_MagazineClaimed" or "ContextMenu_GFL2_MagazineClaimFailed")
        end)
    end

    function W:applyWeaponSkin(skin)
        local weapon=self.signatureWeapon
        if not weapon or self.tab~="weaponDraw" or self.drawAnimation or self.exchange then return end
        self:roomTransaction("WeaponSkin",weapon.id..":"..skin,nil,function()
            return economy.setWeaponSkin(self.player,weapon,skin)
        end,function(status)
            if status=="ok" and self.signatureWeapon==weapon then
                self.drawNotice=nil
                self:layoutOverview()
            end
        end)
    end

    function W:layoutWeaponSkins(weapon,variants,visible,selected)
        local p=self.weaponSkinPanel
        p:setVisible(visible)
        if not visible then return end
        local data=getData()
        local width=math.max(160,math.floor(self.width*.205))
        p:setX(math.floor(self.width*.035));p:setY(106);p:setWidth(width)
        if p.weaponId~=weapon.id then
            for _,row in ipairs(self.weaponSkinRows) do p:removeChild(row) end
            self.weaponSkinRows={}
            p.weaponId=weapon.id
            local row=button(p,8,8,width-16,getText("ContextMenu_GFL2_WeaponDefaultSkin"),self,function(w)w:applyWeaponSkin("")end)
            row.skinId="";self.weaponSkinRows[1]=row
            for _,skin in ipairs(variants) do
                local id=skin.id
                local original=data.weaponStyle.skins[id]
                local title=(getTextOrNull and getTextOrNull("UI_GFL2_WeaponSkin_"..id)) or original
                local b=button(p,8,8,width-16,title,self,function(w)w:applyWeaponSkin(id)end)
                b.skinId=id;b.roomLocalizedPaint=title~=original
                self.weaponSkinRows[#self.weaponSkinRows+1]=b
            end
        end
        local function arrange(rowWidth)
            local y=8
            for _,row in ipairs(self.weaponSkinRows) do
                if row.roomLocalizedPaint then row.roomWrappedTitle=describe(row.roomTitle,rowWidth-16) end
                local lines=row.roomWrappedTitle and #row.roomWrappedTitle or 1
                row:setY(y);row:setWidth(rowWidth)
                row:setHeight(math.max(34,lines*descriptionHeight()+12))
                row.roomLook=row.skinId==selected and "primary" or "drawOnce"
                y=y+row.height+5
            end
            return y+8
        end
        local total=arrange(width-16)
        local maxHeight=math.floor((self.fullHeight-p.y)*.5)
        if total>maxHeight then total=arrange(width-30) end
        p:setHeight(math.min(total,maxHeight))
        p:setScrollHeight(total)
        p:setYScroll(clamp(p:getYScroll(),p.height-p:getScrollHeight(),0))
        p:bringToTop()
    end

    function W:layoutWeaponDescription(weapon)
        local p=self.content
        local x=math.floor(self.fullWidth*.045)-12
        local width=math.floor(self.fullWidth*.32)+24
        local top=self:titleBarHeight()+12
        if self.weaponSkinPanel:getIsVisible() then top=self.weaponSkinPanel.y+self.weaponSkinPanel.height+8 end
        local bottom=self.fullHeight-14
        local textWidth=width-24
        local lines=describe(getText(weapon.description),textWidth)
        if 65+#lines*descriptionHeight()>bottom-top then
            textWidth=textWidth-16
            lines=describe(getText(weapon.description),textWidth)
        end
        local total=65+#lines*descriptionHeight()
        local y=math.max(top,bottom-total)
        p:setX(x);p:setY(y);p:setWidth(width);p:setHeight(math.min(total,bottom-y))
        p:setScrollHeight(total);p:setYScroll(clamp(p:getYScroll(),p.height-total,0))
        p.weaponLines,p.weaponTextWidth=lines,textWidth
    end

    function W:weaponOverlayWidgets()
        local widgets={}
        for _,widget in ipairs({self.content,self.weaponSkinPanel,self.weaponAmmoButton,
            self.weaponSkinButton,self.magazineClaimButton,self.weaponClaimButton}) do
            if widget then widgets[#widgets+1]=widget end
        end
        for _,widget in ipairs(self.drawButtons or {}) do widgets[#widgets+1]=widget end
        for _,widget in ipairs(self.resourceButtons or {}) do widgets[#widgets+1]=widget end
        return widgets
    end

    function W:createWeaponPreview(signatureWeapon)
        if self.weaponPreview then
            self.weaponPreview:onMouseUp()
            self:removeChild(self.weaponPreview)
        end
        local top=self:titleBarHeight()+4
        local bottom=self.drawButtons[1].y-64
        -- Use a wide upper-center showcase stage so signature weapons fill the screen clearly.
        local height=math.max(240,bottom-top)
        local width=math.min(math.floor(self.fullWidth*.92),math.floor(height*1.98))
        local scene=ISUI3DScene:new(math.floor((self.fullWidth-width)/2),top,width,height)
        scene.background=false
        scene:initialise()
        scene:instantiate()
        local java=scene.javaObject
        local id=signatureWeapon.id
        local model=weaponSkins.model(id,economy.weaponProgress(self.player,id).skin) or id
        local script=getScriptManager():getModelScript(model)
        local muzzle=script and script:getAttachmentById("muzzle")
        -- The muzzle's local roll also describes which side of the barrel is up.
        local tilt=muzzle and (180-muzzle:getRotate():z())%360 or 0
        scene:setView("UserDefined")
        java:fromLua3("setViewRotation",-82+tilt,0,90)
        java:fromLua1("setDrawGrid",false)
        local zoom=math.floor(signatureWeapon.previewZoom or 14)
        local maxZoom=math.min(20,math.max(zoom+3,18))
        java:fromLua1("setMaxZoom",maxZoom)
        java:fromLua1("setZoom",zoom)
        java:fromLua2("createModel",id,model)
        local translation=java:fromLua1("getObjectTranslation",id)
        if not GFL2ReadyRoom.centerWeaponPreview(model,translation) then
            scene.prerender=function(p)
                if GFL2ReadyRoom.centerWeaponPreview(model,translation) then p.prerender=nil end
            end
        end
        scene.onMouseWheel=function(_,delta)
            zoom=clamp(zoom-delta,1,maxZoom)
            java:fromLua1("setZoom",zoom)
            return true
        end
        scene.onMouseDown=function(p)
            p.orbit=true
            p:setCapture(true)
            return true
        end
        scene.onMouseMove=function(p,dx,dy)
            if not p.orbit then return false end
            p.pitch=clamp((p.pitch or -82)+dy*.35,-175,-5)
            p.heading=((p.heading or 0)+dx*.35)%360
            java:fromLua3("setViewRotation",p.pitch+tilt,p.heading,90)
            return true
        end
        scene.onMouseMoveOutside=scene.onMouseMove
        scene.onMouseUp=function(p)
            p.orbit=false
            p:setCapture(false)
            return true
        end
        scene.onMouseUpOutside=scene.onMouseUp
        self:addChild(scene)
        for _,widget in ipairs(self:weaponOverlayWidgets()) do widget:bringToTop() end
        scene.weaponId=id
        scene.skinModel=model
        self.weaponPreview=scene
    end
end

return M
