local M = {}

local panel = nil
local lockBtn = nil
local closeBtn = nil
local vmdBtn = nil
local gripBtn = nil
local physBtn = nil
local liveState = {
    enabled = false,
    size = 200,
    zoom = 265,
    yaw = 6,
    pan = 0,
    lock = false,
    fps = 24,
    x = -1,
    y = -1,
}
local onUpdateCb = nil
local currentPlayer = nil

local function clamp(value, lo, hi)
    return math.max(lo, math.min(hi, value))
end

local function clampInt(value, lo, hi, fallback)
    local n = tonumber(value)
    if not n then return fallback end
    return clamp(math.floor(n + (n >= 0 and 0.5 or -0.5)), lo, hi)
end

local function textOr(key, fallback)
    if getTextOrNull then
        local v = getTextOrNull(key)
        if v and v ~= "" then return v end
    end
    if getText then
        local v = getText(key)
        if v and v ~= key then return v end
    end
    return fallback or key
end

local function panelDimensions(size)
    local w = clampInt(size, 160, 340, 200)
    local h = math.floor(w * 1.25 + 0.5)
    return w, h
end

local function defaultPosition(w, h)
    local core = getCore and getCore()
    local sw = core and core:getScreenWidth() or 1280
    local sh = core and core:getScreenHeight() or 720
    return math.max(8, sw - w - 24), math.max(8, sh - h - 28)
end

local function clampOnScreen(x, y, w, h)
    local core = getCore and getCore()
    local sw = core and core:getScreenWidth() or 1280
    local sh = core and core:getScreenHeight() or 720
    return clamp(x, 0, math.max(0, sw - w)), clamp(y, 0, math.max(0, sh - h))
end

local function readyRoomVisible()
    local rw = GFL2RoomWindow or package.loaded["GFL2RoomWindow"]
    if rw and rw.isOpen then
        return rw.isOpen() == true
    end
    return false
end

local function shouldDisplay()
    if not liveState.enabled or not currentPlayer or currentPlayer:isDead() then return false end
    if readyRoomVisible() then return false end
    if not (GFL2CharacterRuntime and GFL2CharacterRuntime.isReady and GFL2CharacterRuntime.isReady(currentPlayer)) then
        return false
    end
    return true
end

local function notifyPrefsChanged()
    if onUpdateCb then
        onUpdateCb(liveState)
    end
end

local function drawPadlockIcon(el, cx, cy, locked, alpha)
    if alpha <= 0.01 then return end
    local r = locked and 0.96 or 0.88
    local g = locked and 0.62 or 0.92
    local b = locked and 0.24 or 0.96
    if locked then
        el:drawRect(cx - 3, cy - 6, 6, 1, alpha, r, g, b)
        el:drawRect(cx - 3, cy - 5, 1, 4, alpha, r, g, b)
        el:drawRect(cx + 2, cy - 5, 1, 4, alpha, r, g, b)
    else
        el:drawRect(cx - 3, cy - 7, 6, 1, alpha, r, g, b)
        el:drawRect(cx - 3, cy - 6, 1, 5, alpha, r, g, b)
        el:drawRect(cx + 2, cy - 6, 1, 2, alpha, r, g, b)
    end
    el:drawRect(cx - 5, cy - 1, 10, 7, alpha, r, g, b)
    el:drawRect(cx - 1, cy + 1, 2, 3, alpha * 0.9, 0.08, 0.10, 0.13)
end

local function drawCloseIcon(el, cx, cy, alpha, hovered)
    if alpha <= 0.01 then return end
    local r = hovered and 0.98 or 0.86
    local g = hovered and 0.42 or 0.90
    local b = hovered and 0.42 or 0.94
    for d = -4, 4 do
        el:drawRect(cx + d, cy + d, 2, 1, alpha, r, g, b)
        el:drawRect(cx + d, cy - d, 2, 1, alpha, r, g, b)
    end
end

local function isAnyHovered()
    if panel and panel.mode ~= nil then return true end
    if panel and panel:isMouseOver() then return true end
    if lockBtn and lockBtn:isMouseOver() then return true end
    if closeBtn and closeBtn:getIsVisible() and closeBtn:isMouseOver() then return true end
    if vmdBtn and vmdBtn:getIsVisible() and vmdBtn:isMouseOver() then return true end
    if gripBtn and gripBtn:getIsVisible() and gripBtn:isMouseOver() then return true end
    if physBtn and physBtn:getIsVisible() and physBtn:isMouseOver() then return true end
    return false
