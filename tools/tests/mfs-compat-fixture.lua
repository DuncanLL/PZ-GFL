local events = {}
local function makeEvent()
    local list = {}
    return {
        Add = function(fn) list[#list + 1] = fn end,
        fire = function(...)
            for i = 1, #list do list[i](...) end
        end,
    }
end
Events = {
    OnGameBoot = makeEvent(),
    OnInitGlobalModData = makeEvent(),
    OnGameStart = makeEvent(),
    OnCreatePlayer = makeEvent(),
    OnEquipPrimary = makeEvent(),
}

ISInsertMagazine = { isValid = function(self) return true end }
ISLoadBulletsInMagazine = {
    new = function(self, character, magazine, ammoCount)
        return { character = character, magazine = magazine, ammoCount = ammoCount, stopOnAim = false }
    end,
    update = function(self) end,
}
ISUnloadBulletsFromMagazine = {
    new = function(self, character, magazine)
        return { character = character, magazine = magazine, stopOnAim = false }
    end,
    update = function(self) end,
}
function getActivatedMods()
    return { contains = function(_, id) return id == "ModernFirearmsSystem" end }
end
function instanceItem(fullType)
    if fullType == "GFL2.ScyllaMagazine" then
        return { getFullType = function() return fullType end, getMaxAmmo = function() return 30 end }
    end
    return { getFullType = function() return fullType end, getMaxAmmo = function() return 60 end }
end

AWCWF_AllowModDataTable = { weaponpart = true }
local changeCalls = {}
local function mfsChangeImpl(playerObj, gun, magType, tag, need)
    changeCalls[#changeCalls + 1] = { gun = gun:getFullType(), mag = magType, tag = tag }
    gun:setMagazineType(magType)
    gun:getModData().MagzineTypeNow = magType
end
ChangeMagzine = mfsChangeImpl
-- Simulate MFSMagazineSyncFix registering OnGameStart BEFORE ZZ_GFL2ModernFirearmsCompat loads.
Events.OnGameStart.Add(function()
    ChangeMagzine = mfsChangeImpl
end)

local function makeGun(fullType, magType, maxAmmo)
    local modData = { MagzineTypeNow = magType }
    local state = { mag = magType, max = maxAmmo }
    return {
        getFullType = function() return fullType end,
        getMagazineType = function() return state.mag end,
        setMagazineType = function(_, m) state.mag = m end,
        getMaxAmmo = function() return state.max end,
        setMaxAmmo = function(_, n) state.max = n end,
        getModData = function() return modData end,
    }
end

function verifyMfsCompat()
    Events.OnGameStart.fire()
    assert(AWCWF_AllowModDataTable.GFL2WeaponSkin == true, "GFL2WeaponSkin must be preserved in MFS AllowModDataTable")

    local scylla = makeGun("GFL2.Scylla", "GFL2.ScyllaMagazine", 30)
    local hk416 = makeGun("Base.HK416_cat", "Base.556Clip", 30)

    -- MFS inspect UI attempting to swap Scylla to an MFS 60-round drum must be blocked.
    ChangeMagzine({}, scylla, "Base.556Drum_cat", nil, true)
    assert(#changeCalls == 0)
    assert(scylla:getMagazineType() == "GFL2.ScyllaMagazine")
    assert(scylla:getModData().MagzineTypeNow == "GFL2.ScyllaMagazine")

    -- MFS inspect UI attempting to swap an MFS rifle to a GFL2 dedicated magazine must be blocked.
    ChangeMagzine({}, hk416, "GFL2.ScyllaMagazine", nil, true)
    assert(#changeCalls == 0)
    assert(hk416:getMagazineType() == "Base.556Clip")

    -- Normal MFS gun -> MFS drum swap must continue working untouched.
    ChangeMagzine({}, hk416, "Base.556Drum_cat", nil, true)
    assert(#changeCalls == 1 and hk416:getMagazineType() == "Base.556Drum_cat")

    -- Existing corrupted save weapon self-heals on OnEquipPrimary.
    local broken = makeGun("GFL2.Scylla", "Base.556Drum_cat", 60)
    Events.OnEquipPrimary.fire({}, broken)
    assert(broken:getMagazineType() == "GFL2.ScyllaMagazine" and broken:getMaxAmmo() == 30)
    assert(broken:getModData().MagzineTypeNow == "GFL2.ScyllaMagazine")

    return "PASS ModernFirearmsSystem magazine isolation, self-heal, and skin modData preservation"
end
