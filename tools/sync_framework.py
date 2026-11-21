"""Standard one-command build, verification, and directory sync for GFL2Exilium."""
import argparse
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys

from common import BUILD, ROOT, environment, settings, sha


MOD_INFO_REL = Path('42/mod.info')
JAR_REL = Path('media/java/client/GFL2Exilium.jar')
STEAM_APP_SUB_JAR_WORKSHOP_ID = '3842318650'
VERSION_RE = re.compile(r'^\d+\.\d+\.\d+$')


def mod42_dir(root: Path) -> Path:
    """Resolve the active `<mod>/42` folder for both mods/ and Workshop/ layouts."""
    workshop = root / 'Contents/mods/GFL2Exilium/42'
    if workshop.is_dir():
        return workshop
    return root / '42'


def read_modversion(mod_info: Path) -> str:
    for line in mod_info.read_text('utf-8-sig').splitlines():
        if line.startswith('modversion='):
            return line.split('=', 1)[1].strip()
    raise ValueError(f'Missing modversion in {mod_info}')


def set_modversion(mod_info: Path, version: str) -> bool:
    if not VERSION_RE.fullmatch(version):
        raise ValueError(f'Invalid version format (expected X.Y.Z): {version}')
    raw = mod_info.read_bytes()
    newline = b'\r\n' if b'\r\n' in raw else b'\n'
    text = raw.decode('utf-8-sig')
    lines = text.splitlines()
    updated = []
    found = False
    for line in lines:
        if line.startswith('modversion='):
            updated.append('modversion=' + version)
            found = True
        else:
            updated.append(line)
    if not found:
        raise ValueError(f'Missing modversion in {mod_info}')
    new_bytes = (newline.decode('ascii').join(updated) + newline.decode('ascii')).encode('utf-8')
    if new_bytes == raw:
        return False
    mod_info.write_bytes(new_bytes)
    return True


def verify_render_support_fresh() -> tuple[Path, Path]:
    report_path = BUILD / 'combined-support/build.json'
    if not report_path.is_file():
        raise SystemExit(
            f'Missing {report_path}; run `py tools/build_render_support.py && py tools/build_combined_render_support.py` first.'
        )
    report = json.loads(report_path.read_text('utf-8-sig'))
    addon_jar = Path(report['jar'])
    if not addon_jar.is_file() or sha(addon_jar) != report['jar_sha256']:
        raise SystemExit(
            'Combined render support JAR is missing or stale; run `py tools/build_render_support.py && py tools/build_combined_render_support.py`.'
        )
    render_report_path = BUILD / 'render-support/build.json'
    render_report = json.loads(render_report_path.read_text('utf-8-sig')) if render_report_path.is_file() else {}
    for rel, expected in report.get('source_hashes', {}).items():
        path = ROOT / rel
        if rel == 'resources/manifest.json':
            manifest = json.loads(path.read_text('utf-8-sig')) if path.is_file() else {}
            profile_sha = manifest.get('render_support', {}).get('original_display_profile', {}).get('sha256')
            if not profile_sha or profile_sha != render_report.get('original_display_asset_sha256'):
                raise SystemExit(
                    'Render support asset hash changed in resources/manifest.json; run `py tools/build_render_support.py && py tools/build_combined_render_support.py` first.'
                )
            continue
        if not path.is_file() or sha(path) != expected:
            raise SystemExit(
                f'Render support source changed ({rel}); run `py tools/build_render_support.py && py tools/build_combined_render_support.py` first.'
            )
    return addon_jar.parent, report_path


def repo_mod42_files() -> dict[str, Path]:
    base = ROOT / 'game/mod/42'
    return {
        p.relative_to(base).as_posix(): p
        for p in sorted(base.rglob('*'))
        if p.is_file()
    }


def configured_roots(config: dict) -> list[tuple[str, Path]]:
    roots: list[tuple[str, Path]] = []
    for idx, p in enumerate(config.get('game_install_roots', []), 1):
        roots.append((f'local[{idx}]', Path(p)))
    for idx, p in enumerate(config.get('workshop_targets', []), 1):
        roots.append((f'workshop[{idx}]', Path(p)))
    steam = Path(config['game_root']).parents[1] / 'workshop/content/108600' / STEAM_APP_SUB_JAR_WORKSHOP_ID / 'mods/GFL2Exilium'
    if steam.exists():
        roots.append(('steam_cache', steam))
    return roots


