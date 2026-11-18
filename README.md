# Depth — 2D → 3D photo converter for Meta Quest (prototype)

Turns one ordinary photo into a two-layer 3D scene you can lean into: objects
separate from what is behind them, and the background they were hiding is
filled in, so moving your head reveals new detail instead of stretching the
picture. Everything runs offline with small models that are licensed for
commercial use.

## What's in here

| Path | What it does |
|---|---|
| `converter/depth.py` | Depth Anything V2 Small (ONNX). Two passes: ~518 px for overall layout and ~1036 px for crisp edges, fused, then snapped into hard steps at object edges. |
| `converter/layers.py` | Finds the strips behind each edge that leaning can uncover. Strip width scales with the depth jump. It extends the background depth into them and grades depth like a 3D film: subject at the window, near things slightly in front, background pushed back. |
| `converter/matte.py` | Edge matting, the fix for fringes and halos. Around every real depth edge it gives each pixel a soft transparency and a cleaned subject color. Fine strands of hair and fur ride with the subject, and the background side keeps its real color. |
| `converter/inpaint.py` | MI-GAN (ONNX) fills those strips: a global 512 px pass plus native-resolution 512 px tiles. Falls back to OpenCV if the model is missing. |
| `converter/convert.py` | Command line tool. Writes each photo's layers plus a side-by-side stereo JPEG and a before/after GIF. |
| `converter/render.py` | CPU renderer for the stereo JPEG and the GIFs. |
| `converter/build_site.py` | Packs converted photos and the viewer into a static site. |
| `docs/` | The ready-to-view site (viewer + converted sample photos), served by GitHub Pages. |
| `viewer/app.js`, `viewer/index.src.html` | three.js viewer. On desktop or phone the pointer leans your head; on Quest you get WebXR with real head tracking. `viewer/index.html` is the built single-file version. |
| `models/migan.onnx` | MI-GAN 512 Places2, converted by me from the official TorchScript. Matches PyTorch to within 1e-4. |
| `scripts/fetch_models.sh` | Downloads Depth Anything V2 Small (ONNX). |
| `scripts/export_migan_onnx.py` | Reproduces the MI-GAN ONNX conversion. |

## Run it

```bash
pip install -r converter/requirements.txt
bash scripts/fetch_models.sh                      # depth model -> models/
cd converter
python3 convert.py ~/Pictures/*.jpg --out ../converted
python3 build_site.py ../converted ../docs         # rebuild the Pages site with your photos
python3 -m http.server -d ../docs 8000             # desktop preview at http://localhost:8000
```

On Windows, download the depth model with the URL inside `scripts/fetch_models.sh`
and save it into `models/`.

Controls:
- **Desktop or phone:**
  - Pointer to lean; click the photo to set the focus (that point sits at the window).
  - Thumbnail gallery; ←/→ to change photo; Space toggles "Typical converter" vs "Layered".
  - **3D:** Depth, Pop-out, Focus (click / Auto).
  - **View:** Size, Distance, Rotate ±90° (R key), Tilt, Turn.
  - **Motion:** Auto-sway (Off / Figure-8 / Side to side / Circle) with Amount and Speed. Sway works in the headset too, because it moves the photo's layers rather than the camera.
  - **Display:** frame (None / Thin / Gallery / Soft edges), Ambient glow, Slideshow (8 s), Show depth.
  - **Export:**
    - 3D image: side-by-side, left-eye then right-eye (`_3D_LR.jpg`).
    - Red/cyan 3D image.
    - 6-second looping sway video: H.264 MP4, every frame rendered, so it's smooth on any device.
    - Depth map PNG.
    - On claude.ai, files come through the page's download prompt; anywhere else they download normally.
  - Reset clears this photo's settings. Settings are remembered per photo.
- **Quest (WebXR):**
  - A control bar and thumbnail strip float under the photo; point and pull the trigger to press.
  - Trigger on the photo sets the focus.
  - Grip moves and rotates the window; grip with both hands resizes it.
  - Thumbstick ←/→ changes photo, ↑/↓ changes depth.
  - A/X toggles the comparison; B/Y hides the controls. The Sway button cycles the sway modes.
  - Recenter brings the window back in front of you.

## Viewing on the Quest

WebXR only works over **https** or `localhost`, so plain `http://<your-PC-IP>` won't work. Easiest options:

1. **GitHub Pages:** in the repo's Settings → Pages, set the source to branch `main`, folder `/docs`. Open the `https://…github.io/depth/` link in the Meta Quest Browser and tap **Start XR**. Passthrough (MR) is used when the browser supports it, otherwise VR.
2. **USB:** `adb reverse tcp:8000 tcp:8000`, then open `http://localhost:8000` in the Quest Browser.

The window appears 1.7 m in front of you, 1.5 m wide. Lean, crouch and step side to side.

## Measured (this prototype, 2-core cloud CPU, no GPU)

| Photo | Depth (2 passes + flip) | Layers + inpaint |
|---|---|---|
| 1482×1000 | 13–14 s | 17–19 s |
| 512–640 px | 2–4 s | 1.4–1.8 s |

This is a slow CPU. The Quest 3's Adreno GPU via LiteRT/ONNX Runtime should be several times faster, but **that's not measured yet.** Photos only need converting once, so a few seconds each on device is acceptable.

## Licenses (everything that ships)

| Component | License | Notes |
|---|---|---|
| Depth Anything V2 **Small** weights + code | Apache-2.0 | Base/Large/Giant are CC-BY-NC: **do not ship those.** |
| MI-GAN code + weights | MIT | Trained on Places2. Dataset terms are a general caveat for nearly every image model; get a lawyer's read before launch. |
| three.js | MIT | |
| mp4-muxer | MIT | Packages the exported video as MP4. |
| ONNX Runtime / OpenCV | MIT / Apache-2.0 | |

Deliberately **not** used: Apple SHARP and Depth Pro (research-only licenses), and Video Depth Anything Large (non-commercial).

## Known issues / next steps

- **Thin structures** (towers, spokes) survive but can look sparse when leaning hard. Next: more mesh density near edges, or point-splat rendering for the foreground.
- **Hair and fur edges:** matting added (`matte.py`). Very fine strands beyond about 8 px from the outline still stay on the background.
- **Fill quality** is MI-GAN level: good for short strips, blotchy for very wide ones. LaMa (Apache-2.0) is an optional higher-quality pass (weights on IOPaint's GitHub releases; ONNX export needs opset ≥17 for FFT).
- **Leaning past about 15 cm** at 1.7 m goes beyond what the fill was sized for. Raise `max_eye` in `layers.py` and `cut_mask()` together to trade compute for freedom.
- **Quest native app:** port `depth.py`, `layers.py` and `inpaint.py` to Kotlin with ONNX Runtime Android or LiteRT (GPU delegate). Reuse this exact shader in a Spatial SDK or Unity mesh. All of the math is image-processing that ports 1:1 (morphology, blur, distance transform).
