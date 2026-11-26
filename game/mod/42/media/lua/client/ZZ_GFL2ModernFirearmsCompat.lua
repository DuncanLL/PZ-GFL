-- Third-party compatibility guard for:
--   Mod: Modern Firearms System (MFS) | Mod ID: ModernFirearmsSystem
--   Conflict & Root Cause: MFS's ChangeMagzine swaps GFL2 signature weapons to generic magazines
--     and strips custom modData keys not listed in AWCWF_AllowModDataTable.
--   Hook Points: Wraps global ChangeMagzine, registers AWCWF_AllowModDataTable["GFL2WeaponSkin"],
--     and repairs dedicated magazines on OnEquipPrimary.
--   Removal: Delete this file, tools/tests/mfs-compat-fixture.lua, and usesMFS branches in
--     GFL2SignatureMagazines.lua / GFL2RoomEconomy.lua; update docs/MOD_COMPATIBILITY.md.

local activeMods = getActivatedMods and getActivatedMods()
if not activeMods or not activeMods:contains("ModernFirearmsSystem") then return end

local magazines = require "GFL2SignatureMagazines"

local function repairDedicatedMagazine(weapon)
    local dedicated = magazines.forWeapon(weapon)
    if not dedicated then return end
    local data = weapon.getModData and weapon:getModData()
    if data and data.MagzineTypeNow ~= nil and data.MagzineTypeNow ~= dedicated then
        data.MagzineTypeNow = dedicated
    end
    if weapon.getMagazineType and weapon:getMagazineType() ~= dedicated then
        local mag = instanceItem and instanceItem(dedicated)
        weapon:setMagazineType(dedicated)
        if mag and mag.getMaxAmmo and weapon.setMaxAmmo then
            weapon:setMaxAmmo(mag:getMaxAmmo())
        end
    end
end

local wrappedOriginal, wrappedGuard
local function installGuard()
    if type(AWCWF_AllowModDataTable) == "table" then
        AWCWF_AllowModDataTable["GFL2WeaponSkin"] = true
    end
    if type(ChangeMagzine) == "function" and ChangeMagzine ~= wrappedGuard then
        wrappedOriginal = ChangeMagzine
        wrappedGuard = function(playerObj, mainGun, magazineType, tag, need)
            local dedicated = magazines.forWeapon(mainGun)
            if dedicated then
                repairDedicatedMagazine(mainGun)
                if magazineType ~= dedicated then return end
            elseif magazines.ownerOf(magazineType) then
                return
            end
            return wrappedOriginal(playerObj, mainGun, magazineType, tag, need)
        end
        ChangeMagzine = wrappedGuard
    end
end

local function onEquipPrimary(_, weapon)
    repairDedicatedMagazine(weapon)
end

installGuard()
Events.OnGameStart.Add(installGuard)
if Events.OnCreatePlayer then
    Events.OnCreatePlayer.Add(installGuard)
end
Events.OnEquipPrimary.Add(onEquipPrimary)
