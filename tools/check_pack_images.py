"""Verify cover, poster, icon, and ready-room image standards across framework and all character packs."""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
import sys
import numpy as np
from PIL import Image

from common import ROOT, settings


def sha256(p: Path) -> str:
    return hashlib.sha256(p.read_bytes()).hexdigest()


def alpha_stats(im: Image.Image) -> dict:
    arr = np.array(im.convert("RGBA"))
    alpha = arr[:, :, 3]
    solid = alpha > 20
    ys, xs = np.where(solid)
    corners = [int(alpha[0, 0]), int(alpha[0, -1]), int(alpha[-1, 0]), int(alpha[-1, -1])]
    return {
        "has_alpha": im.mode == "RGBA" and int(alpha.min()) < 200,
        "coverage": float(solid.mean()),
        "bottom16": float(solid[-16:, :].mean()),
        "top_y": float(ys.min() / im.height) if len(ys) else 1.0,
        "bot_y": float(ys.max() / im.height) if len(ys) else 0.0,
        "top_corners_transparent": corners[0] < 20 and corners[1] < 20,
    }


def main() -> int:
    cfg = settings()
    ws_local = Path(cfg["workshop_targets"][1]).parent if len(cfg.get("workshop_targets", [])) > 1 else Path(r"E:\Game\Save\PZsave-B42.20\Workshop")
    # Resolve local Workshop root containing character packs
    for cand in [Path(p).parent for p in cfg.get("workshop_targets", [])] + [Path(r"E:\Game\Save\PZsave-B42.20\Workshop")]:
        if (cand / "GFL2Kuro").is_dir() or (cand / "GFL2Alva").is_dir():
            ws_local = cand
            break
    mods_local = Path(cfg["game_install_roots"][0]).parent
    ws_pub = Path(cfg["workshop_targets"][0])

    errors: list[str] = []

    # 1. Framework poster.png & icon.png in repo + synced copies (no 14KB white-text placeholder!)
    repo_mod42 = ROOT / "game/mod/42"
    for fname, exp_size, min_bytes in (("poster.png", (512, 512), 50_000), ("icon.png", (256, 256), 15_000)):
        rp = repo_mod42 / fname
        if not rp.is_file():
            errors.append(f"[framework] missing repo file: {rp}")
            continue
        im = Image.open(rp)
        if im.size != exp_size:
            errors.append(f"[framework] repo {fname} size={im.size}, expected {exp_size}")
        if rp.stat().st_size < min_bytes:
            errors.append(f"[framework] repo {fname} too small ({rp.stat().st_size}B < {min_bytes}B — placeholder?)")
        rh = sha256(rp)
        for label, d in (
            ("mods", mods_local / "GFL2Exilium/42"),
            ("ws_local", ws_local / "GFL2Exilium/Contents/mods/GFL2Exilium/42"),
            ("ws_pub", ws_pub / "Contents/mods/GFL2Exilium/42"),
        ):
            tp = d / fname
            if not tp.is_file():
                errors.append(f"[framework:{label}] missing {tp}")
            elif sha256(tp) != rh:
                errors.append(f"[framework:{label}] drift vs repo on {fname}")

    # 2. All character packs: preview.png, 42/poster.png, 42/icon.png
    packs = [d.name for d in sorted(ws_local.glob("GFL2*")) if d.is_dir() and d.name != "GFL2Exilium"]
    if len(packs) < 30:
        errors.append(f"Expected >= 30 character packs under {ws_local}, found {len(packs)}")

    for pk in packs:
        prev = ws_local / pk / "preview.png"
        ws_post = ws_local / pk / "Contents/mods" / pk / "42/poster.png"
        ws_icon = ws_local / pk / "Contents/mods" / pk / "42/icon.png"
        m_post = mods_local / pk / "42/poster.png"
        m_icon = mods_local / pk / "42/icon.png"

        for tag, p in (("preview", prev), ("ws_poster", ws_post), ("ws_icon", ws_icon), ("mods_poster", m_post), ("mods_icon", m_icon)):
            if not p.is_file():
                errors.append(f"[{pk}] missing {tag}: {p}")
        if not all(p.is_file() for p in (prev, ws_post, ws_icon, m_post, m_icon)):
            continue

        # preview.png: 512x512 RGBA transparent 2D close-up head+shoulders
        im_prev = Image.open(prev)
        st_prev = alpha_stats(im_prev)
        if im_prev.size != (512, 512) or im_prev.mode != "RGBA":
            errors.append(f"[{pk}] preview.png must be 512x512 RGBA, got {im_prev.size} {im_prev.mode}")
        if not st_prev["has_alpha"]:
            errors.append(f"[{pk}] preview.png lacks transparent background")
        if not (0.50 <= st_prev["coverage"] <= 0.96):
            errors.append(f"[{pk}] preview.png alpha coverage={st_prev['coverage']:.3f} outside [0.50, 0.96]")
        if st_prev["bottom16"] < 0.65:
            errors.append(f"[{pk}] preview.png bottom16 fill={st_prev['bottom16']:.3f} < 0.65 (half/full body crop?)")
        if st_prev["top_y"] > 0.12 or st_prev["bot_y"] < 0.95:
            errors.append(f"[{pk}] preview.png vertical framing top_y={st_prev['top_y']:.3f} bot_y={st_prev['bot_y']:.3f}")

        # 42/poster.png: 512x512 RGBA transparent Avatar_Bust, must NOT equal preview.png
        im_post = Image.open(ws_post)
        st_post = alpha_stats(im_post)
        if im_post.size != (512, 512) or im_post.mode != "RGBA":
            errors.append(f"[{pk}] 42/poster.png must be 512x512 RGBA, got {im_post.size} {im_post.mode}")
        if not st_post["has_alpha"]:
            errors.append(f"[{pk}] 42/poster.png lacks transparent background (opaque RGB?)")
        if sha256(prev) == sha256(ws_post):
            errors.append(f"[{pk}] preview.png and 42/poster.png must NOT be identical bytes (2D close-up vs Avatar_Bust)")
        if sha256(ws_post) != sha256(m_post):
            errors.append(f"[{pk}] 42/poster.png drift between Workshop and mods")

        # 42/icon.png: 256x256 RGBA transparent Avatar_Bust
        im_icon = Image.open(ws_icon)
        st_icon = alpha_stats(im_icon)
        if im_icon.size != (256, 256) or im_icon.mode != "RGBA":
            errors.append(f"[{pk}] 42/icon.png must be 256x256 RGBA, got {im_icon.size} {im_icon.mode}")
        if not st_icon["has_alpha"]:
            errors.append(f"[{pk}] 42/icon.png lacks transparent background")
        if sha256(ws_icon) != sha256(m_icon):
            errors.append(f"[{pk}] 42/icon.png drift between Workshop and mods")

    # 3. Ready Room 30-character dual avatar check across mods / ws_local / ws_pub (portrait- circular vs grid- square, 128x128 RGBA)
    cat = json.loads((ROOT / "game/mod/42/media/ui/GFL2ReadyRoom/catalog.json").read_text("utf-8-sig"))
    starters = cat.get("roomStarterSkins", {})
    rr_roots = (
        ("mods", mods_local / "GFL2Exilium/42/media/ui/GFL2ReadyRoom"),
        ("ws_local", ws_local / "GFL2Exilium/Contents/mods/GFL2Exilium/42/media/ui/GFL2ReadyRoom"),
        ("ws_pub", ws_pub / "Contents/mods/GFL2Exilium/42/media/ui/GFL2ReadyRoom"),
    )
    for label, rr in rr_roots:
        for ch_name, sid in starters.items():
            pp = rr / f"portrait-{sid}.png"
            gp = rr / f"grid-{sid}.png"
            for tag, p in (("portrait", pp), ("grid", gp)):
                if not p.is_file():
                    errors.append(f"[readyroom:{label}:{ch_name}] missing {p.name}")
                    continue
                im = Image.open(p)
                if im.size != (128, 128) or im.mode != "RGBA":
                    errors.append(f"[readyroom:{label}:{ch_name}] {p.name} must be 128x128 RGBA, got {im.size} {im.mode}")
            if pp.is_file() and gp.is_file():
                pa = np.array(Image.open(pp))[:, :, 3]
                if int(pa[-1, 0]) > 20 or int(pa[-1, -1]) > 20:
                    errors.append(f"[readyroom:{label}:{ch_name}] {pp.name} bottom corners not transparent (square leaked into circle slot?)")

    if errors:
        print(f"FAIL check_pack_images ({len(errors)} issues):")
        for e in errors:
            print("  -", e)
        return 1

    print(f"PASS check_pack_images: framework + {len(packs)} packs (preview/poster/icon) + {len(starters)} ready-room dual avatars.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