def audit_target(mod42: Path, repo_files: dict[str, Path]) -> dict:
    if not mod42.is_dir():
        return {'exists': False}
    info = mod42 / 'mod.info'
    jar = mod42 / JAR_REL
    zbs = mod42 / (JAR_REL.as_posix() + '.zbs')
    version = read_modversion(info) if info.is_file() else '(missing)'
    jar_sha = sha(jar) if jar.is_file() else None
    missing, differing = [], []
    for rel, src in repo_files.items():
        dst = mod42 / rel
        if not dst.is_file():
            missing.append(rel)
        elif sha(dst) != sha(src):
            differing.append(rel)
    return {
        'exists': True,
        'version': version,
        'jar_sha256': jar_sha,
        'zbs_exists': zbs.is_file(),
        'missing': missing,
        'differing': differing,
    }


def print_status(config: dict) -> None:
    repo_files = repo_mod42_files()
    repo_version = read_modversion(ROOT / 'game/mod' / MOD_INFO_REL)
    print(f'[Git Repo] modversion={repo_version} | game/mod/42 files={len(repo_files)}')

    git_bin = shutil.which('git')
    if git_bin:
        git_res = subprocess.run([git_bin, '-C', str(ROOT), 'status', '--short'], capture_output=True, text=True)
        if git_res.returncode == 0:
            changed = [line for line in git_res.stdout.splitlines() if line.strip()]
            print(f'[Git Status] modified/untracked entries={len(changed)}')
            for line in changed[:10]:
                print(f'  {line}')

    try:
        _, report_path = verify_render_support_fresh()
        print(f'[Render Support] OK ({report_path})')
    except SystemExit as err:
        print(f'[Render Support] STALE: {err}')

    print('\n[Directory Audit]')
    for label, root in configured_roots(config):
        mod42 = mod42_dir(root)
        info = audit_target(mod42, repo_files)
        if not info['exists']:
            print(f'- {label}: MISSING ({mod42})')
            continue
        jar_txt = (info['jar_sha256'][:16] + '...') if info['jar_sha256'] else 'MISSING'
        drift = len(info['missing']) + len(info['differing'])
        print(
            f"- {label}: modversion={info['version']} | jar={jar_txt} ({info['jar_sha256']}) | "
            f".jar.zbs={info['zbs_exists']} | mod42_drift={drift}"
        )
        print(f'  path: {mod42}')
        for rel in (info['missing'] + info['differing'])[:10]:
            tag = 'missing' if rel in info['missing'] else 'diff'
            print(f'    [{tag}] {rel}')


def run_step(cmd: list[str], label: str) -> None:
    print(f'\n==> {label}: {" ".join(cmd)}', flush=True)
    subprocess.run(cmd, cwd=ROOT, env=environment(), check=True)


