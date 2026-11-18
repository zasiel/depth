"""Step 1: monocular depth with Depth Anything V2 Small (Apache-2.0), ONNX Runtime.

Two passes, fused:
  * global pass at ~518 px  -> reliable overall scene layout
  * detail pass at ~1036 px -> crisp object boundaries (hair, handlebars, ears)
Low frequencies come from the global pass, high frequencies from the detail
pass. Soft depth edges are what make cheap converters look like rubber sheets,
so this matters more than model size.

Output: "closeness" (relative inverse depth) 0..1, 1 = nearest.
"""
import time
import numpy as np
import cv2
import onnxruntime as ort

_MEAN = np.array([0.485, 0.456, 0.406], np.float32)
_STD = np.array([0.229, 0.224, 0.225], np.float32)
_SESS = {}


def _session(path):
    if path not in _SESS:
        so = ort.SessionOptions()
        so.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
        _SESS[path] = ort.InferenceSession(path, so, providers=["CPUExecutionProvider"])
    return _SESS[path]


def _infer(sess, rgb, long_side):
    h, w = rgb.shape[:2]
    s = long_side / max(h, w)
    th, tw = max(14, int(round(h * s / 14)) * 14), max(14, int(round(w * s / 14)) * 14)
    x = cv2.resize(rgb, (tw, th), interpolation=cv2.INTER_AREA if s < 1 else cv2.INTER_CUBIC)
    x = ((x.astype(np.float32) / 255.0 - _MEAN) / _STD).transpose(2, 0, 1)[None]
    name = sess.get_inputs()[0].name
    d = sess.run(None, {name: x})[0][0]
    d2 = sess.run(None, {name: x[..., ::-1].copy()})[0][0][:, ::-1]  # flip TTA
    d = 0.5 * (d + d2)
    return cv2.resize(d, (w, h), interpolation=cv2.INTER_CUBIC)


def _norm(d):
    lo, hi = np.percentile(d, [0.5, 99.5])
    return np.clip((d - lo) / max(hi - lo, 1e-6), 0, 1).astype(np.float32)


def estimate(rgb, model_path, detail_long_side=1036):
    """rgb: HxWx3 uint8 (any size). Returns (closeness HxW float32, seconds)."""
    sess = _session(model_path)
    h, w = rgb.shape[:2]
    # work at <= 2048 px; results are upsampled to the photo at the end
    s = min(1.0, 2048 / max(h, w))
    small = cv2.resize(rgb, (int(w * s), int(h * s)), interpolation=cv2.INTER_AREA) if s < 1 else rgb
    t = time.time()
    g = _infer(sess, small, 518)
    if max(small.shape[:2]) > 600:
        dt = _infer(sess, small, min(detail_long_side, max(small.shape[:2]) // 14 * 14))
        # align detail pass to global pass (scale + shift), then frequency-split fuse
        A = np.stack([dt.ravel(), np.ones(dt.size)], 1)
        a, b = np.linalg.lstsq(A[::7], g.ravel()[::7], rcond=None)[0]
        dt = a * dt + b
        sig = max(small.shape[:2]) / 60
        fused = cv2.GaussianBlur(g, (0, 0), sig) + (dt - cv2.GaussianBlur(dt, (0, 0), sig))
    else:
        fused = g
    secs = time.time() - t
    d = _norm(fused)
    d = _snap_edges(d, small)
    if s < 1:
        d = cv2.resize(d, (w, h), interpolation=cv2.INTER_LINEAR)
    return d, secs


def _snap_edges(d, rgb):
    """One light weighted-median pass: turns leftover ramps at boundaries into
    steps without the spiky noise that heavier filtering produces."""
    try:
        import cv2.ximgproc as xip
        r = max(2, int(round(max(d.shape) / 400)))
        d = xip.weightedMedianFilter(rgb, d, r, 12)
    except Exception:
        pass
    # Remove small ripples (textured floors, fabric) while keeping real steps:
    # a self-guided filter flattens variations below ~0.04 and leaves larger
    # jumps untouched. Ripples would otherwise read as tiny edges later.
    try:
        import cv2.ximgproc as xip
        rr = max(3, int(round(max(d.shape) / 250)))
        d = xip.guidedFilter(d, d, rr, 0.04 ** 2)
    except Exception:
        pass
    # Hard step at real depth edges: inside a thin zone where depth changes a
    # lot, every pixel goes fully to the near or the far value. Without this,
    # in-between "flying pixels" smear background colour along object edges.
    r = max(2, int(round(max(d.shape) / 500)))
    k = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (2 * r + 1, 2 * r + 1))
    mx, mn = cv2.dilate(d, k), cv2.erode(d, k)
    zone = (mx - mn) > 0.10
    snapped = np.where(d > 0.5 * (mx + mn), mx, mn)
    d = np.where(zone, snapped, d)
    return np.clip(d, 0, 1).astype(np.float32)
