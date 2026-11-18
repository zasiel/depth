"""2D -> 3D photo converter prototype (offline, on-device-class models only).

usage: python3 convert.py photo1.jpg [photo2.jpg ...] --out out_dir [--strength 0.8]

Per photo it writes:
  <name>/fg.jpg, bg.jpg              colour layers
  <name>/fg_depth.png, bg_depth.png  closeness, 16-bit packed into R(high)+G(low)
  <name>/meta.json                   grading + timing
  <name>/sbs.jpg                     side-by-side stereo fallback
  <name>/compare.gif                 lean-left/right: naive warp vs layered
"""
import argparse, json, os, sys, time
import numpy as np
import cv2

import depth as depth_mod
import layers
import render
import inpaint as inpaint_mod
import matte as matte_mod

HERE = os.path.dirname(os.path.abspath(__file__))
DEPTH_MODEL = os.environ.get("DEPTH_MODEL", os.path.join(HERE, "..", "models", "depth_anything_v2_vits_dynamic.onnx"))


def pack16(c, cut=None, alpha=None):
    """R,G = 16-bit closeness; B = foreground alpha (0..255), or a legacy cut mask."""
    v = np.clip(c * 65535 + 0.5, 0, 65535).astype(np.uint32)
    if alpha is not None:
        b = np.clip(alpha * 255 + 0.5, 0, 255).astype(np.uint8)
    else:
        b = np.zeros_like(v, np.uint8) if cut is None else (cut.astype(np.uint8) * 255)
    return np.dstack([(v >> 8).astype(np.uint8), (v & 255).astype(np.uint8), b])


def cut_mask(c, strength, max_eye=0.10, max_stretch_px=1.5):
    """Mark both pixels of any neighbour pair whose depth step would open a gap
    wider than max_stretch_px at the largest supported head offset. The viewer
    discards mesh triangles touching these pixels, so objects separate cleanly
    instead of stretching (and thin parts like spokes keep their shape)."""
    K = max_eye * strength * c.shape[1]
    k = np.ones((3, 3), np.uint8)
    cut = ((cv2.dilate(c, k) - cv2.erode(c, k)) > (max_stretch_px / K)).astype(np.uint8)
    # smooth the cut lines: close small gaps and drop isolated specks so fur and
    # hair edges get one clean cut instead of a comb of tiny ones
    cut = cv2.morphologyEx(cut, cv2.MORPH_CLOSE, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (5, 5)))
    n, lab, stats, _ = cv2.connectedComponentsWithStats(cut, 8)
    keep = np.zeros(n, bool); keep[1:] = stats[1:, cv2.CC_STAT_AREA] >= 12
    return keep[lab]


