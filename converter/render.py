"""Novel-view renderer (CPU point splatting) used for:
  * checking results without a headset (wiggle GIFs, before/after)
  * exporting side-by-side stereo JPEGs

Geometry matches the WebXR viewer: the photo is a window of width 1.0 at
distance D0; a pixel at depth z (window units) seen from an eye offset e
shifts on screen by  e * (1 - 1/z).  Parallax is linear in inverse depth.
"""
import numpy as np
import cv2


def _splat(rgb, z, eye, alpha=None):
    """Forward-splat one layer. Returns (colour, alpha, hit). Nearest sample wins."""
    h, w = z.shape
    ex, ey = eye
    gy, gx = np.mgrid[0:h, 0:w].astype(np.float32)
    k = 1.0 - 1.0 / z
    x = (gx + ex * k * w).ravel(); y = (gy + ey * k * w).ravel()
    col = rgb.reshape(-1, 3)
    a = (np.ones(h * w, np.float32) if alpha is None else alpha.ravel().astype(np.float32))
    key = (1.0 / z).ravel()
    keep = a > 0
    x, y, col, a, key = x[keep], y[keep], col[keep], a[keep], key[keep]
    offs = ((0, 0), (0.5, 0), (0, 0.5), (0.5, 0.5))      # 2x2 footprint closes hairline cracks
    xi = np.concatenate([np.floor(x + dx) for dx, _ in offs]).astype(np.int32)
    yi = np.concatenate([np.floor(y + dy) for _, dy in offs]).astype(np.int32)
    col = np.tile(col, (4, 1)); a = np.tile(a, 4); key = np.tile(key, 4)
    ok = (xi >= 0) & (xi < w) & (yi >= 0) & (yi < h)
    xi, yi, col, a, key = xi[ok], yi[ok], col[ok], a[ok], key[ok]
    o = np.argsort(key, kind="stable")                  # far first, near written last
    out = np.zeros((h, w, 3), np.uint8); oa = np.zeros((h, w), np.float32); hit = np.zeros((h, w), bool)
    out[yi[o], xi[o]] = col[o]; oa[yi[o], xi[o]] = a[o]; hit[yi[o], xi[o]] = True
    return out, oa, hit


def render_layered(fg, z_fg, alpha, bg, z_bg, eye):
    """Background layer, then the matted foreground blended over it."""
    b, _, hit = _splat(bg, z_bg, eye)
    if (~hit).any():
        b = cv2.inpaint(b, (~hit).astype(np.uint8), 3, cv2.INPAINT_TELEA)
    f, a, _ = _splat(fg, z_fg, eye, alpha)
    a = a[..., None]
    return (f * a + b * (1 - a) + 0.5).astype(np.uint8)


def render_naive(rgb, z, eye):
    """What typical converters do: warp one layer and smear the gaps."""
    img, _, hit = _splat(rgb, z, eye)
    if (~hit).any():
        img = cv2.inpaint(img, (~hit).astype(np.uint8), 3, cv2.INPAINT_TELEA)
    return img


def sbs(fg, z_fg, alpha, bg, z_bg, ipd=0.04):
    """Side-by-side stereo (left|right). ipd in window widths
    (0.04 ~ 64 mm eyes looking at a 1.6 m wide window)."""
    L = render_layered(fg, z_fg, alpha, bg, z_bg, (-ipd / 2, 0))
    R = render_layered(fg, z_fg, alpha, bg, z_bg, (+ipd / 2, 0))
    return np.hstack([L, R])
