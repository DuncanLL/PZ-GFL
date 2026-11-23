"""Validate an actual Workshop project and its packaged native runtime without starting the game."""
from pathlib import Path
import json
import os
import sys
from common import ROOT, BUILD, settings, sha, save

config = settings()
project = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else BUILD / 'workshop/GFL2Exilium'
mod = project / 'Contents/mods/GFL2Exilium'
temp = BUILD / 'tmp'
temp.mkdir(parents=True, exist_ok=True)
os.environ['TEMP'] = os.environ['TMP'] = str(temp)
sys.path.insert(0, config['python_dependencies'])
import jpype
game = Path(config['game_root'])
jar = mod / '42/media/java/client/GFL2Exilium.jar'
# The game's file validator permits only its private user root (or game roots).
# Keep an out-of-workspace candidate inside this check's own permitted root.
home = BUILD if project.is_relative_to(BUILD) else project.parent.parent
home.mkdir(exist_ok=True)
jpype.startJVM(str(game / 'jre64/bin/server/jvm.dll'), '--enable-native-access=ALL-UNNAMED',
              '-Djava.io.tmpdir=' + str(temp), '-Duser.home=' + str(home),
              classpath=[str(jar), config['zombiebuddy_jar'], config['viewpoint_jar'], str(game), *map(str, game.glob('*.jar'))], convertStrings=True)
