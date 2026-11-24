-- Regression assertions for Guns of Marz (new GunsOfMarz & legacy MarzGuns),
-- automatic cadence (including ThresholdStorm), and JAR-missing self-check dialog.

local function makeEvent()
    local list = {}
    return {
        Add = function(fn) list[#list + 1] = fn end,
        fire = function(...)
            for i = 1, #list do list[i](...) end
        end,
    }
end

local function makeScriptItem(fullType)
    local state = { fullType = fullType, ammoType = "base:initial", params = {} }
    return {
        state = state,
        getFullType = function() return fullType end,
        getAmmoType = function() return state.ammoType end,
        setAmmoType = function(_, v) state.ammoType = v end,
        DoParam = function(_, k, v) state.params[k] = v end,
    }
end

local function runGoMScenario(activeModList, ammoTableName, runGfl2Magazines, runGfl2Cadence)
    local active = {}
    for _, id in ipairs(activeModList) do active[id] = true end

    Events = {
        OnGameBoot = makeEvent(),
        OnInitGlobalModData = makeEvent(),
    }
    ISInsertMagazine = { isValid = function() return true end }

    local registeredProfiles = {}
    local weaponProfiles = {}
    local itemFamilies = {}
    local rpmProfiles = {}
    local modifierLayers = {}

    _G["WeaponSystems/Utils/Magazine"] = {
        RegisterMagazineProfile = function(name, mags) registeredProfiles[name] = mags end,
        RegisterWeaponWithProfile = function(name, w) weaponProfiles[w] = name end,
        RestoreMagazineType = function() end,
    }
    _G["WeaponSystems/Utils/Ammo"] = {
        RegisterItemWithFamily = function(family, items)
            if type(items) == "table" then
                for _, id in ipairs(items) do itemFamilies[id] = family end
            else
                itemFamilies[items] = family
            end
        end,
    }
    _G["WeaponSystems/Utils/RateOfFire"] = {
        RegisterWeapon = function(w, cfg) rpmProfiles[w] = cfg end,
    }
    _G["WeaponSystems/Utils/StatsFactory"] = {
        Set = function(stat, val) return { stat = stat, val = val } end,
        RegisterModifierLayer = function(name, fn, restore, pred)
            modifierLayers[name] = { fn = fn, restore = restore, pred = pred }
        end,
    }

    local sampleIds = {
        "GFL2.Scylla", "GFL2.ScyllaMagazine",
        "GFL2.ThresholdStorm", "GFL2.ThresholdStormMagazine",
        "GFL2.Iaso", "GFL2.IasoMagazine",
        "GFL2.Schlitzohr", "GFL2.SchlitzohrMagazine",
        "GFL2.SilverWingedPistis", "GFL2.SilverWingedPistisMagazine",
        "GFL2.Sibyl", "GFL2.SibylMagazine",
        "GFL2.Trailblazer", "GFL2.GaiaTerminus", "GFL2.Radiance",
        "GFL2.CauseAndEffect", "GFL2.TwilightRose",
        "MarzGuns.556x45_Box", "MarzGuns.308_Box", "MarzGuns.9x19_Box",
        "MarzGuns.45_Box", "MarzGuns.44_Box", "MarzGuns.12Gauge_Box_Buckshot",
    }
    local scripts = {}
    for _, id in ipairs(sampleIds) do scripts[id] = makeScriptItem(id) end

    getActivatedMods = function()
        return { contains = function(_, id) return active[id] == true end }
    end
    getScriptManager = function()
        return { FindItem = function(_, id) return scripts[id] end }
    end

    SWMG_AmmoTypes = nil
    MarzGuns_AmmoTypes = nil
    if ammoTableName == "SWMG_AmmoTypes" then
        SWMG_AmmoTypes = {
            BULLET_556x45 = "swmg:bullet_556x45",
            BULLET_308 = "swmg:bullet_308",
            BULLET_9x19 = "swmg:bullet_9x19",
            BULLET_45 = "swmg:bullet_45",
            BULLET_44 = "swmg:bullet_44",
            SHELL_12G_BUCKSHOT = "swmg:shell_12g_buckshot",
        }
    elseif ammoTableName == "MarzGuns_AmmoTypes" then
        MarzGuns_AmmoTypes = {
            BULLET_556x45 = "marzguns:bullet_556x45",
            BULLET_308 = "marzguns:bullet_308",
            BULLET_9x19 = "marzguns:bullet_9x19",
            BULLET_45 = "marzguns:bullet_45",
            BULLET_44 = "marzguns:bullet_44",
            SHELL_12G_BUCKSHOT = "marzguns:shell_12g_buckshot",
        }
    end

    runGfl2Magazines()
    runGfl2Cadence()
    Events.OnGameBoot.fire()
    Events.OnInitGlobalModData.fire()

    return scripts, registeredProfiles, weaponProfiles, itemFamilies, rpmProfiles, modifierLayers
end

function verifyGoMAndJavaHelp(runGfl2Magazines, runGfl2Cadence, runRuntime, runRadial)
    -- 1. New GunsOfMarz (SWMG + GunsOfMarz): all 6 calibers + mag & non-mag guns + melee untouched.
    local scripts, regProfs, weapProfs, families, rpms, layers =
        runGoMScenario({ "SWMG", "GunsOfMarz" }, "SWMG_AmmoTypes", runGfl2Magazines, runGfl2Cadence)

    assert(scripts["GFL2.Scylla"].state.ammoType == "swmg:bullet_556x45")
    assert(scripts["GFL2.ScyllaMagazine"].state.ammoType == "swmg:bullet_556x45")
    assert(scripts["GFL2.Scylla"].state.params.AmmoBox == "MarzGuns.556x45_Box")
    assert(families["GFL2.Scylla"] == "5.56x45mm" and families["GFL2.ScyllaMagazine"] == "5.56x45mm")
    assert(weapProfs["GFL2.Scylla"] == "GFL2.Scylla" and regProfs["GFL2.Scylla"][1] == "GFL2.ScyllaMagazine")

    assert(scripts["GFL2.ThresholdStorm"].state.ammoType == "swmg:bullet_556x45")
    assert(scripts["GFL2.ThresholdStormMagazine"].state.ammoType == "swmg:bullet_556x45")
    assert(rpms["GFL2.ThresholdStorm"] and rpms["GFL2.ThresholdStorm"].rpm == 700)
    assert(layers.GFL2AutomaticCadence.pred({ getFullType = function() return "GFL2.ThresholdStorm" end }))

    assert(scripts["GFL2.Iaso"].state.ammoType == "swmg:bullet_9x19" and families["GFL2.IasoMagazine"] == "9x19mm")
    assert(scripts["GFL2.Schlitzohr"].state.ammoType == "swmg:bullet_45" and families["GFL2.SchlitzohrMagazine"] == ".45 ACP")
    assert(scripts["GFL2.SilverWingedPistis"].state.ammoType == "swmg:bullet_44" and families["GFL2.SilverWingedPistisMagazine"] == ".44 Magnum")
    assert(scripts["GFL2.Sibyl"].state.ammoType == "swmg:bullet_308" and families["GFL2.SibylMagazine"] == "7.62x51mm")

    -- Non-magazine firearms (shotguns + bolt-action nomag) must also align to GoM ammo & family.
    assert(scripts["GFL2.Trailblazer"].state.ammoType == "swmg:shell_12g_buckshot" and families["GFL2.Trailblazer"] == "12Gauge")
    assert(scripts["GFL2.GaiaTerminus"].state.ammoType == "swmg:shell_12g_buckshot" and families["GFL2.GaiaTerminus"] == "12Gauge")
    assert(scripts["GFL2.Radiance"].state.ammoType == "swmg:bullet_308" and families["GFL2.Radiance"] == "7.62x51mm")

    -- Melee weapons must remain untouched.
    assert(scripts["GFL2.CauseAndEffect"].state.ammoType == "base:initial" and families["GFL2.CauseAndEffect"] == nil)
    assert(scripts["GFL2.TwilightRose"].state.ammoType == "base:initial" and families["GFL2.TwilightRose"] == nil)

    -- 2. Legacy MarzGuns (SWMG + MarzGuns): aligns to MarzGuns_AmmoTypes & registers families.
    local oldScripts, _, _, oldFamilies =
        runGoMScenario({ "SWMG", "MarzGuns" }, "MarzGuns_AmmoTypes", runGfl2Magazines, runGfl2Cadence)
    assert(oldScripts["GFL2.Scylla"].state.ammoType == "marzguns:bullet_556x45")
    assert(oldScripts["GFL2.ScyllaMagazine"].state.ammoType == "marzguns:bullet_556x45")
    assert(oldFamilies["GFL2.Scylla"] == "5.56x45mm" and oldFamilies["GFL2.ScyllaMagazine"] == "5.56x45mm")
    assert(oldScripts["GFL2.Trailblazer"].state.ammoType == "marzguns:shell_12g_buckshot" and oldFamilies["GFL2.Trailblazer"] == "12Gauge")

    -- 3. Priority guards: EFZ or MFS present -> GoM configurator does not touch scripts.
    local efzScripts, _, _, efzFamilies =
        runGoMScenario({ "SWMG", "GunsOfMarz", "EscapeFromZomboidFirearms" }, "SWMG_AmmoTypes", runGfl2Magazines, runGfl2Cadence)
    assert(efzScripts["GFL2.Scylla"].state.ammoType == "base:initial" and efzFamilies["GFL2.Scylla"] == nil)

    local mfsScripts, _, _, mfsFamilies =
        runGoMScenario({ "SWMG", "GunsOfMarz", "ModernFirearmsSystem" }, "SWMG_AmmoTypes", runGfl2Magazines, runGfl2Cadence)
    assert(mfsScripts["GFL2.Scylla"].state.ammoType == "base:initial" and mfsFamilies["GFL2.Scylla"] == nil)

    -- 4. JAR-missing self-check modal & clickable right-click/radial entries.
    GFL2CharacterRuntime = nil
    GFL2RadialMenu = nil
    local modals = {}
    local destroyed = 0
    ISModalRichText = {
        new = function(_, x, y, w, h, text, yesno, target, onclick, playerIndex)
            local m = {
                x = x, y = y, w = w, h = h, text = text, yesno = yesno,
                onclick = onclick, playerIndex = playerIndex,
                initialise = function() end,
                addToUIManager = function(self) modals[#modals + 1] = self end,
                setAlwaysOnTop = function() end,
                destroy = function() destroyed = destroyed + 1 end,
            }
            return m
        end,
    }
    getCore = function() return { getScreenWidth = function() return 1920 end, getScreenHeight = function() return 1080 end } end
    getText = function(k, a1) return a1 and (k .. ":" .. tostring(a1)) or k end
    getTextOrNull = function(k) return k end
    getTexture = function() return nil end
    local playerObj = { getPlayerNum = function() return 0 end, isDead = function() return false end }
    getSpecificPlayer = function(i) return i == 0 and playerObj or nil end
    ZombieBuddy = { getVersion = function() return "2.3.4" end }
    getActivatedMods = function() return { contains = function(_, id) return id == "ZombieBuddy" end } end

    Events = {
        OnTick = makeEvent(),
        OnDisconnect = makeEvent(),
        OnMainMenuEnter = makeEvent(),
        OnGameStart = makeEvent(),
        OnCreatePlayer = makeEvent(),
        OnSave = makeEvent(),
        OnPlayerDeath = makeEvent(),
        OnFillWorldObjectContextMenu = makeEvent(),
    }
    _G["GFL2RoomWindow"] = { open = function() end, closeAll = function() end, closeFor = function() end }
    _G["GFL2MultiplayerClient"] = { ready = function() return false end }
    _G["ISUI/ISEmoteRadialMenu"] = true
    ISEmoteRadialMenu = { fillMenu = function() end }
    local radialSlices = {}
    getPlayerRadialMenu = function()
        return { addSlice = function(_, title, _, fn, arg) radialSlices[#radialSlices + 1] = { title = title, fn = fn, arg = arg } end }
    end

    runRuntime()
    runRadial()
    Events.OnGameStart.fire()

    assert(#modals == 1, "expected auto self-check modal once on OnGameStart when Java missing")
    assert(modals[1].text:find("ContextMenu_GFL2_JavaHelp_ZbModOn", 1, true), "expected ZombieBuddy mod enabled line")
    assert(modals[1].text:find("ContextMenu_GFL2_JavaHelp_ZbRuntimeOn:2.3.4", 1, true), "expected ZombieBuddy version line")
    assert(modals[1].text:find("ContextMenu_GFL2_JavaHelp_JarOff", 1, true), "expected JAR missing line")

    -- Re-firing OnGameStart without main menu reset does not spam a second modal.
    Events.OnGameStart.fire()
    assert(#modals == 1)

    -- Right-click ground menu option must be clickable (not notAvailable) and trigger showJavaHelp.
    local menuOpt
    Events.OnFillWorldObjectContextMenu.fire(0, {
        addOption = function(_, title, p, fn)
            menuOpt = { title = title, player = p, fn = fn }
            return menuOpt
        end,
    }, {}, false)
    assert(menuOpt and menuOpt.notAvailable == nil and type(menuOpt.fn) == "function")
    menuOpt.fn(menuOpt.player)
    assert(#modals == 2 and destroyed == 1)

    -- Q radial menu must expose GFL2 slice wired to showJavaHelp when Java is missing.
    ISEmoteRadialMenu.fillMenu({ character = playerObj, playerNum = 0 }, nil)
    assert(#radialSlices == 1 and radialSlices[1].fn == GFL2CharacterActions.showJavaHelp)

    -- Returning to main menu closes modal and resets auto-prompt flag for next load.
    Events.OnMainMenuEnter.fire()
    assert(destroyed == 2)
    ZombieBuddy = nil
    getActivatedMods = function() return { contains = function() return false end } end
    Events.OnGameStart.fire()
    assert(#modals == 3)
    assert(modals[3].text:find("ContextMenu_GFL2_JavaHelp_ZbModOff", 1, true))
    assert(modals[3].text:find("ContextMenu_GFL2_JavaHelp_ZbRuntimeOff", 1, true))

    return "PASS GunsOfMarz/MarzGuns 6-caliber + ThresholdStorm cadence + JAR self-check modal"
end
