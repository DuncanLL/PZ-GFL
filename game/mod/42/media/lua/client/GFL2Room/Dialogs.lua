local M={}

function M.install(W,ctx)
    local button=ctx.button
    local clamp=ctx.clamp
    local drawCornerTicks=ctx.drawCornerTicks
    local drawShadow=ctx.drawShadow
    local economy=ctx.economy
    local getData=ctx.data
    local label=ctx.label
    local modalProgress=ctx.modalProgress
    local number=ctx.number
    local resourceIcon=ctx.resourceIcon
    local resourceTitles=ctx.resourceTitles
    local styleSlider=ctx.styleSlider
    local surface=ctx.surface
    local tr=ctx.tr

    function W:closeExchange()
        local exchange=self.exchange
        if not exchange then return end
        self.exchange=nil
        exchange.slider.dragInside=false;exchange.slider:setCapture(false)
        self:removeChild(exchange.overlay)
    end

    function W:closeWorkshopPrompt()
        local prompt=self.workshopPrompt
        if not prompt then return end
        self.workshopPrompt=nil
        self:removeChild(prompt.overlay)
    end

    function W:closeAllModals()
        self:closeWorkshopPrompt()
        self:closeExchange()
        if self.motionMusicDialog then self:closeMotionMusicDialog() end
        self:closeMotionPathDialog()
    end

    function W:requestWorkshopPage()
        local character=self.byCharacter and self.byCharacter[self.character]
        if self.closed or self.folded or self.foldStart or self.exchange or self.workshopPrompt
            or self.roomPage~="appearance" or self.tab~="overview" or not character or not character.locked then return end
        local id=(getData().roomWorkshopItems or {})[character.name]
        if type(id)~="string" or not id:match("^%d+$") then return end
        self:closeAllModals()
        local name=character.name
        self.preview:onMouseUp()
        local openedAt=getTimestampMs()
        local overlay=ISPanel:new(0,0,self.width,self.height)
        overlay:initialise();overlay.background=false
        local width=math.min(560,self.width-80)
        local targetY=math.floor((self.height-240)/2)
        local card=ISPanel:new(math.floor((self.width-width)/2),targetY,width,240)
        card:initialise();card.background=false;overlay:addChild(card)
        overlay.prerender=function(p)
            local t=modalProgress(openedAt,150)
            p:drawRect(0,0,p.width,p.height,.76*t,.018,.026,.034)
            card:setY(targetY+math.floor((1-t)*12+.5))
        end
        overlay.onMouseDown=function()return true end
        overlay.onMouseUp=overlay.onMouseDown;overlay.onMouseWheel=overlay.onMouseDown
        card.prerender=function(p)
            local t=modalProgress(openedAt,150)
            drawShadow(p,0,0,p.width,p.height,.95*t)
            surface(p,0,0,p.width,p.height,.97*t)
            drawCornerTicks(p,0,0,p.width,p.height,t,8)
            p:drawRect(0,0,p.width,2,.88*t,.95,.49,.14)
            label(p,tr("RoomWorkshopConfirm"),p.width/2,28,25,p.width-48,.90,.92,.94,t,true)
            p:drawRect(math.floor(p.width/2-20),66,40,2,.84*t,.95,.49,.14)
            label(p,name,p.width/2,88,31,p.width-48,.82,.74,.64,t,true)
            label(p,tr("RoomWorkshopDestination"),p.width/2,137,16,p.width-48,.66,.70,.74,t,true)
        end
        local prompt={overlay=overlay,card=card,id=id,name=name}
        local buttonWidth=math.floor((width-72)/2)
        prompt.cancel=button(card,24,184,buttonWidth,tr("RoomExchangeCancel"),self,function(w)w:closeWorkshopPrompt()end)
        prompt.confirm=button(card,48+buttonWidth,184,buttonWidth,tr("RoomWorkshopAccept"),self,function(w)
            if w.workshopPrompt~=prompt then return end
            w:closeWorkshopPrompt()
            if isSteamOverlayEnabled() then activateSteamOverlayToWorkshopItem(id)
            else openUrl("https://steamcommunity.com/sharedfiles/filedetails/?id="..id) end
        end)
        prompt.cancel:setHeight(36);prompt.confirm:setHeight(36);prompt.confirm.roomLook="primary"
        self.workshopPrompt=prompt
        self:addChild(overlay)
    end

    function W:updateExchange(quantity)
        local s=self.exchange
        s.maximum=economy.available(self.player,s.key,s.weapon)
        s.quantity=clamp(math.floor(quantity+.5),1,math.max(1,s.maximum))
        s.slider:setValues(1,math.max(2,s.maximum),1,10,true)
        s.slider:setCurrentValue(s.quantity,true)
        s.slider.disabled=s.maximum<=1
        s.less:setEnable(s.quantity>1);s.minimum:setEnable(s.quantity>1)
        s.more:setEnable(s.quantity<s.maximum);s.maximumButton:setEnable(s.quantity<s.maximum)
        s.confirm:setEnable(s.maximum>0)
    end

    function W:openExchange(key,weapon)
        if self.closed or self.folded or self.foldStart or self.drawAnimation then return end
        local buyingAmmo=key=="ammo"
        local ammo=buyingAmmo and economy.weaponAmmo(weapon)
        if buyingAmmo and not ammo then return end
        self:closeAllModals()
        local openedAt=getTimestampMs()
        local overlay=ISPanel:new(0,0,self.width,self.height);overlay:initialise();overlay.background=false
        local width=math.min(810,math.floor(self.width*.78))
        local height=math.min(433,self.height-76)
        local targetY=math.floor((self.height-height)/2)
        local card=ISPanel:new(math.floor((self.width-width)/2),targetY,width,height)
        card:initialise();card.background=false;overlay:addChild(card)
        overlay.prerender=function(p)
            local t=modalProgress(openedAt,150)
            p:drawRect(0,0,p.width,p.height,.76*t,.018,.026,.034)
            card:setY(targetY+math.floor((1-t)*12+.5))
        end
        overlay.onMouseDown=function()return true end
        overlay.onMouseUp=overlay.onMouseDown
        overlay.onMouseWheel=overlay.onMouseDown
        local s={key=key,weapon=weapon,ammo=ammo,overlay=overlay,quantity=1}
        local buyingCredits=key=="credits"
        local cost=buyingCredits and 1 or (buyingAmmo and ammo.price or economy.cost[key])
        local title=buyingAmmo and (ammo.name or tr("RoomAmmoBuy")) or tr(resourceTitles[key])
        local cashRate=economy.creditsRate()
        local rateLabel=buyingCredits and tostring(tr("RoomCashRate")):gsub("300",tostring(cashRate),1)
            or buyingAmmo and string.format(tr("RoomAmmoRate"),ammo.price)
            or tr(key=="targeted" and "RoomTargetedPermitRate" or "RoomPermitRate")
        card.prerender=function(p)
            local t=modalProgress(openedAt,150)
            drawShadow(p,0,0,p.width,p.height,.96*t)
            surface(p,0,0,p.width,p.height,.97*t)
            drawCornerTicks(p,0,0,p.width,p.height,t,8)
            p:drawRect(0,0,p.width,2,.88*t,.95,.49,.14)
            -- Recessed icon showcase cavity
            p:drawRect(29,44,144,144,.86*t,.058,.064,.074)
            p:drawRectBorder(29,44,144,144,.14*t,.84,.86,.88)
            drawCornerTicks(p,29,44,144,144,.45*t,5)
            if buyingAmmo then
                if ammo.texture then p:drawTextureScaledAspect(ammo.texture,43,57,116,116,t,1,1,1)
                else resourceIcon(p,"ammo",55,69,92) end
            else
                resourceIcon(p,key,39,53,124)
            end
            label(p,title,198,45,29,p.width-280,.90,.92,.94,t)
            p:drawRect(198,83,38,2,.88*t,.95,.49,.14)
            p:drawRect(240,84,math.max(0,p.width-281),1,.12*t,.84,.86,.88)
            label(p,rateLabel,198,94,17,p.width-239,.64,.68,.73,t)
            p:drawRect(29,p.height-164,p.width-58,1,.12*t,.84,.86,.88)
            label(p,tr("RoomExchangeQuantity"),p.width/2-90,p.height-154,17,124,.70,.74,.78,t)
            number(p,s.quantity,p.width/2+40,p.height-154,17,.82,.74,.64)
            label(p,tr("RoomExchangeSpend"),p.width/2-100,p.height-76,17,80,.70,.74,.78,t)
            if not buyingCredits then resourceIcon(p,"credits",p.width/2-26,p.height-83,27) end
            number(p,s.quantity*cost,p.width/2+5,p.height-76,17,.82,.74,.64)
            label(p,tr(buyingCredits and "RoomCash" or "RoomCredits"),p.width/2+90,p.height-76,16,78,.70,.74,.78,t)
            if buyingCredits then
                label(p,tr("RoomCredits"),198,133,16,85,.66,.70,.74,t)
                number(p,s.quantity*cashRate,290,130,22,.82,.74,.64)
            end
            local message=s.message or (s.maximum==0 and tr("RoomNotEnough"))
            if message then label(p,message,p.width/2,p.height-107,15,p.width-50,1,.58,.36,t,true) end
        end
        local left=math.floor(width*.15)
        local right=math.floor(width*.85)
        s.minimum=button(card,left-31,height-128,60,tr("RoomExchangeMin"),self,function(w)w:updateExchange(1)end)
        s.less=button(card,left+35,height-128,33,"-",self,function(w)w:updateExchange(s.quantity-1)end)
        s.more=button(card,right-68,height-128,33,"+",self,function(w)w:updateExchange(s.quantity+1)end)
        s.maximumButton=button(card,right-29,height-128,60,tr("RoomExchangeMax"),self,function(w)w:updateExchange(s.maximum)end)
        s.slider=ISSliderPanel:new(left+77,height-126,right-left-154,23,self,function(w,value)w:updateExchange(value)end)
        s.slider:initialise();s.slider:instantiate();s.slider:setDoButtons(false)
        if styleSlider then styleSlider(s.slider,"amber") end
        card:addChild(s.slider)
        local closeX=button(card,width-54,14,34,"X",self,function(w)w:closeExchange()end)
        closeX.roomLook="bare"
        local cancelBtn=button(card,math.floor(width*.23),height-48,math.floor(width*.24),tr("RoomExchangeCancel"),self,function(w)w:closeExchange()end)
        cancelBtn:setHeight(34)
        s.confirm=button(card,math.floor(width*.53),height-48,math.floor(width*.24),tr("RoomExchangeBuy"),self,function(w)
            if buyingAmmo then
                w:roomTransaction("BuyAmmo",weapon.id,s.quantity,function()
                    return economy.buyAmmo(w.player,weapon,s.quantity)
                end,function(status)
                    if w.exchange~=s then return end
                    s.message=tr(status=="ok" and "RoomExchangeDone" or "RoomNotEnough")
                    w:updateExchange(s.quantity)
                end)
            else
                w:roomTransaction("Exchange",key,s.quantity,function()
                    return economy.buy(w.player,key,s.quantity) and "ok" or "short"
                end,function(status)
                    if w.exchange~=s then return end
                    s.message=tr(status=="ok" and "RoomExchangeDone" or "RoomNotEnough")
                    w:updateExchange(s.quantity)
                end)
            end
        end)
        s.confirm:setHeight(34);s.confirm.roomLook="primary"
        s.confirm:setSound("activate","GFL2RoomConfirm")
        self.exchange=s
        self:addChild(overlay)
        self:updateExchange(1)
    end
end

return M
