"""Verify all signature weapon Item_GFL2*.png icons are 32x32 with matching WeaponIcons and mods/Workshop parity."""
from pathlib import Path
import hashlib
import json
import struct
import sys
import zlib

from common import ROOT, settings


def png_size(path: Path) -> tuple[int, int]:
    head = path.read_bytes()[:24]
    if len(head) < 24 or head[:8] != b'\x89PNG\r\n\x1a\n':
        return (-1, -1)
    return struct.unpack('>II', head[16:24])


def png_vertical_alpha_coverage(path: Path) -> float | None:
    """Return non-transparent vertical span ratio [0..1] for 8-bit RGBA PNGs (catches flat horizontal-card icons)."""
    data = path.read_bytes()
    if len(data) < 33 or data[:8] != b'\x89PNG\r\n\x1a\n':
        return None
    pos = 8
    w = h = bd = ct = 0
    idat = bytearray()
    while pos + 8 <= len(data):
        ln, ctype = struct.unpack('>I4s', data[pos:pos + 8])
        chunk = data[pos + 8:pos + 8 + ln]
        pos += 12 + ln
        if ctype == b'IHDR':
            w, h, bd, ct = struct.unpack('>IIBB', chunk[:10])
        elif ctype == b'IDAT':
            idat.extend(chunk)
        elif ctype == b'IEND':
            break
    if bd != 8 or ct != 6 or w <= 0 or h <= 0:
        return None
    raw = zlib.decompress(bytes(idat))
    bpp = 4
    stride = w * bpp
    prev = bytearray(stride)
    rows_with_alpha = []
    idx = 0
    for y in range(h):
        ftype = raw[idx]
        idx += 1
        cur = bytearray(raw[idx:idx + stride])
        idx += stride
        if ftype == 1:
            for i in range(bpp, stride):
                cur[i] = (cur[i] + cur[i - bpp]) & 0xFF
        elif ftype == 2:
            for i in range(stride):
                cur[i] = (cur[i] + prev[i]) & 0xFF
        elif ftype == 3:
            for i in range(stride):
                a = cur[i - bpp] if i >= bpp else 0
                cur[i] = (cur[i] + ((a + prev[i]) >> 1)) & 0xFF
        elif ftype == 4:
            for i in range(stride):
                a = cur[i - bpp] if i >= bpp else 0
                b = prev[i]
                c = prev[i - bpp] if i >= bpp else 0
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                cur[i] = (cur[i] + pr) & 0xFF
        if any(cur[x * 4 + 3] > 10 for x in range(w)):
            rows_with_alpha.append(y)
        prev = cur
    if not rows_with_alpha:
        return 0.0
    return (rows_with_alpha[-1] - rows_with_alpha[0] + 1) / h


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
                elif 'Skin' not in item_png.name:
                    hcov = png_vertical_alpha_coverage(item_png)
                    if hcov is not None and hcov < 0.75:
                        errors.append(
                            f'[{label}] {pack}/{item_png.name}: vertical alpha coverage {hcov:.2f} < 0.75 '
                            f'(must use official 256x256 diagonal icon Weapon_*_5_256.png, not horizontal card)'
                        )
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
