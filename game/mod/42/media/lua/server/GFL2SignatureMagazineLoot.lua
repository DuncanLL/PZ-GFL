require "Items/ProceduralDistributions"

-- Signature weapon magazines are claimed directly in the Ready Room; keep vanilla
-- gun store ammo shelves untouched and strip any legacy character-pack magazine entries.
Events.OnPreDistributionMerge.Add(function()
    local tableDef = ProceduralDistributions and ProceduralDistributions.list and ProceduralDistributions.list.GunStoreMagsAmmo
    local items = tableDef and tableDef.items
    if not items then return end
    local write = 1
    for read = 1, #items, 2 do
        local name = items[read]
        local weight = items[read + 1]
        if type(name) ~= "string" or not name:match("^GFL2%..+Magazine$") then
            items[write] = name
            items[write + 1] = weight
            write = write + 2
        end
    end
    for i = write, #items do items[i] = nil end
end)
