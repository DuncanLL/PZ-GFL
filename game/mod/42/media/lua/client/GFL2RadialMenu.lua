require "ISUI/ISEmoteRadialMenu"

GFL2RadialMenu = GFL2RadialMenu or {}

local ART = "media/ui/GFL2ReadyRoom/"
local textures = {}

local function icon(name)
    if not name or not getTexture then return nil end
    local path = ART .. name
    if textures[path] == nil then
        textures[path] = getTexture(path) or false
    end
    return textures[path] or nil
end

local function label(key, fallback)
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

local function isCharacterActive(player)
    return player ~= nil
        and GFL2CharacterRuntime ~= nil
        and GFL2CharacterRuntime.isEnabled ~= nil
        and GFL2CharacterRuntime.isEnabled(player) == true
end

local function isBodyPhysicsOn(player)
    return isCharacterActive(player)
        and GFL2CharacterRuntime.modelPhysicsEnabled ~= nil
        and GFL2CharacterRuntime.modelPhysicsEnabled(player) == true
end

local function runOpen(player, target)
    if not player or not GFL2CharacterActions or not GFL2CharacterActions.open then return end
    GFL2CharacterActions.open(player, target)
end

local function runResetPhysics(player)
    if not isCharacterActive(player) then return end
    if GFL2CharacterActions and GFL2CharacterActions.resetPhysics then
        GFL2CharacterActions.resetPhysics(player)
    end
end

local function runTogglePhysics(player)
    if not isCharacterActive(player) then return end
    if GFL2CharacterActions and GFL2CharacterActions.setBodyPhysics then
        GFL2CharacterActions.setBodyPhysics(player, not isBodyPhysicsOn(player))
    end
end

function GFL2RadialMenu.fillSubmenu(emoteMenu)
    if not emoteMenu or not emoteMenu.character then return end
    local player = emoteMenu.character
    local menu = getPlayerRadialMenu(emoteMenu.playerNum)
    if not menu then return end
    menu:clear()

    local active = isCharacterActive(player)
    menu:addSlice(label("UI_GFL2_Radial_ReadyRoom", "Ready Room"), icon("radial-room.png"), runOpen, player, "overview")
    if active then
        menu:addSlice(label("UI_GFL2_Radial_ResetPhysics", "Reset Physics"), icon("radial-reset-physics.png"), runResetPhysics, player)
        local physOn = isBodyPhysicsOn(player)
        local physKey = physOn and "UI_GFL2_Radial_PhysicsOn" or "UI_GFL2_Radial_PhysicsOff"
        local physFallback = physOn and "Cloth & Hair: On" or "Cloth & Hair: Off"
        menu:addSlice(label(physKey, physFallback), icon("radial-physics.png"), runTogglePhysics, player)
        menu:addSlice(label("UI_GFL2_Radial_Motions", "VMD Motions"), icon("radial-motion.png"), runOpen, player, "motions")
        menu:addSlice(label("UI_GFL2_Radial_Grip", "Grip Calibration"), icon("radial-grip.png"), runOpen, player, "grip")
    end
    menu:addSlice(label("UI_GFL2_Radial_Settings", "Character Settings"), icon("room-settings.png"), runOpen, player, "settings")
    local backIcon = ISEmoteRadialMenu and ISEmoteRadialMenu.icons and ISEmoteRadialMenu.icons["back"] or nil
    menu:addSlice(getText("IGUI_Emote_Back"), backIcon, emoteMenu.fillMenu, emoteMenu)

    emoteMenu:display()
end

function GFL2RadialMenu.install()
    if GFL2RadialMenu._installed then return end
    if not ISEmoteRadialMenu or not ISEmoteRadialMenu.fillMenu then return end
    GFL2RadialMenu._installed = true
    local baseFillMenu = ISEmoteRadialMenu.fillMenu
    ISEmoteRadialMenu.fillMenu = function(self, submenu)
        baseFillMenu(self, submenu)
        if submenu then return end
        if not self or not self.character or self.character:isDead() then return end
        if not GFL2CharacterRuntime then
            local showHelp = GFL2CharacterActions and GFL2CharacterActions.showJavaHelp
            if showHelp then
                local menu = getPlayerRadialMenu(self.playerNum)
                if menu then
                    menu:addSlice(label("UI_GFL2_Radial_Root", "GFL2 Exilium"), icon("radial-root.png"), showHelp, self.character)
                end
            end
            return
        end
        local menu = getPlayerRadialMenu(self.playerNum)
        if not menu then return end
        menu:addSlice(label("UI_GFL2_Radial_Root", "GFL2 Exilium"), icon("radial-root.png"), GFL2RadialMenu.fillSubmenu, self)
    end
end

if Events and Events.OnGameStart then
    Events.OnGameStart.Add(GFL2RadialMenu.install)
end

return GFL2RadialMenu
