local oldRequire=require
local worldData={}
ModData={getOrCreate=function(key)
 worldData[key]=worldData[key] or {}
 return worldData[key]
end}
function require(name)
 if name=="GFL2RoomEconomy"then return economy end
 if name=="GFL2SignatureWeapon"then return signatureWeapons end
 if name=="GFL2WeaponSkins"then return weaponSkins end
 if name=="GFL2MultiplayerProtocol"then return multiplayerProtocol end
 if name=="GFL2MultiplayerClient"then return multiplayerRoom end
 if roomModules and roomModules[name] then return roomModules[name] end
 return oldRequire(name)
end
function getScriptManager()return {FindItem=function()end}end
function actor:getInventory()
 return {containsTypeRecurse=function()return false end,getAllTypeRecurse=function()return {size=function()return 0 end} end}
end
function actor:isDead()return false end
function getTimestampMs()return 10000 end
function UI:getIsVisible()return self.visible end
function UI:prerender()end
function UI:render()end
function UI:isMouseOver()return self.mouseOver==true end
function UI:getAbsoluteX()return (self.parent and self.parent.getAbsoluteX and self.parent:getAbsoluteX() or 0)+(self.x or 0) end
function UI:getAbsoluteY()return (self.parent and self.parent.getAbsoluteY and self.parent:getAbsoluteY() or 0)+(self.y or 0) end
function UI:setScrollWithParent()end
if not getMouseX then function getMouseX()return 0 end end
if not getMouseY then function getMouseY()return 0 end end
if not ISToolTip then
 ISToolTip=UI:derive("ISToolTip")
 function ISToolTip:new()return UI.new(self,0,0,100,20) end
 function ISToolTip:setOwner(o)self.owner=o end
 function ISToolTip:setAlwaysOnTop(v)self.alwaysOnTop=v end
 function ISToolTip:setDesiredPosition(x,y)self.x,self.y=x,y end
end
function ISScrollingListBox:ensureVisible()end
function UI:drawTextCentre()end
function UI:drawTextRight()end
function recordDraw()end
function getTexture(path)return {path=path,getWidth=function()return 340 end,getHeight=function()return 156 end}end
opened={}
overlayEnabled=false
function isSteamOverlayEnabled()return overlayEnabled end
function activateSteamOverlayToWorkshopItem(id)table.insert(opened,{kind="overlay",id=id})end
function openUrl(url)table.insert(opened,{kind="browser",url=url})end
