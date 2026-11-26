from pathlib import Path
import json, os, sys
root=Path(__file__).resolve().parents[1]
test=root/'tools/tests'
media=root/'game/mod/42/media/lua'
from common import settings
config=settings()
sys.path.insert(0,config['python_dependencies'])
import jpype
game=Path(config['game_root'])
os.chdir(game)
jpype.startJVM(str(game/'jre64/bin/server/jvm.dll'),'--enable-native-access=ALL-UNNAMED',classpath=[str(game/'projectzomboid.jar')],convertStrings=True)
try:
 J=jpype.JClass
 platform=J('se.krka.kahlua.j2se.J2SEPlatform').getInstance()
 env=platform.newEnvironment()
 thread=J('se.krka.kahlua.vm.KahluaThread')(platform,env)
 thread.debugOwnerThread=J('java.lang.Thread').currentThread()
 compiler=J('se.krka.kahlua.luaj.compiler.LuaCompiler')
 empty=jpype.JArray(J('java.lang.Object'))(0)
 def run(text,name):
  result=thread.pcall(compiler.loadstring(text,name,env),empty)
  if not result[0]:raise AssertionError([str(x) for x in result])
  return result[1] if len(result)>1 else None
 for p in media.rglob('*.lua'):
  compiler.loadstring(p.read_text('utf-8-sig'),p.name,env)
 print('PASS Lua compilation',len(list(media.rglob('*.lua'))))
 run('function require(name) return assert(_G[name], name) end','require')
 for name in ('GFL2WeaponSkins','GFL2RoomEconomy','GFL2MultiplayerProtocol','GFL2MultiplayerCatalog','GFL2SignatureWeapon'):
  env.rawset(name,run((media/'shared'/f'{name}.lua').read_text('utf8'),name))
 env.rawset('Economy',env.rawget('GFL2RoomEconomy'))
 print(run((root/'tools/tests/room_economy_assertions.lua').read_text('utf8'),'economy'))
 run((test/'multiplayer-server-fixture.lua').read_text('utf8'),'fixture')
 run((media/'server/GFL2Network.lua').read_text('utf8'),'server')
 print(run('return verifyServer()','assertions'))
 env.rawset('GFL2RoomEconomy',run((media/'shared/GFL2RoomEconomy.lua').read_text('utf8'),'economy-client'))
 run((test/'multiplayer-client-fixture.lua').read_text('utf8'),'client-fixture')
 env.rawset('client',run((media/'client/GFL2MultiplayerClient.lua').read_text('utf8'),'client'))
 print(run('return verifyClient(client,GFL2RoomEconomy)','client-assertions'))
 run('GFL2MultiplayerClient=client; prepareExpressionEdits()','expression-fixture')
 env.rawset('GFL2PortraitHud',run((media/'client/GFL2PortraitHud.lua').read_text('utf8'),'portrait-hud'))
 run((media/'client/GFL2CharacterRuntime.lua').read_text('utf8'),'preview')
 print(run('return verifyExpressionEdits()','expression-assertions'))
 print(run('return verifyAppearanceRejections()','appearance-rejection-assertions'))
 run((test/'multiplayer-voice-fixture.lua').read_text('utf8'),'voice-fixture')
 for folder, names in [('shared',('VoiceRouting','Identity','NativeVoice','SilentData','Silent','VoiceCore','FullVoiceCore')),
                      ('client',('CaptionLayout','VoiceEngine','CatalogVoice'))]:
  for name in names:
   module=f'GFL2Voice/{name}'
   env.rawset(module,run((media/folder/f'{module}.lua').read_text('utf8'),module))
 print(run('return verifyVoiceLifecycle()','voice-assertions'))
 run((test/'mfs-compat-fixture.lua').read_text('utf8'),'mfs-fixture')
 env.rawset('TimedActions/ISInsertMagazine', True)
 env.rawset('TimedActions/ISLoadBulletsInMagazine', True)
 env.rawset('TimedActions/ISUnloadBulletsFromMagazine', True)
 env.rawset('GFL2SignatureMagazines',run((media/'shared/GFL2SignatureMagazines.lua').read_text('utf8'),'sig-magazines'))
 run((media/'client/ZZ_GFL2ModernFirearmsCompat.lua').read_text('utf8'),'mfs-compat')
 print(run('return verifyMfsCompat()','mfs-assertions'))
 run((test/'gom-compat-fixture.lua').read_text('utf8'),'gom-fixture')
 env.rawset('_runGfl2Magazines', compiler.loadstring((media/'shared/GFL2SignatureMagazines.lua').read_text('utf8'), 'GFL2SignatureMagazines.lua', env))
 env.rawset('_runGfl2Cadence', compiler.loadstring((media/'shared/GFL2AutomaticCadence.lua').read_text('utf8'), 'GFL2AutomaticCadence.lua', env))
 env.rawset('_runGfl2Runtime', compiler.loadstring((media/'client/GFL2CharacterRuntime.lua').read_text('utf8'), 'GFL2CharacterRuntime.lua', env))
 env.rawset('_runGfl2Radial', compiler.loadstring((media/'client/GFL2RadialMenu.lua').read_text('utf8'), 'GFL2RadialMenu.lua', env))
 print(run('return verifyGoMAndJavaHelp(_runGfl2Magazines,_runGfl2Cadence,_runGfl2Runtime,_runGfl2Radial)','gom-assertions'))
finally:jpype.shutdownJVM()
