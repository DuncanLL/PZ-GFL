local catalog = require "GFL2SignatureWeapon"
local M = {}
local owners = {}
for id, entry in pairs(catalog.byId) do
    if entry.startsWithMagazine then owners[id .. "Magazine"] = id end
end

function M.forWeapon(weapon)
    if not weapon or not weapon.getFullType then return end
    local entry = catalog.byId[weapon:getFullType()]
    if entry and entry.startsWithMagazine then return entry.id .. "Magazine" end
end

function M.ownerOf(magazineType)
    return type(magazineType) == "string" and owners[magazineType] or nil
end

local activeMods = getActivatedMods and getActivatedMods()
if activeMods and activeMods:contains("SWMG") then
    local Magazine = require "WeaponSystems/Utils/Magazine"
    local usesEFZ = activeMods:contains("EscapeFromZomboidFirearms")
    local usesMFS = activeMods:contains("ModernFirearmsSystem")
    local usesNewGOM = not usesEFZ and not usesMFS and activeMods:contains("GunsOfMarz")
    local usesOldGOM = not usesEFZ and not usesMFS and not usesNewGOM and activeMods:contains("MarzGuns")
    local usesGOM = usesNewGOM or usesOldGOM
    local Ammo = (usesEFZ or usesGOM) and require "WeaponSystems/Utils/Ammo"

    function M.register(weaponId, donorId, ammoFamily, ammoBox)
        local magazineId = weaponId .. "Magazine"
        local function configure()
            local scripts = getScriptManager()
            local weapon = scripts:FindItem(weaponId)
            local magazine = scripts:FindItem(magazineId)
            if not weapon or not magazine then return end

            Magazine.RegisterMagazineProfile(weaponId, { magazineId })
            Magazine.RegisterWeaponWithProfile(weaponId, weaponId)
            if not usesEFZ then return end

            local donor = scripts:FindItem(donorId)
            local ammoType = donor and donor:getAmmoType()
            if not ammoType then return end
            weapon:setAmmoType(ammoType)
            magazine:setAmmoType(ammoType)
            weapon:DoParam("AmmoBox", ammoBox)
            Ammo.RegisterItemWithFamily(ammoFamily, { weaponId, magazineId })
        end
        Events.OnGameBoot.Add(configure)
        Events.OnInitGlobalModData.Add(configure)
    end

    if usesGOM then
        local gomAmmoMap = {
            ["Base.556Box"] = { key = "BULLET_556x45", family = "5.56x45mm", box = "MarzGuns.556x45_Box" },
            ["Base.308Box"] = { key = "BULLET_308", family = "7.62x51mm", box = "MarzGuns.308_Box" },
            ["Base.Bullets9mmBox"] = { key = "BULLET_9x19", family = "9x19mm", box = "MarzGuns.9x19_Box" },
            ["Base.Bullets45Box"] = { key = "BULLET_45", family = ".45 ACP", box = "MarzGuns.45_Box" },
            ["Base.Bullets44Box"] = { key = "BULLET_44", family = ".44 Magnum", box = "MarzGuns.44_Box" },
            ["Base.ShotgunShellsBox"] = { key = "SHELL_12G_BUCKSHOT", family = "12Gauge", box = "MarzGuns.12Gauge_Box_Buckshot" },
        }

        local function configureGunsOfMarz()
            local scripts = getScriptManager and getScriptManager()
            if not scripts then return end
            local ammoTable = (usesNewGOM and SWMG_AmmoTypes) or (usesOldGOM and MarzGuns_AmmoTypes)
            for weaponId, entry in pairs(catalog.byId) do
                local spec = entry.ammoBox and gomAmmoMap[entry.ammoBox]
                local weapon = spec and scripts:FindItem(weaponId)
                if weapon then
                    local ammoEnum = ammoTable and ammoTable[spec.key]
                    if ammoEnum then
                        weapon:setAmmoType(ammoEnum)
                    end
                    if scripts:FindItem(spec.box) then
                        weapon:DoParam("AmmoBox", spec.box)
                    end
                    if entry.startsWithMagazine then
                        local magazineId = weaponId .. "Magazine"
                        local magazine = scripts:FindItem(magazineId)
                        if magazine then
                            Magazine.RegisterMagazineProfile(weaponId, { magazineId })
                            Magazine.RegisterWeaponWithProfile(weaponId, weaponId)
                            if ammoEnum then
                                magazine:setAmmoType(ammoEnum)
                            end
                            Ammo.RegisterItemWithFamily(spec.family, { weaponId, magazineId })
                        else
                            Ammo.RegisterItemWithFamily(spec.family, weaponId)
                        end
                    else
                        Ammo.RegisterItemWithFamily(spec.family, weaponId)
                    end
                end
            end
        end

        Events.OnGameBoot.Add(configureGunsOfMarz)
        Events.OnInitGlobalModData.Add(configureGunsOfMarz)
    end

    local restoreMagazine = Magazine.RestoreMagazineType
    function Magazine.RestoreMagazineType(weapon)
        local dedicated = M.forWeapon(weapon)
        if not dedicated then return restoreMagazine(weapon) end
        local data = weapon:getModData()
        if weapon:getMagazineType() == dedicated and
                (not data.MagazineType or data.MagazineType == dedicated) then return end
        local magazine = instanceItem(dedicated)
        if not magazine then return end
        -- Keep the loaded rounds and chamber state while migrating the saved magazine type.
        weapon:setMagazineType(dedicated)
        weapon:setMaxAmmo(magazine:getMaxAmmo())
        if weapon:isContainsClip() then
            Magazine.SaveMagazineType(weapon, dedicated)
        else
            Magazine.ClearMagazineType(weapon)
            data.MagazineTypeLastIndex = nil
        end
    end
