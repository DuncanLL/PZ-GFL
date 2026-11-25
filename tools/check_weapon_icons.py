"""Verify all signature weapon Item_GFL2*.png icons are 32x32 with matching WeaponIcons and mods/Workshop parity."""
from pathlib import Path
import hashlib
import json
import struct
import sys

from common import ROOT, settings


def png_size(path: Path) -> tuple[int, int]:
    head = path.read_bytes()[:24]
    if len(head) < 24 or head[:8] != b'\x89PNG\r\n\x1a\n':
        return (-1, -1)
    return struct.unpack('>II', head[16:24])


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def pack_mod42(root: Path, pack: str) -> Path | None:
    ws = root / pack / 'Contents' / 'mods' / pack / '42'
    if ws.is_dir():
        return ws
    direct = root / pack / '42'
    if direct.is_dir():
        return direct
    return None


def main() -> None:
    cfg = settings()
    install_roots = [Path(p) for p in cfg.get('game_install_roots', [])]
    mods_root = next((p.parent for p in install_roots if p.parent.name.lower() == 'mods' and p.parents[1].name.lower() != 'contents'), None)
    ws_root = next((parent for p in install_roots for parent in p.parents if parent.name.lower() == 'workshop'), None)
    assets_root = Path(cfg['asset_root']).parent / 'localized-characters' / 'Workshop'

    roots = [(name, r) for name, r in [('mods', mods_root), ('Workshop', ws_root), ('GFL2Assets', assets_root)] if r and r.is_dir()]
    catalog = json.loads((ROOT / 'game/mod/42/media/ui/GFL2ReadyRoom/catalog.json').read_text('utf-8'))
    no_weapon_packs = {row['pack'] for row in catalog['models'].values() if catalog['roomRecruitment'].get(row['character'], {}).get('free')}
    packs = sorted({d.name for _, r in roots for d in r.iterdir() if d.is_dir() and d.name.startswith('GFL2') and d.name != 'GFL2Exilium' and d.name not in no_weapon_packs})
    assert packs, f'No GFL2 character packs found under {roots}'

    errors: list[str] = []
    checked = 0

    for pack in packs:
        item_hashes: dict[str, dict[str, str]] = {}
        wi_hashes: dict[str, dict[str, str]] = {}
        for label, root in roots:
            mod42 = pack_mod42(root, pack)
            if not mod42:
                continue
            tex_dir = mod42 / 'media' / 'textures'
            wi_dir = mod42 / 'media' / 'ui' / 'GFL2' / 'WeaponIcons'
            items = sorted(tex_dir.glob('Item_GFL2*.png')) if tex_dir.is_dir() else []
            if not items:
                errors.append(f'[{label}] {pack}: missing media/textures/Item_GFL2*.png')
                continue
            for item_png in items:
                checked += 1
                w, h = png_size(item_png)
                if (w, h) != (32, 32):
                    errors.append(f'[{label}] {pack}/{item_png.name}: expected 32x32, got {w}x{h}')
                item_hashes.setdefault(item_png.name, {})[label] = sha256(item_png)

                wi_name = item_png.name.removeprefix('Item_')
                wi_png = wi_dir / wi_name
                if not wi_png.is_file():
                    errors.append(f'[{label}] {pack}: missing WeaponIcons/{wi_name}')
                else:
                    ww, wh = png_size(wi_png)
                    if ww < 64 or wh < 64:
                        errors.append(f'[{label}] {pack}/WeaponIcons/{wi_name}: expected hi-res >=64px, got {ww}x{wh}')
                    wi_hashes.setdefault(wi_name, {})[label] = sha256(wi_png)

        for name, by_root in item_hashes.items():
            if len(set(by_root.values())) > 1:
                errors.append(f'[drift] {pack}/{name} differs across {list(by_root)}')
        for name, by_root in wi_hashes.items():
            if len(set(by_root.values())) > 1:
                errors.append(f'[drift] {pack}/WeaponIcons/{name} differs across {list(by_root)}')

    if errors:
        print(f'FAIL: {len(errors)} weapon icon standard violations:')
        for err in errors:
            print('  -', err)
        sys.exit(1)

    print(f'PASS: {len(packs)} packs ({checked} Item_GFL2*.png checks) all 32x32 + hi-res WeaponIcons + synced across {[n for n, _ in roots]}.')


if __name__ == '__main__':
    main()
