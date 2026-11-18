"""Background fill. Uses MI-GAN (MIT, ~27 MB, built for phones) via ONNX when
models/migan.onnx exists; otherwise falls back to OpenCV Telea (no model).

MI-GAN works at 512x512. Strips behind object edges are scattered across the
whole photo, so we inpaint 512 px tiles cut around each hole region at a scale
where the strip is reasonably sized, then paste back only the hole pixels.
"""
import os
import numpy as np
import cv2

MODEL = os.environ.get("MIGAN_ONNX", os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "models", "migan.onnx"))
_sess = None
NAME = "migan" if os.path.exists(MODEL) else "opencv-telea"


def _session():
    global _sess
    if _sess is None:
        import onnxruntime as ort
        _sess = ort.InferenceSession(MODEL, providers=["CPUExecutionProvider"])
    return _sess


def _migan512(img, mask):
    """img 512x512x3 uint8, mask 512x512 bool (True = fill)."""
    x = img.astype(np.float32) / 127.5 - 1.0
    m = mask.astype(np.float32)
    x = x.transpose(2, 0, 1)[None]
    m = m[None, None]
    inp = np.concatenate([0.5 - m, x * (1 - m)], axis=1).astype(np.float32)
    s = _session()
    out = s.run(None, {s.get_inputs()[0].name: inp})[0][0]
    return np.clip(out.transpose(1, 2, 0) * 127.5 + 127.5, 0, 255).astype(np.uint8)


def inpaint(rgb, hole):
    # generate a few px beyond the requested hole so the caller can feather the
    # seam, and so mixed edge pixels never act as context for the fill
    hole = cv2.dilate(hole.astype(np.uint8), cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (9, 9))) > 0
    if NAME != "migan":
        return cv2.inpaint(rgb, hole.astype(np.uint8), 5, cv2.INPAINT_TELEA)
    h, w = hole.shape
    out = rgb.copy()
    # global pass at 512 for coarse structure everywhere
    side = max(h, w)
    # reflect-pad to square so the model never sees fake black borders
    sq = cv2.copyMakeBorder(rgb, 0, side - h, 0, side - w, cv2.BORDER_REFLECT_101)
    sm = cv2.copyMakeBorder(hole.astype(np.uint8), 0, side - h, 0, side - w, cv2.BORDER_REFLECT_101) > 0
    # low-res mask: any coverage counts, then grow 1 px, so subject colours
    # averaged into boundary pixels by the downscale are never kept as context
    sm512 = cv2.resize(sm.astype(np.float32), (512, 512), interpolation=cv2.INTER_AREA) > 0.01
    sm512 = cv2.dilate(sm512.astype(np.uint8), np.ones((3, 3), np.uint8)) > 0
    g = _migan512(cv2.resize(sq, (512, 512), interpolation=cv2.INTER_AREA), sm512)
    g = cv2.resize(g, (side, side), interpolation=cv2.INTER_CUBIC)[:h, :w]
    out[hole] = g[hole]
    # detail pass: native-resolution 512 tiles over hole regions
    if side > 640:
        step = 384
        for y0 in range(0, max(1, h - 128), step):
            for x0 in range(0, max(1, w - 128), step):
                y1, x1 = min(h, y0 + 512), min(w, x0 + 512)
                ya, xa = max(0, y1 - 512), max(0, x1 - 512)
                tm = hole[ya:y1, xa:x1]
                if tm.mean() < 0.002:
                    continue
                ti = out[ya:y1, xa:x1].copy()
                th, tw = tm.shape
                pi = np.zeros((512, 512, 3), np.uint8); pi[:th, :tw] = ti
                pm = np.zeros((512, 512), bool); pm[:th, :tw] = tm
                r = _migan512(pi, pm)[:th, :tw]
                # feather tile seams inside the hole
                f = 48
                ramp = np.minimum(np.arange(th)[:, None], np.arange(th)[::-1][:, None])
                rampx = np.minimum(np.arange(tw)[None], np.arange(tw)[::-1][None])
                wgt = np.clip(np.minimum(ramp, rampx) / f, 0, 1)
                blend = (ti * (1 - wgt[..., None]) + r * wgt[..., None]).astype(np.uint8)
                sub = out[ya:y1, xa:x1]
                sub[tm] = blend[tm]
    return out