end

local function syncOverlayPositions()
    if not panel then return end
    local x = math.floor(panel:getX() + 0.5)
    local y = math.floor(panel:getY() + 0.5)
    local w = math.floor(panel.width + 0.5)
    local visible = shouldDisplay()
    local unlocked = visible and liveState.lock ~= true
    -- lockBtn stays as a top-level UIManager button so it remains clickable even when panel is locked/click-through.
    if lockBtn then
        lockBtn:setX(x + w - 26)
        lockBtn:setY(y + 4)
        lockBtn:setVisible(visible)
    end
    -- closeBtn, vmdBtn, gripBtn, physBtn are real children of panel (local coords), so they ALWAYS draw on top of the 3D portrait and ALWAYS receive clicks before panel!
    if closeBtn then
        closeBtn:setX(4)
        closeBtn:setY(4)
        closeBtn:setVisible(unlocked)
    end
    local midStart = 30
    local midEnd = w - 30
    local gap = 3
    local avail = math.max(60, midEnd - midStart)
    local btnW = math.floor((avail - gap * 2) / 3)
    local rem = avail - btnW * 3 - gap * 2
    local btns = { vmdBtn, gripBtn, physBtn }
    local cx = midStart
    for i = 1, 3 do
        local b = btns[i]
        local bw = btnW + (i <= rem and 1 or 0)
        if b then
            b:setX(cx)
            b:setY(4)
            b:setWidth(bw)
            b:setHeight(22)
            b:setVisible(unlocked)
        end
        cx = cx + bw + gap
    end
end

local function makeQuickButton(parentPanel, labelKey, fallbackLabel, tipKey, onClick)
    local b = ISPanel:new(30, 4, 42, 22)
    b:initialise()
    b.background = false
    b.borderColor = { r = 0, g = 0, b = 0, a = 0 }
    b.backgroundColor = { r = 0, g = 0, b = 0, a = 0 }
    b.flashUntil = 0

    function b:render()
        if not shouldDisplay() or liveState.lock == true then return end
        local a = parentPanel and parentPanel.hoverAlpha or 0
        if a <= 0.01 then return end
        local now = getTimestampMs and getTimestampMs() or 0
        local flashing = now < (self.flashUntil or 0)
        local overSelf = self:isMouseOver()
        if flashing then
            self:drawRect(0, 0, self.width, self.height, 0.85 * a, 0.10, 0.24, 0.14)
            self:drawRectBorder(0, 0, self.width, self.height, 0.92 * a, 0.36, 0.92, 0.56)
        else
            self:drawRect(0, 0, self.width, self.height, (overSelf and 0.78 or 0.50) * a, 0.06, 0.08, 0.11)
            if overSelf then
                self:drawRectBorder(0, 0, self.width, self.height, 0.82 * a, 0.96, 0.58, 0.22)
            else
                self:drawRectBorder(0, 0, self.width, self.height, 0.36 * a, 0.80, 0.88, 0.94)
            end
        end
        local txt = textOr(labelKey, fallbackLabel)
        local tm = getTextManager and getTextManager()
        local fontH = (tm and tm.getFontHeight and tm:getFontHeight(UIFont.Small)) or 14
        local ty = math.floor((self.height - fontH) * 0.5)
        if self.setStencilRect then
            self:setStencilRect(1, 1, math.max(1, self.width - 2), math.max(1, self.height - 2))
        end
        if self.drawTextCentre then
            local tr = flashing and 0.52 or (overSelf and 0.98 or 0.90)
            local tg = flashing and 0.98 or (overSelf and 0.72 or 0.94)
            local tb = flashing and 0.64 or (overSelf and 0.36 or 0.96)
            self:drawTextCentre(txt, self.width * 0.5, ty, tr, tg, tb, 0.96 * a, UIFont.Small)
        end
        if self.clearStencilRect then self:clearStencilRect() end
        if overSelf and self.setTooltip then
            self:setTooltip(textOr(tipKey))
            if ISButton and ISButton.updateTooltip then ISButton.updateTooltip(self) end
        end
    end

    function b:onMouseDown(mx, my)
        if not shouldDisplay() or liveState.lock == true or not currentPlayer then return false end
        self.flashUntil = (getTimestampMs and getTimestampMs() or 0) + 320
        onClick(currentPlayer)
        return true
    end

    function b:onMouseUp(mx, my) return true end

    parentPanel:addChild(b)
    return b
end

