"""Edge matting: the fix for fringes and halos.

Pixels along an object's outline are a blend of the object and whatever is
behind it (hair over a wall, fur against a sofa). A plain depth cut puts each
of those pixels wholly on one side, so a rim of background colour travels with
the subject (fringe) and bits of subject stay painted on the background
(ghost outline).

Here every pixel in a thin band around each depth edge is split into
  alpha  - how much of it belongs to the nearer object
  F      - the nearer object's own colour, with the background removed
and the band takes the nearer object's depth, so fine strands of hair and fur
move with the subject. The background layer is filled across the whole band,
so the alpha-blended edge sits over clean background.

Only box/gaussian filters and per-pixel maths: ports directly to a GPU shader.
"""
import numpy as np
import cv2


def _disk(r):
    return cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (2 * r + 1, 2 * r + 1))


def _nconv(val, w, sigma):
    """Normalised convolution: average of val over pixels where w>0."""
    num = cv2.GaussianBlur(val * (w[..., None] if val.ndim == 3 else w), (0, 0), sigma)
    den = cv2.GaussianBlur(w, (0, 0), sigma)
    d = den[..., None] if val.ndim == 3 else den
    return num / np.maximum(d, 1e-6), den


def _guided(I, p, r, eps):
    """Colour guided filter (He et al.) - edge-aware smoothing of alpha."""
    mean = lambda x: cv2.boxFilter(x, -1, (2 * r + 1, 2 * r + 1))
    mI = mean(I); mp = mean(p)
    cov_Ip = mean(I * p[..., None]) - mI * mp[..., None]
    var = np.empty(I.shape[:2] + (3, 3), np.float32)
    for i in range(3):
        for j in range(i, 3):
            v = mean(I[..., i] * I[..., j]) - mI[..., i] * mI[..., j]
            var[..., i, j] = var[..., j, i] = v
    var += eps * np.eye(3, dtype=np.float32)
    a = np.linalg.solve(var, cov_Ip[..., None])[..., 0]
    b = mp - (a * mI).sum(-1)
    return (mean(a) * I).sum(-1) + mean(b)


def matte(rgb, c, K, band_frac=1 / 200):
    """rgb uint8 HxWx3, c closeness 0..1, K = px of parallax per unit closeness.

    Returns dict:
      alpha  HxW float, 1 = opaque foreground
      F      HxWx3 uint8 foreground colour (decontaminated inside the band)
      c_fg   HxW closeness for the foreground layer (band takes the near depth)
      band   HxW bool, the edge band (must be filled in the background layer)
    """
    h, w = c.shape
    I = rgb.astype(np.float32) / 255.0
    r = max(3, int(round(max(h, w) * band_frac)))          # band half-width (~8 px at 1600)

    # depth edges worth matting: a jump that would open >1.5 px when leaning
    k3 = np.ones((3, 3), np.uint8)
    jump = (cv2.dilate(c, k3) - cv2.erode(c, k3)) > max(0.045, 1.5 / K)
    band = cv2.dilate(jump.astype(np.uint8), _disk(r)) > 0
    if not band.any():
        return {"alpha": np.ones((h, w), np.float32), "F": rgb.copy(), "B": rgb.copy(), "c_fg": c.copy(),
                "c_bgband": c.copy(), "band": band}

    # local near / far depth levels around each edge
    hi = cv2.dilate(c, _disk(2 * r)); lo = cv2.erode(c, _disk(2 * r))
    gap = np.maximum(hi - lo, 1e-4)
    knownF = ((~band) & (c > hi - 0.35 * gap)).astype(np.float32)
    knownB = ((~band) & (c < lo + 0.35 * gap)).astype(np.float32)

    sig = r * 0.9
    Fe, wF = _nconv(I, knownF, sig)
    Be, wB = _nconv(I, knownB, sig)
    cF, _ = _nconv(c, knownF, sig)
    cBe, _ = _nconv(c, knownB, sig)

    # colour-based alpha: project each pixel onto the line from B to F
    d = Fe - Be
    a_col = np.clip(((I - Be) * d).sum(-1) / ((d * d).sum(-1) + 1e-4), 0, 1)
    # depth-based alpha: fallback where F and B colours are too similar to tell
    # (e.g. arm against torso). A near-binary step at the depth midpoint, so
    # such edges stay opaque on the near side and fully original on the far side.
    mid = 0.5 * (hi + lo)
    a_dep = np.clip((c - mid) / (0.12 * gap) + 0.5, 0, 1)
    a_dep = cv2.GaussianBlur(a_dep, (0, 0), 0.7)
    conf = np.clip(np.sqrt((d * d).sum(-1)) / 0.15, 0, 1)
    conf *= np.clip(np.minimum(wF, wB) * 6, 0, 1)            # need both sides nearby
    # soft transparency only across real occlusions (big depth gap); small
    # gaps inside a subject (collar vs chest, arm vs torso) get a clean step
    conf *= np.clip((gap - 0.06) / 0.12, 0, 1)
    a = conf * a_col + (1 - conf) * a_dep
    # the body of the nearer object is never see-through: colour only decides
    # inside a thin rim at the outline and for strands beyond it
    rim = max(2, r // 3)
    near_core = cv2.erode((c > mid).astype(np.uint8), _disk(rim)) > 0
    a = np.where(near_core, 1.0, a)

    # edge-aware cleanup, then keep it inside the band
    a = np.clip(_guided(I, a.astype(np.float32), 2, 1e-4), 0, 1)
    a = np.where(band, a, 1.0).astype(np.float32)
    a[a < 0.04] = 0.0
    a[a > 0.97] = 1.0

    # decontaminated foreground colour: solve I = a*F + (1-a)*B where alpha is
    # trustworthy, else use the propagated object colour
    Fs = (I - (1 - a[..., None]) * Be) / np.maximum(a[..., None], 1e-3)
    t = np.clip((a - 0.25) / 0.5, 0, 1)[..., None]
    Fo = np.clip(t * Fs + (1 - t) * np.where(wF[..., None] > 1e-3, Fe, I), 0, 1)
    F = np.where(band[..., None], Fo, I)

    # clean background colour for the far side of the band: remove the
    # foreground's share from mixed pixels (strands over a wall etc.)
    Bs = (I - a[..., None] * Fo) / np.maximum(1 - a[..., None], 1e-3)
    tb = np.clip((0.8 - a) / 0.5, 0, 1)[..., None]
    Bo = np.clip(tb * Bs + (1 - tb) * np.where(wB[..., None] > 1e-3, Be, I), 0, 1)
    B = np.where(band[..., None], Bo, I)

    # the band rides with the nearer surface (strands move with the head)
    c_near = np.where(wF > 1e-3, np.maximum(cF, c), hi)
    c_fg = np.where(band & (a > 0), np.maximum(c, c_near), c).astype(np.float32)

    # depth of the far surface across the band (for the background layer)
    c_bgb = np.where(wB > 1e-3, np.minimum(cBe, c), lo).astype(np.float32)

    return {"alpha": a, "F": (F * 255 + 0.5).astype(np.uint8), "B": (B * 255 + 0.5).astype(np.uint8),
            "c_fg": c_fg, "c_bgband": c_bgb, "band": band}