else
    function M.register() end
end

local usesXanny = activeMods
    and activeMods:contains("XannyArmsGunPack")
    and not activeMods:contains("EscapeFromZomboidFirearms")
    and not activeMods:contains("ModernFirearmsSystem")
    and not activeMods:contains("GunsOfMarz")
    and not activeMods:contains("MarzGuns")

if usesXanny then
    local xannyAmmoMap = {
        ["Base.556Box"] = { box = "XannyArms.XA_556x45Box" },
        ["Base.308Box"] = { box = "XannyArms.XA_762x51Box", donors = { "XannyArms.XA_Remington700", "XannyArms.XA_HK417_Mag" } },
        ["Base.Bullets9mmBox"] = { box = "XannyArms.XA_9x19Box" },
        ["Base.Bullets45Box"] = { box = "XannyArms.XA_45Box" },
        ["Base.Bullets44Box"] = { box = "XannyArms.XA_50AEBox", donors = { "XannyArms.XA_DesertEagle", "XannyArms.XA_DesertEagle_Mag" } },
        ["Base.ShotgunShellsBox"] = { box = "XannyArms.XA_12GaugeBox" },
    }

    local function resolveXannyAmmoType(scripts, donors)
        if not donors then return nil end
        for i = 1, #donors do
            local donor = scripts:FindItem(donors[i])
            local ammoType = donor and donor:getAmmoType()
            if ammoType then return ammoType end
        end
        return nil
    end

    local function configureXannyArms()
        local scripts = getScriptManager and getScriptManager()
        if not scripts then return end
        for weaponId, entry in pairs(catalog.byId) do
            local spec = entry.ammoBox and xannyAmmoMap[entry.ammoBox]
            local weapon = spec and scripts:FindItem(weaponId)
            if weapon then
                local ammoType = resolveXannyAmmoType(scripts, spec.donors)
                if ammoType then
                    weapon:setAmmoType(ammoType)
                end
                if scripts:FindItem(spec.box) then
                    weapon:DoParam("AmmoBox", spec.box)
                end
                if entry.startsWithMagazine and ammoType then
                    local magazine = scripts:FindItem(weaponId .. "Magazine")
                    if magazine then
                        magazine:setAmmoType(ammoType)
                    end
                end
            end
        end
    end

    local function syncXannyItem(item)
        if not item or not item.getFullType then return end
        local fullType = item:getFullType()
        local ownerId = M.ownerOf(fullType)
        local entry = catalog.byId[ownerId or fullType]
        if not entry or not entry.ammoBox then return end
        local spec = xannyAmmoMap[entry.ammoBox]
        if not spec then return end
        local scripts = getScriptManager and getScriptManager()
        if not scripts then return end
        local ammoType = resolveXannyAmmoType(scripts, spec.donors)
        if ammoType and item.getAmmoType and item.setAmmoType and item:getAmmoType() ~= ammoType then
            item:setAmmoType(ammoType)
        end
        if not ownerId and item.setAmmoBox and scripts:FindItem(spec.box) then
            if not item.getAmmoBox or item:getAmmoBox() ~= spec.box then
                item:setAmmoBox(spec.box)
            end
        end
    end

    local function syncXannyContainer(container, depth)
        if not container or not container.getItems then return end
        depth = depth or 0
        local items = container:getItems()
        if not items then return end
        for i = 0, items:size() - 1 do
            local item = items:get(i)
            syncXannyItem(item)
            if depth < 2 and item and item.IsInventoryContainer and item:IsInventoryContainer() and item.getInventory then
                syncXannyContainer(item:getInventory(), depth + 1)
            end
        end
    end

    Events.OnGameBoot.Add(configureXannyArms)
    Events.OnInitGlobalModData.Add(configureXannyArms)
    if Events.OnEquipPrimary then
        Events.OnEquipPrimary.Add(function(_, item) syncXannyItem(item) end)
    end
    if Events.OnGameStart then
        Events.OnGameStart.Add(function()
            configureXannyArms()
            local player = getSpecificPlayer and getSpecificPlayer(0)
            if player and player.getInventory then
                syncXannyContainer(player:getInventory(), 0)
            end
        end)
    end