local function makeButtons(parentPanel)
    local lb = ISPanel:new(0, 0, 22, 22)
    lb:initialise()
    lb.background = false
    lb.borderColor = { r = 0, g = 0, b = 0, a = 0 }
    lb.backgroundColor = { r = 0, g = 0, b = 0, a = 0 }
    lb.hoverFade = 0

    function lb:render()
        if not shouldDisplay() then return end
        local overSelf = self:isMouseOver()
        local locked = liveState.lock == true
        local target = 0
        if locked then
            -- Keep a subtle visible padlock icon while locked so the player always knows where to click to unlock!
            target = overSelf and 1.0 or 0.45
        else
            local panelAlpha = parentPanel and parentPanel.hoverAlpha or 0
            target = overSelf and 1.0 or panelAlpha
        end
        self.hoverFade = self.hoverFade + (target - self.hoverFade) * 0.28
        if self.hoverFade < 0.01 then self.hoverFade = 0 end
        if self.hoverFade <= 0.01 then return end

        local bgA = (overSelf and 0.78 or (locked and 0.36 or 0.48)) * self.hoverFade
        self:drawRect(0, 0, self.width, self.height, bgA, 0.06, 0.08, 0.11)
        if locked then
            self:drawRectBorder(0, 0, self.width, self.height, (overSelf and 0.95 or 0.70) * self.hoverFade, 0.96, 0.56, 0.20)
        else
            self:drawRectBorder(0, 0, self.width, self.height, (overSelf and 0.65 or 0.35) * self.hoverFade, 0.80, 0.88, 0.94)
        end
        drawPadlockIcon(self, 11, 11, locked, math.min(1, self.hoverFade * 1.15))

        if overSelf and self.setTooltip then
            self:setTooltip(textOr(locked and "ContextMenu_GFL2_PortraitLockOn" or "ContextMenu_GFL2_PortraitLockOff"))
            if ISButton and ISButton.updateTooltip then ISButton.updateTooltip(self) end
        end
    end

    function lb:onMouseDown(mx, my)
        if not shouldDisplay() then return false end
        liveState.lock = not (liveState.lock == true)
        if parentPanel then
            parentPanel.mode = nil
            parentPanel:setCapture(false)
            if liveState.lock then parentPanel.hoverAlpha = 0 end
            parentPanel:syncGeometry()
        end
        notifyPrefsChanged()
        return true
    end

    function lb:onMouseUp(mx, my) return true end

    lb:addToUIManager()
    if lb.setWantMouseEvents then lb:setWantMouseEvents(true) end

    local cb = ISPanel:new(4, 4, 22, 22)
    cb:initialise()
    cb.background = false
    cb.borderColor = { r = 0, g = 0, b = 0, a = 0 }
    cb.backgroundColor = { r = 0, g = 0, b = 0, a = 0 }

    function cb:render()
        if not shouldDisplay() or liveState.lock == true then return end
        local a = parentPanel and parentPanel.hoverAlpha or 0
        if a <= 0.01 then return end
        local overSelf = self:isMouseOver()
        local bgR = overSelf and 0.24 or 0.06
        local bgG = overSelf and 0.07 or 0.08
        local bgB = overSelf and 0.08 or 0.11
        self:drawRect(0, 0, self.width, self.height, (overSelf and 0.80 or 0.48) * a, bgR, bgG, bgB)
        if overSelf then
            self:drawRectBorder(0, 0, self.width, self.height, 0.78 * a, 0.96, 0.40, 0.40)
        else
            self:drawRectBorder(0, 0, self.width, self.height, 0.35 * a, 0.80, 0.88, 0.94)
        end
        drawCloseIcon(self, 11, 11, a, overSelf)
        if overSelf and self.setTooltip then
            self:setTooltip(textOr("ContextMenu_GFL2_PortraitCloseTip"))
            if ISButton and ISButton.updateTooltip then ISButton.updateTooltip(self) end
        end
    end

    function cb:onMouseDown(mx, my)
        if not shouldDisplay() or liveState.lock == true then return false end
        liveState.enabled = false
        notifyPrefsChanged()
        M.hide()
        return true
    end

    function cb:onMouseUp(mx, my) return true end

    parentPanel:addChild(cb)

    local vb = makeQuickButton(parentPanel, "UI_GFL2_PortraitBtnVmd", "VMD", "UI_GFL2_Radial_Motions", function(player)
        if GFL2CharacterActions and GFL2CharacterActions.open then
            GFL2CharacterActions.open(player, "motions")
        end
    end)

    local gb = makeQuickButton(parentPanel, "UI_GFL2_PortraitBtnGrip", "Grip", "UI_GFL2_Radial_Grip", function(player)
        if GFL2CharacterActions and GFL2CharacterActions.open then
            GFL2CharacterActions.open(player, "grip")
        end
    end)

    local pb = makeQuickButton(parentPanel, "UI_GFL2_PortraitBtnPhysics", "Phys", "UI_GFL2_Radial_ResetPhysics", function(player)
        if GFL2CharacterActions and GFL2CharacterActions.resetPhysics then
            GFL2CharacterActions.resetPhysics(player)
        end
    end)

    return lb, cb, vb, gb, pb