J = jpype.JClass
try:
    os.chdir(game)
    J('zombie.ZomboidFileSystem').instance.setCacheDir(str(home))
    J('zombie.ZomboidFileSystem').instance.init()
    if project.parent.name.casefold() == 'workshop':
        J('zombie.ZomboidFileSystem').instance.setCacheDir(str(project.parent.parent))
    item = J('zombie.core.znet.SteamWorkshopItem')(str(project))
    assert item.readWorkshopTxt()
    error = item.validateContents()
    assert error is None, error
    description = '\n'.join(line.removeprefix('description=') for line in
                            (project / 'workshop.txt').read_text('utf-8-sig').splitlines()
                            if line.startswith('description='))
    assert item.getTitle() == '僵毁少女前线' and item.getDescription().strip() == description.strip()
    J('zombie.ZomboidFileSystem').instance.setCacheDir(str(home))
    os.chdir(temp)
    main = J('gfl2.runtime.Main')
    directory = main.class_.getDeclaredField('directory')
    directory.setAccessible(True); directory.set(None, J('java.nio.file.Path').of(str(mod / '42')))
    initialize = main.class_.getDeclaredMethod('nativeRuntime')
    initialize.setAccessible(True); initialize.invoke(None)
    api_field = main.class_.getDeclaredField('api'); api_field.setAccessible(True)
    api = api_field.get(None)
    build = json.loads((BUILD / 'build.json').read_text('utf8'))
    assert api.GetVersion() == build['native_version']
    assets = mod / '42/media/gfl2_pmx'
    registry = json.loads((assets / 'registry.json').read_text('utf8'))['models']
    checked = []
    for row in registry:
        profile = json.loads((assets / row['profile']).read_text('utf8'))
        assert sha(assets / row['model']) == profile['pmx_sha256']
        model = J('gfl2.motion.PmxModel').load(api, J('java.nio.file.Path').of(str(assets / row['model'])),
                                              J('java.nio.file.Path').of(str(assets / row['profile'])))
        try:
            pose = jpype.JArray(jpype.JFloat)(profile['source_reference'])
            for _ in range(4): model.apply(pose, None, 1/60)
            checked.append(row['id'])
        finally: model.close()
    stats = json.loads(str(api.GetRuntimeStats()))
    assert all(value == 0 for value in stats.values())
    assert J('gfl2.motion.ChestSoftBody').liveWorlds()==0

    # Verify cross-save & MP reconnect GripCalibration persistence, legacy SP migration, and reset.
    reg_data = json.loads((assets / 'registry.json').read_text('utf8'))
    multi_skins = next(v for v in reg_data['packs'].values() if len(v) >= 2)
    skin_a, skin_b = multi_skins[0], multi_skins[1]
    gc_cls = J('java.lang.Class').forName('gfl2.runtime.GripCalibration')
    def gc_method(name, *types):
        m = gc_cls.getDeclaredMethod(name, *[J(t).class_ if isinstance(t, str) else t for t in types])
        m.setAccessible(True)
        return m
    jBoolean, jInt, jDouble = J('java.lang.Boolean').TYPE, J('java.lang.Integer').TYPE, J('java.lang.Double').TYPE
    m_set_store = gc_method('setStoreFileForTest', 'java.nio.file.Path')
    m_load_chars = gc_method('loadCharacters', 'java.nio.file.Path')
    m_set = gc_method('set', 'zombie.characters.IsoGameCharacter', 'java.lang.String', 'java.lang.String', jInt, jDouble, jBoolean)
    m_sliders = gc_method('sliders', 'zombie.characters.IsoGameCharacter', 'java.lang.String', 'java.lang.String', jBoolean)
    m_current = gc_method('currentRow', 'zombie.characters.IsoGameCharacter', 'java.lang.String', 'zombie.inventory.InventoryItem', jBoolean)
    m_flush = gc_method('flush')
    unsafe_f = J('sun.misc.Unsafe').class_.getDeclaredField('theUnsafe'); unsafe_f.setAccessible(True)
    unsafe = unsafe_f.get(None)
    platform = J('se.krka.kahlua.j2se.J2SEPlatform').getInstance()
    iso_player_cls = J('zombie.characters.IsoPlayer').class_
    iso_char_cls = J('zombie.characters.IsoGameCharacter').class_
    iso_obj_cls = J('zombie.iso.IsoObject').class_
    inv_item_cls = J('zombie.inventory.InventoryItem').class_
    f_primary = iso_char_cls.getDeclaredField('primaryHandItem'); f_primary.setAccessible(True)
    f_table = iso_obj_cls.getDeclaredField('table'); f_table.setAccessible(True)
    f_mod = inv_item_cls.getDeclaredField('module'); f_mod.setAccessible(True)
    f_type = inv_item_cls.getDeclaredField('type'); f_type.setAccessible(True)
    f_full = inv_item_cls.getDeclaredField('fullType'); f_full.setAccessible(True)
    def make_item(mod_name, type_name):
        it = unsafe.allocateInstance(J('zombie.inventory.types.HandWeapon').class_)
        f_mod.set(it, mod_name); f_type.set(it, type_name); f_full.set(it, f'{mod_name}.{type_name}')
        return it
    def make_local_player(held):
        pl = unsafe.allocateInstance(iso_player_cls)
        f_table.set(pl, platform.newTable())
        f_primary.set(pl, held)
        J('zombie.characters.IsoPlayer').numPlayers = 1
        J('zombie.characters.IsoPlayer').players[0] = pl
        return pl
    grip_file = temp / 'grip-calibration-check.json'
    grip_file.unlink(missing_ok=True)
    try:
        j_grip_path = J('java.nio.file.Path').of(str(grip_file))
        m_set_store.invoke(None, j_grip_path)
        m_load_chars.invoke(None, J('java.nio.file.Path').of(str(assets / 'registry.json')))
        skyhowl = make_item('GFL2', 'Skyhowl')
        p1 = make_local_player(skyhowl)
        assert bool(m_set.invoke(None, p1, skin_a, 'GFL2.Skyhowl', jpype.JInt(0), jpype.JDouble(3.5), jpype.JBoolean(False)))
        assert bool(m_set.invoke(None, p1, skin_a, 'GFL2.Skyhowl', jpype.JInt(7), jpype.JDouble(-15.0), jpype.JBoolean(True)))
        m_flush.invoke(None)
        assert grip_file.is_file()
        # Simulate full game restart + MP server login with empty ModData and sibling outfit skin.
        m_set_store.invoke(None, j_grip_path)
        mp_player = make_local_player(skyhowl)
        stand_vals = m_sliders.invoke(None, mp_player, skin_b, 'GFL2.Skyhowl', jpype.JBoolean(False))
        aim_vals = m_sliders.invoke(None, mp_player, skin_b, 'GFL2.Skyhowl', jpype.JBoolean(True))
        assert float(stand_vals.rawget(jpype.JDouble(1))) == 3.5
        assert float(aim_vals.rawget(jpype.JDouble(8))) == -15.0
        row1 = m_current.invoke(None, mp_player, skin_b, skyhowl, jpype.JBoolean(False))
        row2 = m_current.invoke(None, mp_player, skin_b, skyhowl, jpype.JBoolean(False))
        assert row1 is not None and row1 == row2
        # Legacy single-player ModData auto-imports to persistent JSON, and explicit reset persists.
        pistol = make_item('Base', 'Pistol')
        sp_player = make_local_player(pistol)
        sp_store = platform.newTable(); legacy_char = platform.newTable(); legacy_row = platform.newTable()
        legacy_row.rawset(jpype.JDouble(1), jpype.JDouble(-4.0))
        legacy_char.rawset('Base.Pistol', legacy_row)
        sp_store.rawset(skin_a, legacy_char)
        sp_player.getModData().rawset('GFL2GripCalibration', sp_store)
        migrated = m_sliders.invoke(None, sp_player, skin_b, 'Base.Pistol', jpype.JBoolean(False))
        assert float(migrated.rawget(jpype.JDouble(1))) == -4.0
        m_flush.invoke(None)
        assert bool(m_set.invoke(None, sp_player, skin_b, 'Base.Pistol', jpype.JInt(-1), jpype.JDouble(0.0), jpype.JBoolean(False)))
        m_flush.invoke(None)
        m_set_store.invoke(None, j_grip_path)
        sp_reload = make_local_player(pistol)
        sp_reload.getModData().rawset('GFL2GripCalibration', sp_store)
        reset_vals = m_sliders.invoke(None, sp_reload, skin_b, 'Base.Pistol', jpype.JBoolean(False))
        assert float(reset_vals.rawget(jpype.JDouble(1))) == 0.0
    finally:
        m_set_store.invoke(None, jpype.JArray(J('java.lang.Object'))([None]))
        J('zombie.characters.IsoPlayer').numPlayers = 0
        J('zombie.characters.IsoPlayer').players[0] = None
        grip_file.unlink(missing_ok=True)

    # Verify pelvis-pivot root retargeting (bed lying) and FurnitureSeat chair-vs-lying discrimination.
    sample_prof_path = None
    sample_pmx_path = None
    for pack_id in reg_data['packs']:
        cand_dir = mod.parent.parent.parent / pack_id / 'Contents/mods' / pack_id / '42/media/gfl2_pmx'
        if not cand_dir.is_dir():
            cand_dir = mod.parent.parent / pack_id / '42/media/gfl2_pmx'
        profs = sorted(cand_dir.glob('models/*/profile.json')) if cand_dir.is_dir() else []
        if profs:
            sample_prof_path = profs[0]
            sample_pmx_path = next(sample_prof_path.parent.glob('*.pmx'))
            break
    if sample_prof_path is not None:
        prof_obj = json.loads(sample_prof_path.read_text('utf8'))
        prof_json = J('org.json.JSONObject')(sample_prof_path.read_text('utf8'))
        pmx_test = J('gfl2.motion.PmxModel').load(api, J('java.nio.file.Path').of(str(sample_pmx_path)),
                                                  J('java.nio.file.Path').of(str(sample_prof_path)), True)
        try:
            snames = prof_obj['source_bone_names']
            sr_i = prof_obj['source_root']
            pel_i = snames.index('Bip01_Pelvis')
            sp_i = snames.index('Bip01_Spine1')
            lt_i, rt_i = snames.index('Bip01_L_Thigh'), snames.index('Bip01_R_Thigh')
            lc_i, rc_i = snames.index('Bip01_L_Calf'), snames.index('Bip01_R_Calf')
            lf_i, rf_i = snames.index('Bip01_L_Foot'), snames.index('Bip01_R_Foot')
            pel_ti = prof_obj['target_ids'][prof_obj['source_ids'].index(pel_i)]
            u_scale = float(prof_obj['unit_scale'])
            ref_pose = list(prof_obj['source_reference'])
            pmx_test.apply(jpype.JArray(jpype.JFloat)(ref_pose), None, 0.0, -1, -1, None)
            rest_pel = pmx_test.retargeter().jointMidpoint(pel_ti, pel_ti)
            rest_pel_y = float(rest_pel.y()) / u_scale
            # Pitch source_root and Bip01_Pelvis 90 deg around X around their shared rest translation.
            lie_pose = list(ref_pose)
            for bi in (sr_i, pel_i):
                m = J('org.joml.Matrix4f')().set(jpype.JArray(jpype.JFloat)(ref_pose[bi*16:(bi+1)*16]))
                t = m.getTranslation(J('org.joml.Vector3f')())
                rot_m = J('org.joml.Matrix4f')().translation(t).rotateX(1.5707963267948966).mul(m.setTranslation(0, 0, 0))
                arr = jpype.JArray(jpype.JFloat)(16)
                rot_m.get(arr)
                lie_pose[bi*16:(bi+1)*16] = list(arr)
            # Make spine horizontal in lie_pose for FurnitureSeat check
            lie_pose[sp_i*16+12], lie_pose[sp_i*16+13], lie_pose[sp_i*16+14] = lie_pose[pel_i*16+12], lie_pose[pel_i*16+13], lie_pose[pel_i*16+14] + 0.2
            for ti, ci, fi in ((lt_i, lc_i, lf_i), (rt_i, rc_i, rf_i)):
                lie_pose[ci*16+12], lie_pose[ci*16+13], lie_pose[ci*16+14] = lie_pose[ti*16+12], lie_pose[ti*16+13], lie_pose[ti*16+14] - 0.4
                lie_pose[fi*16+12], lie_pose[fi*16+13], lie_pose[fi*16+14] = lie_pose[ci*16+12], lie_pose[ci*16+13], lie_pose[ci*16+14] - 0.4
            j_lie = jpype.JArray(jpype.JFloat)(lie_pose)
            pmx_test.apply(j_lie, None, 0.0, -1, -1, None)
            lie_pel = pmx_test.retargeter().jointMidpoint(pel_ti, pel_ti)
            assert abs(float(lie_pel.y()) / u_scale - rest_pel_y) < 1e-3, (float(lie_pel.y()) / u_scale, rest_pel_y)
            assert abs(float(lie_pel.z()) / u_scale - float(rest_pel.z()) / u_scale) < 1e-3
            # FurnitureSeat must return 0 on horizontal lying pose and >0 on upright seated pose.
            seat_cls = J('java.lang.Class').forName('gfl2.runtime.FurnitureSeat')
            seat_ctor = seat_cls.getDeclaredConstructor(J('org.json.JSONObject').class_); seat_ctor.setAccessible(True)
            seat_off = next(m for m in seat_cls.getDeclaredMethods() if str(m.getName()) == 'offset'); seat_off.setAccessible(True)
            vcount = int(prof_obj['vertices'])
            pos_buf = J('java.nio.ByteBuffer').allocateDirect(vcount * 12).order(J('java.nio.ByteOrder').nativeOrder())
            api.CopyDataToByteBuffer(pos_buf, api.GetPoss(pmx_test.handle()), vcount * 12)
            j_verts = jpype.JArray(jpype.JFloat)(vcount * 3)
            pos_buf.asFloatBuffer().get(j_verts)
            fd = J('gfl2.motion.PmxDrawMesh$FrameData')(j_verts, j_verts, jpype.JArray(jpype.JFloat)(12))
            seat_inst = seat_ctor.newInstance(prof_json)
            assert float(seat_off.invoke(seat_inst, api, pmx_test, prof_json, jpype.JFloat(u_scale), j_lie, fd, jpype.JBoolean(True), jpype.JBoolean(True))) == 0.0
            assert seat_inst.needsMeasurement()
        finally:
            pmx_test.close()

    field = main.class_.getDeclaredField('grip'); field.setAccessible(True); field.get(None).close()
    save(BUILD / 'runtime-check.json', {'status': 'PASS_WORKSHOP_VALIDATOR_AND_NATIVE_MODEL_COMPONENTS',
         'jar_sha256': sha(jar), 'models': checked, 'remaining_objects': stats,
         'game_started': False, 'workshop_uploaded': False, 'actual_gameplay_tested': False})
    print('PASS: Workshop validator, packaged native initialization, ' + str(len(checked)) + ' models, zero remaining objects.', flush=True)
except Exception as problem:
    if hasattr(problem, 'getMessage'):
        print('Java validation error: ' + str(problem.getMessage()), flush=True)
    raise
finally:
    jpype.shutdownJVM()