end

require "TimedActions/ISInsertMagazine"
require "TimedActions/ISLoadBulletsInMagazine"
require "TimedActions/ISUnloadBulletsFromMagazine"

local isValid = ISInsertMagazine.isValid
function ISInsertMagazine:isValid()
    local dedicated = M.forWeapon(self.gun)
    if dedicated and self.magazine and self.magazine:getFullType() ~= dedicated then return false end
    return isValid(self)
end

-- Vanilla ISLoadBulletsInMagazine / ISUnloadBulletsFromMagazine keep stopOnAim = false and
-- maxTime = -1 while overriding primaryHandModel = nil and secondaryHandModel = "GunMagazine".
-- Aiming a firearm preempts the InsertBullets animation so loadFinished never fires, leaving
-- the firearm invisible and the magazine stuck in the left hand indefinitely.
local loadMagNew = ISLoadBulletsInMagazine.new
function ISLoadBulletsInMagazine:new(character, magazine, ammoCount, ...)
    local o = loadMagNew(self, character, magazine, ammoCount, ...)
    o.stopOnAim = true
    return o
end

local loadMagUpdate = ISLoadBulletsInMagazine.update
function ISLoadBulletsInMagazine:update()
    if self.character and self.character:isAiming() then
        self:setOverrideHandModels(nil, nil)
        self:forceStop()
        return
    end
    if not self.loadFinished and self:isLoadFinished() then
        local dt = GameTime and GameTime.getInstance() and GameTime.getInstance():getRealworldSecondsSinceLastUpdate() or 0.016
        self._gfl2FinishWait = (self._gfl2FinishWait or 0) + dt
        if self._gfl2FinishWait >= 0.65 then
            self.loadFinished = true
        end
    else
        self._gfl2FinishWait = nil
    end
    return loadMagUpdate(self)
end

local unloadMagNew = ISUnloadBulletsFromMagazine.new
function ISUnloadBulletsFromMagazine:new(character, magazine, ...)
    local o = unloadMagNew(self, character, magazine, ...)
    o.stopOnAim = true
    return o
end

local unloadMagUpdate = ISUnloadBulletsFromMagazine.update
function ISUnloadBulletsFromMagazine:update()
    if self.character and self.character:isAiming() then
        self:setOverrideHandModels(nil, nil)
        self:forceStop()
        return
    end
    if not self.unloadFinished and self.magazine and self.magazine:getCurrentAmmoCount() <= 0 then
        local dt = GameTime and GameTime.getInstance() and GameTime.getInstance():getRealworldSecondsSinceLastUpdate() or 0.016
        self._gfl2FinishWait = (self._gfl2FinishWait or 0) + dt
        if self._gfl2FinishWait >= 0.65 then
            self.unloadFinished = true
        end
    else
        self._gfl2FinishWait = nil
    end
    return unloadMagUpdate(self)
end

return M