def convert(path, out_dir, strength=0.8, max_side=1600, gif=True):
    name = os.path.splitext(os.path.basename(path))[0]
    od = os.path.join(out_dir, name)
    os.makedirs(od, exist_ok=True)
    bgr = cv2.imread(path, cv2.IMREAD_COLOR)
    if bgr is None:
        raise SystemExit(f"cannot read {path}")
    h, w = bgr.shape[:2]
    s = min(1.0, max_side / max(h, w))
    if s < 1:
        bgr = cv2.resize(bgr, (int(w * s), int(h * s)), interpolation=cv2.INTER_AREA)
    rgb = cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB)

    t0 = time.time()
    c, t_depth = depth_mod.estimate(rgb, DEPTH_MODEL)
    t1 = time.time()
    K = 0.10 * strength * rgb.shape[1]           # px of parallax per unit closeness at max lean
    M = matte_mod.matte(rgb, c, K)
    t1m = time.time()
    L = layers.build(rgb, c, inpaint_mod.inpaint, strength=strength, matte=M)
    t2 = time.time()

    _, g = layers.grade(c, strength)
    z_fg, _ = layers.grade(M["c_fg"], strength, subject=g["subject"])
    z_bg, _ = layers.grade(L["c_bg"], strength, subject=g["subject"])
    # keep bg exactly on the same grading curve
    meta = {
        "source": os.path.basename(path), "width": rgb.shape[1], "height": rgb.shape[0],
        "grading": g, "band_px": L["band_px"], "reveal_pct": round(L["reveal_pct"], 2),
        "inpainter": inpaint_mod.NAME,
        "seconds": {"depth": round(t1 - t0, 2), "matte": round(t1m - t1, 2), "layers_inpaint": round(t2 - t1m, 2)},
    }

    # fg colour = decontaminated foreground; alpha rides in the depth PNG's B channel
    cv2.imwrite(os.path.join(od, "fg.jpg"), cv2.cvtColor(M["F"], cv2.COLOR_RGB2BGR), [cv2.IMWRITE_JPEG_QUALITY, 92])
    cv2.imwrite(os.path.join(od, "bg.jpg"), cv2.cvtColor(L["rgb_bg"], cv2.COLOR_RGB2BGR), [cv2.IMWRITE_JPEG_QUALITY, 90])
    cv2.imwrite(os.path.join(od, "fg_depth.png"), pack16(M["c_fg"], None, M["alpha"])[..., ::-1])
    cv2.imwrite(os.path.join(od, "bg_depth.png"), pack16(L["c_bg"])[..., ::-1])
    cv2.imwrite(os.path.join(od, "orig.jpg"), bgr, [cv2.IMWRITE_JPEG_QUALITY, 90])
    # gallery thumbnail + tiny blurred "ambient glow" backdrop for the viewer
    th = 320 / max(bgr.shape[:2])
    cv2.imwrite(os.path.join(od, "thumb.jpg"), cv2.resize(bgr, None, fx=th, fy=th, interpolation=cv2.INTER_AREA), [cv2.IMWRITE_JPEG_QUALITY, 85])
    amb = cv2.resize(bgr, None, fx=96 / max(bgr.shape[:2]), fy=96 / max(bgr.shape[:2]), interpolation=cv2.INTER_AREA)
    cv2.imwrite(os.path.join(od, "ambient.jpg"), cv2.GaussianBlur(amb, (0, 0), 4), [cv2.IMWRITE_JPEG_QUALITY, 85])
    st = render.sbs(M["F"], z_fg, M["alpha"], L["rgb_bg"], z_bg)
    cv2.imwrite(os.path.join(od, "sbs.jpg"), cv2.cvtColor(st, cv2.COLOR_RGB2BGR), [cv2.IMWRITE_JPEG_QUALITY, 92])

    if gif:
        z_raw, _ = layers.grade(c, strength, subject=g["subject"])
        _compare_gif(rgb, z_raw, M, z_fg, L, z_bg, os.path.join(od, "compare.gif"))
        dbg = np.hstack([
            cv2.applyColorMap((c * 255).astype(np.uint8), cv2.COLORMAP_INFERNO),
            cv2.cvtColor(L["rgb_bg"], cv2.COLOR_RGB2BGR),
            _overlay(bgr, L["reveal"]),
        ])
        cv2.imwrite(os.path.join(od, "debug.jpg"), dbg, [cv2.IMWRITE_JPEG_QUALITY, 85])

    with open(os.path.join(od, "meta.json"), "w") as f:
        json.dump(meta, f, indent=1)
    return meta


def _overlay(bgr, mask):
    o = bgr.copy()
    o[mask] = (0.4 * o[mask] + 0.6 * np.array([255, 0, 255])).astype(np.uint8)
    return o


def _compare_gif(rgb, z_raw, M, z_fg, L, z_bg, path, amp=0.06, n=16):
    from PIL import Image
    h, w = z_fg.shape
    sc = min(1.0, 560 / w)
    frames = []
    for i in range(n):
        e = amp * np.sin(2 * np.pi * i / n)
        eye = (e, 0.25 * e)
        a = render.render_naive(rgb, z_raw, eye)
        b = render.render_layered(M["F"], z_fg, M["alpha"], L["rgb_bg"], z_bg, eye)
        a = cv2.resize(a, None, fx=sc, fy=sc, interpolation=cv2.INTER_AREA)
        b = cv2.resize(b, None, fx=sc, fy=sc, interpolation=cv2.INTER_AREA)
        bar = np.full((a.shape[0], 6, 3), 255, np.uint8)
        fr = np.hstack([a, bar, b])
        cv2.putText(fr, "typical converter", (8, 22), cv2.FONT_HERSHEY_SIMPLEX, 0.6, (255, 255, 255), 2)
        cv2.putText(fr, "layered + inpainted", (a.shape[1] + 14, 22), cv2.FONT_HERSHEY_SIMPLEX, 0.6, (255, 255, 255), 2)
        frames.append(Image.fromarray(fr))
    frames[0].save(path, save_all=True, append_images=frames[1:], duration=90, loop=0)


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("photos", nargs="+")
    ap.add_argument("--out", default="out")
    ap.add_argument("--strength", type=float, default=0.8)
    ap.add_argument("--no-gif", action="store_true")
    a = ap.parse_args()
    for p in a.photos:
        m = convert(p, a.out, a.strength, gif=not a.no_gif)
        print(json.dumps(m))