def build_verify_and_sync(config: dict, target: str, requested_version: str | None) -> None:
    render_project, render_report = verify_render_support_fresh()

    repo_mod_info = ROOT / 'game/mod' / MOD_INFO_REL
    version = read_modversion(repo_mod_info)
    if requested_version is not None:
        set_modversion(repo_mod_info, requested_version)
        version = requested_version

    # 1. Compile character runtime JAR
    run_step([sys.executable, str(ROOT / 'tools/build.py')], 'Compile character runtime JAR')
    char_build = json.loads((BUILD / 'build.json').read_text('utf-8-sig'))
    char_sha = char_build['jar_sha256']
    char_jar = Path(char_build['jar'])

    # 2. Stage clean Workshop template + repo game/mod/42 + compiled JAR
    template_root = Path(config['workshop_targets'][0]).resolve(strict=True)
    work = BUILD / 'framework-release'
    if work.exists():
        shutil.rmtree(work)
    stage = work / 'stage/GFL2Exilium'
    shutil.copytree(template_root, stage)
    stage_mod42 = stage / 'Contents/mods/GFL2Exilium/42'
    repo_files = repo_mod42_files()
    for rel, src in repo_files.items():
        dst = stage_mod42 / rel
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(src, dst)
    stage_jar = stage_mod42 / JAR_REL
    stage_jar.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(char_jar, stage_jar)
    stage_jar.with_suffix('.jar.zbs').unlink(missing_ok=True)

    # 3. Package unified JAR (wiring + render-support)
    unified_project = work / 'Workshop/GFL2Exilium'
    run_step(
        [
            sys.executable,
            str(ROOT / 'tools/package_gfl2_unified.py'),
            '--character-project', str(stage),
            '--character-sha256', char_sha,
            '--render-project', str(render_project),
            '--render-report', str(render_report),
            '--version', version,
            '--output', str(unified_project),
        ],
        'Assemble unified Workshop package',
    )
    shutil.rmtree(work / 'stage')

    # 4. Run full automated verification suite
    run_step(
        [sys.executable, str(ROOT / 'tools/check_gfl2_unified.py'), '--project', str(unified_project)],
        'Verify unified startup hooks',
    )
    run_step(
        [sys.executable, str(ROOT / 'tools/check_runtime.py'), str(unified_project)],
        'Validate Workshop layout and native initialization',
    )
    run_step(
        [sys.executable, '-m', 'unittest', 'discover', '-s', str(ROOT / 'tools'), '-p', 'test_build.py'],
        'Run build regression test',
    )

    # 5. Cross-check unified build & startup-check hashes
    unified_jar = unified_project / 'Contents/mods/GFL2Exilium/42' / JAR_REL
    unified_report = json.loads((work / 'build.json').read_text('utf-8-sig'))
    startup_report = json.loads((work / 'startup-check.json').read_text('utf-8-sig'))
    unified_sha = sha(unified_jar)
    assert unified_sha == unified_report['jar_sha256'], 'Unified JAR hash mismatch vs build.json'
    assert unified_sha == startup_report['jar_sha256'], 'Unified JAR hash mismatch vs startup-check.json'

    # 6. Sync targets if requested
    sync_roots: list[Path] = []
    if target in ('local', 'workshop'):
        sync_roots.extend(Path(p) for p in config.get('game_install_roots', []))
    if target == 'workshop':
        sync_roots.extend(Path(p) for p in config.get('workshop_targets', []))

    synced: list[Path] = []
    for root in sync_roots:
        mod42 = mod42_dir(root)
        if not mod42.is_dir():
            raise SystemExit(f'Target directory missing: {mod42}')
        for rel, src in repo_files.items():
            dst = mod42 / rel
            dst.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(src, dst)
        dst_jar = mod42 / JAR_REL
        dst_jar.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(unified_jar, dst_jar)
        dst_zbs = mod42 / (JAR_REL.as_posix() + '.zbs')
        dst_zbs.unlink(missing_ok=True)

        # Read-back verification
        assert not dst_zbs.exists(), f'Stale signature remains at {dst_zbs}'
        assert sha(dst_jar) == unified_sha, f'Synced JAR checksum mismatch at {dst_jar}'
        for rel, src in repo_files.items():
            assert sha(mod42 / rel) == sha(src), f'Synced file checksum mismatch at {mod42 / rel}'
        synced.append(mod42)

    print('\n=== SYNC FRAMEWORK SUMMARY ===')
    print(f'modversion:   {version}')
    print(f'unified_jar:  {unified_jar}')
    print(f'jar_sha256:   {unified_sha}')
    print(f'checks:       PASS (check_gfl2_unified, check_runtime, test_build)')
    if synced:
        for path in synced:
            print(f'synced:       {path}')
    else:
        print('synced:       (none -- build & verify only)')
    print('steam_upload: NOT PERFORMED (use explicit release upload when ready)')


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--status', action='store_true', help='Read-only status check across repo and all targets.')
    parser.add_argument(
        '--target',
        choices=('none', 'local', 'workshop'),
        default='none',
        help='Where to sync after build + verification pass (default: none).',
    )
    parser.add_argument('--version', help='Optional X.Y.Z version to write to game/mod/42/mod.info before building.')
    args = parser.parse_args()

    config = settings()
    if args.status:
        print_status(config)
        return
    build_verify_and_sync(config, args.target, args.version)


if __name__ == '__main__':
    main()
