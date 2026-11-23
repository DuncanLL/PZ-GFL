-- Public wire format for the framework. Player identity comes from the game.
local P = {module = "GFL2Exilium", version = 1}

function P.integer(value, minimum, maximum)
    return type(value) == "number" and value >= minimum and value <= maximum and value % 1 == 0
end

function P.appearance(data)
    if type(data) ~= "table" or type(data.model) ~= "string" then return false end
    if data.model == "" then return data.signature == "" and data.appearance == "" end
    return #data.model == 20 and data.model:match("^pmx%-%x+$") ~= nil
        and type(data.signature) == "string" and #data.signature == 64 and data.signature:match("^%x+$") ~= nil
        and type(data.appearance) == "string" and #data.appearance >= 2 and #data.appearance <= 8192
end

function P.voice(data)
    if type(data) ~= "table" or type(data.sound) ~= "string" then return false end
    if #data.sound < 9 or #data.sound > 64 or not data.sound:match("^GFL2Pmx[%w_]+$") then return false end
    return data.kind == nil or (type(data.kind) == "string" and #data.kind <= 16)
end

return P