end

local function makePanel()
    local w, h = panelDimensions(liveState.size)
    local x, y = liveState.x, liveState.y
    if x < 0 or y < 0 then
        x, y = defaultPosition(w, h)
    else
        x, y = clampOnScreen(x, y, w, h)
    end

    local p = ISPanel:new(x, y, w, h)
    p:initialise()
    p.background = false
    p.borderColor = { r = 0, g = 0, b = 0, a = 0 }
    p.backgroundColor = { r = 0, g = 0, b = 0, a = 0 }
    p.anchorLeft = true
    p.anchorTop = true
    p.moveWithMouse = false
    p.mode = nil -- "move" | "resize" | "orbit"
    p.startMouseX = 0
    p.startMouseY = 0
    p.startX = x
    p.startY = y
    p.startSize = liveState.size
    p.startYaw = liveState.yaw
    p.startPan = liveState.pan
    p.hoverAlpha = 0
    p.lastHp = -1
    p.hitShake = 0
    p.lastFrameMs = getTimestampMs and getTimestampMs() or 0

    function p:syncGeometry()
        local nw, nh = panelDimensions(liveState.size)
        if math.floor(self.width + 0.5) ~= nw or math.floor(self.height + 0.5) ~= nh then
            self:setWidth(nw)
            self:setHeight(nh)
        end
        local tx, ty = liveState.x, liveState.y
        if tx < 0 or ty < 0 then
            tx, ty = defaultPosition(nw, nh)
        else
            tx, ty = clampOnScreen(tx, ty, nw, nh)
        end
        if math.floor(self:getX() + 0.5) ~= tx or math.floor(self:getY() + 0.5) ~= ty then
            self:setX(tx)
            self:setY(ty)
        end
        local wantMouse = liveState.lock ~= true and shouldDisplay()
        if self.setWantMouseEvents and self:isWantMouseEvents() ~= wantMouse then
            self:setWantMouseEvents(wantMouse)
        end
        syncOverlayPositions()
    end

    function p:onMouseDown(mx, my)
        if liveState.lock == true or not shouldDisplay() then return false end
        -- Ignore clicks on the top button strip so child/lock buttons handle them cleanly
        if my <= 28 then return false end
        self.startMouseX = getMouseX()
        self.startMouseY = getMouseY()
        self.startX = self:getX()
        self.startY = self:getY()
        self.startSize = liveState.size
        if mx >= self.width - 16 and my >= self.height - 16 then
            self.mode = "resize"
        else
            self.mode = "move"
        end
        self:setCapture(true)
        if lockBtn and lockBtn.bringToTop then lockBtn:bringToTop() end
        return true
    end

    function p:onRightMouseDown(mx, my)
        if liveState.lock == true or not shouldDisplay() then return false end
        local now = getTimestampMs and getTimestampMs() or 0
        if self.lastRightClickMs and (now - self.lastRightClickMs) < 320 then
            -- Double right-click quickly recenters angle & vertical framing!
            self.lastRightClickMs = 0
            liveState.yaw = 6
            liveState.pan = 0
            notifyPrefsChanged()
            return true
        end
        self.lastRightClickMs = now
        self.startMouseX = getMouseX()
        self.startMouseY = getMouseY()
        self.startYaw = liveState.yaw
        self.startPan = liveState.pan
        self.mode = "orbit"
        self:setCapture(true)
        if lockBtn and lockBtn.bringToTop then lockBtn:bringToTop() end
        return true
    end

    function p:onMouseMove(dx, dy)
        if not self.mode or liveState.lock == true then return false end
        local dmx = getMouseX() - self.startMouseX
        local dmy = getMouseY() - self.startMouseY
        if self.mode == "move" then
            local nx, ny = clampOnScreen(math.floor(self.startX + dmx + 0.5), math.floor(self.startY + dmy + 0.5), self.width, self.height)
            self:setX(nx)
            self:setY(ny)
            liveState.x = nx
            liveState.y = ny
            syncOverlayPositions()
        elseif self.mode == "resize" then
            local delta = math.max(dmx, dmy * 0.8)
            local ns = clampInt(self.startSize + delta, 160, 340, 200)
            liveState.size = ns
            local nw, nh = panelDimensions(ns)
            local nx, ny = clampOnScreen(math.floor(self:getX() + 0.5), math.floor(self:getY() + 0.5), nw, nh)
            self:setWidth(nw)
            self:setHeight(nh)
            self:setX(nx)
            self:setY(ny)
            liveState.x = nx
            liveState.y = ny
            syncOverlayPositions()
        elseif self.mode == "orbit" then
            liveState.yaw = clampInt(self.startYaw + dmx * 0.42, -45, 45, 6)
            liveState.pan = clampInt(self.startPan + dmy * 0.16, -25, 25, 0)
        end
        return true
    end

    function p:onMouseMoveOutside(dx, dy)
        return self:onMouseMove(dx, dy)
    end

    local function finishDrag(self)
        if not self.mode then return false end
        self.mode = nil
        self:setCapture(false)
        notifyPrefsChanged()
        return true
    end

    function p:onMouseUp(mx, my) return finishDrag(self) end
    function p:onMouseUpOutside(mx, my) return finishDrag(self) end
    function p:onRightMouseUp(mx, my) return finishDrag(self) end
    function p:onRightMouseUpOutside(mx, my) return finishDrag(self) end

    function p:onMouseWheel(delta)
        if liveState.lock == true or not shouldDisplay() then return false end
        local step = (delta < 0) and 12 or -12
        liveState.zoom = clampInt((tonumber(liveState.zoom) or 265) + step, 100, 340, 265)
        notifyPrefsChanged()
        return true
    end

    function p:prerender()
        if not shouldDisplay() then
            if self.setWantMouseEvents and self:isWantMouseEvents() then self:setWantMouseEvents(false) end
            syncOverlayPositions()
            return
        end
        self:syncGeometry()

        local now = getTimestampMs and getTimestampMs() or self.lastFrameMs
        local dt = clamp((now - self.lastFrameMs) / 1000.0, 0, 0.1)
        self.lastFrameMs = now

        local targetHover = (liveState.lock ~= true and isAnyHovered()) and 1 or 0
        local speed = targetHover > self.hoverAlpha and 9.0 or 6.5
        self.hoverAlpha = clamp(self.hoverAlpha + (targetHover - self.hoverAlpha) * math.min(1, dt * speed), 0, 1)
        if liveState.lock == true then self.hoverAlpha = 0 end

        local hp = 1.0
        if currentPlayer.getBodyDamage and currentPlayer:getBodyDamage() then
            hp = (currentPlayer:getBodyDamage():getHealth() or 100) / 100.0
        end
        if self.lastHp >= 0 and hp < self.lastHp - 0.005 then
            self.hitShake = 1.0
        else
            self.hitShake = math.max(0, self.hitShake - dt * 3.8)
        end
        self.lastHp = hp

        local ox = 0
        if self.hitShake > 0.01 then
            ox = math.floor(math.sin(now * 0.08) * 2.5 * self.hitShake + 0.5)
        end

        if self.hoverAlpha > 0.01 then
            local a = self.hoverAlpha
            self:drawRect(0, 0, self.width, self.height, 0.34 * a, 0.05, 0.07, 0.10)
            self:drawRectBorder(0, 0, self.width, self.height, 0.30 * a, 0.80, 0.88, 0.94)
        end

        if GFL2ReadyRoom and GFL2ReadyRoom.drawPortraitHud then
            local zoom = clamp((tonumber(liveState.zoom) or 265) / 100.0, 0.62, 4.2)
            local yaw = math.rad(clamp(tonumber(liveState.yaw) or 6, -45, 45))
            local pan = clamp((tonumber(liveState.pan) or 0) / 100.0, -0.25, 0.25)
            local focus = clamp((zoom - 1.05) / 1.15, 0, 1)
            local drawX = math.floor(self:getAbsoluteX() + 0.5) + ox
            local drawY = math.floor(self:getAbsoluteY() + 0.5)
            GFL2ReadyRoom.drawPortraitHud(
                currentPlayer,
                drawX, drawY,
                math.floor(self.width + 0.5), math.floor(self.height + 0.5),
                yaw, zoom, pan, focus, math.floor(tonumber(liveState.fps) or 24)
            )
        end
    end

    local function wrapHintLines(text, maxWidth)
        local tm = getTextManager and getTextManager()
        if not tm or not tm.MeasureStringX then return { text } end
        if tm:MeasureStringX(UIFont.Small, text) <= maxWidth then return { text } end
        local sep = " · "
        local parts = {}
        local start = 1
        while true do
            local s, e = string.find(text, sep, start, true)
            if not s then
                parts[#parts + 1] = string.sub(text, start)
                break
            end
            parts[#parts + 1] = string.sub(text, start, s - 1)
            start = e + 1
        end
        if #parts <= 1 then return { text } end
        local lines, cur = {}, parts[1]
        for i = 2, #parts do
            local candidate = cur .. sep .. parts[i]
            if tm:MeasureStringX(UIFont.Small, candidate) <= maxWidth then
                cur = candidate
            else
                lines[#lines + 1] = cur
                cur = parts[i]
            end
        end
        lines[#lines + 1] = cur
        return lines
    end

    function p:render()
        if not shouldDisplay() or liveState.lock == true or self.hoverAlpha <= 0.05 then return end
        if self.mode ~= nil then return end
        local a = self.hoverAlpha
        local hint = textOr("UI_GFL2_PortraitHoverHint", "Drag · Right-Drag · Scroll")
        local tm = getTextManager and getTextManager()
        local fontH = (tm and tm.getFontHeight and tm:getFontHeight(UIFont.Small)) or 15
        local innerW = math.max(24, self.width - 12)
        local lines = wrapHintLines(hint, innerW - 6)
        local padY = 3
        local boxY = 30
        local boxH = #lines * fontH + padY * 2
        if boxY + boxH > self.height - 6 then return end
        if self.setStencilRect then
            self:setStencilRect(2, 2, math.max(1, self.width - 4), math.max(1, self.height - 4))
        end
        self:drawRect(4, boxY, self.width - 8, boxH, 0.50 * a, 0.04, 0.06, 0.08)
        self:drawRectBorder(4, boxY, self.width - 8, boxH, 0.24 * a, 0.80, 0.88, 0.94)
        if self.drawTextCentre then
            for i = 1, #lines do
                self:drawTextCentre(lines[i], self.width / 2, boxY + padY + (i - 1) * fontH, 0.88, 0.92, 0.95, 0.92 * a, UIFont.Small)
            end
        end
        if self.clearStencilRect then self:clearStencilRect() end
    end

    p:addToUIManager()
    return p
end

function M.sync(player, prefs, onUpdate)
    currentPlayer = player
    if onUpdate then onUpdateCb = onUpdate end
    if type(prefs) == "table" then
        liveState.enabled = prefs.portraitHud == true
        liveState.size = clampInt(prefs.portraitSize, 160, 340, 200)
        liveState.zoom = clampInt(prefs.portraitZoom, 100, 340, 265)
        liveState.yaw = clampInt(prefs.portraitYaw, -45, 45, 6)
        liveState.pan = clampInt(prefs.portraitPan, -25, 25, 0)
        liveState.lock = prefs.portraitLock == true
        liveState.fps = clampInt(prefs.portraitFps, 0, 60, 24)
        liveState.x = clampInt(prefs.portraitX, -1, 16384, -1)
        liveState.y = clampInt(prefs.portraitY, -1, 16384, -1)
    end

    if not liveState.enabled or not player or player:isDead() then
        M.hide()
        return
    end
    if not panel then
        panel = makePanel()
        lockBtn, closeBtn, vmdBtn, gripBtn, physBtn = makeButtons(panel)
        panel:syncGeometry()
        if lockBtn and lockBtn.bringToTop then lockBtn:bringToTop() end
    else
        panel:setVisible(true)
        panel:syncGeometry()
        if lockBtn and lockBtn.bringToTop then lockBtn:bringToTop() end
    end
end

function M.hide()
    if lockBtn then
        lockBtn:removeFromUIManager()
        lockBtn = nil
    end
    physBtn = nil
    gripBtn = nil
    vmdBtn = nil
    closeBtn = nil
    if panel then
        panel.mode = nil
        panel:setCapture(false)
        panel:removeFromUIManager()
        panel = nil
    end
    if GFL2ReadyRoom and GFL2ReadyRoom.releasePortraitHud then
        GFL2ReadyRoom.releasePortraitHud()
    end
end

return M
