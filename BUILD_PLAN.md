# Cyote3D — Build Plan (sideloadable Quest 3 app)

Working name **Cyote3D** (temporary). Package id `com.cyote3d.app` (placeholder; changing it later
means a reinstall, which is fine during testing).

One native app for Meta Quest 3 that imports photos, converts them to layered 3D **on the headset**
(no server, no cloud, no accounts), keeps them in a gallery, and shows them as head-tracked 3D
"windows" in the room. Built automatically by GitHub Actions into an APK that is sideloaded.

The Python prototype in `converter/` and the web viewer in `viewer/app.js` are the **reference
implementation**: port their behaviour, don't reinvent it. When in doubt, match their output.

---

## 1. Architecture

| Layer | Choice | Notes |
|---|---|---|
| App framework | **Meta Spatial SDK** (Kotlin), latest stable | Immersive activity + Compose panels. Template: Meta "Spatial SDK template" / StarterSample. |
| UI | **Jetpack Compose** panels in 3D space | All buttons/theme custom (see §4). |
| 3D photo | Custom `SceneMesh` grid + **custom GLSL shader** via `SceneMaterial.custom()` | Experimental SDK feature — prove it first (Milestone 0). Port shaders from `viewer/app.js`. |
| Inference | **ONNX Runtime Android** (`com.microsoft.onnxruntime:onnxruntime-android`), CPU/XNNPACK | Same .onnx files as the prototype. GPU (LiteRT) only later, if profiling says so. |
| Image ops | **OpenCV Android** (`org.opencv:opencv` from Maven Central) | `ximgproc` is NOT in the Android build: implement guided filter with box filters; replace weighted median with a joint-bilateral pass (minor quality impact, acceptable). |
| Phone upload | Tiny LAN HTTP server (NanoHTTPD or Ktor CIO) inside the app | Phone browser → upload page → photos land in the import queue. LAN only, no outbound traffic. |
| Storage | App-private `filesDir/photos/<id>/` | Same file set as the converter (see §3) so the web viewer can also open them for debugging. |
| Build | GitHub Actions → `app-debug.apk` artifact | See §6. Debug-signed is fine for sideloading. |

Models shipped in `assets/models/`:
- `depth_anything_v2_vits_dynamic.onnx` (Apache-2.0) — fetched in CI by `scripts/fetch_models.sh`, not in git.
- `migan.onnx` (MIT) — in `models/`.

Permissions: `INTERNET` (needed only for the local upload server socket), media read for the picker.
No analytics, no network calls to any host. State this in-app ("Your photos never leave this headset").

---

## 2. Conversion pipeline (port of `converter/`)

Order and constants must match the Python. File references are the source of truth.

1. **Load + resize** to max 1600 px long side, honour EXIF orientation. (`convert.py`)
2. **Depth** (`depth.py`): Depth Anything V2 Small, two passes
   - global at long side 518, detail at long side 1036 (multiples of 14), ImageNet normalisation;
   - **drop the flip-TTA on device** (halves cost);
   - fuse: align detail to global with least squares (scale+shift), low-freq from global
     (Gaussian σ = long/60) + high-freq from detail;
   - normalise to 0..1 by 0.5/99.5 percentiles;
   - edge cleanup: (weighted median → joint bilateral), **self-guided filter** (r = max(3, long/250),
     eps = 0.04²), then **snap**: inside zones where local max−min > 0.10 (radius max(2, long/500)),
     set each pixel to local max or min, whichever side of the midpoint it is on.
3. **Matting** (`matte.py`): band around depth jumps > max(0.045, 1.5/K) where
   K = 0.10 × strength(0.8) × width; trimap from local hi/lo depth; normalised-convolution F/B colour
   estimates; colour alpha with confidence (colour distance, gap size, both sides present);
   depth-step fallback; guided-filter refine; **near core forced opaque**; decontaminated F;
   cleaned B; `c_fg` (band takes the near depth); `c_bgband`.
4. **Layers** (`layers.py`): multi-scale parallax-aware reveal mask; inward hole growth scaled by
   the depth gap; hole ∪ (band & alpha≥0.5); background depth = smooth "pushed back" surface, with
   the far-surface depth across the band.
5. **Fill** (`inpaint.py`): MI-GAN 512×512, input = [0.5−mask, image×(1−mask)] in [−1,1]; global
   pass on a reflect-padded square + native-res 512 tiles (step 384) with feathered blending;
   mask dilated 4 px; feathered seam (±2 px) when compositing.
6. **Grade** (`layers.grade` / `subject_closeness`): subject = weighted-histogram peak of
   "stands out + central" pixels.
7. **Write** the photo folder (§3), plus `thumb.jpg` (320 px) and `ambient.jpg` (96 px, blurred).

Run conversion in a foreground service / background coroutine with a queue; one photo at a time;
progress per stage (depth 40 %, matte 10 %, layers+fill 45 %, write 5 %). Log stage timings to
`meta.json` → `seconds`.

**Acceptance:** for the six photos in `docs/photos/*/orig.jpg`, on-device output vs the Python
output: mean |Δcloseness| < 0.03; no visible fringe/halo in the viewer at full lean (compare
against the published Pages viewer).

---

## 3. Photo folder format (identical to the converter)

`fg.jpg` (decontaminated foreground colour) · `bg.jpg` (filled background) · `orig.jpg` ·
`fg_depth.png` (R,G = 16-bit closeness hi/lo byte; **B = foreground alpha**) ·
`bg_depth.png` (R,G = 16-bit closeness) · `thumb.jpg` · `ambient.jpg` ·
`meta.json` (`width`, `height`, `grading{P, subject, z_near, z_far}`, `seconds`, `created`, `title`).
Per-photo viewer settings (depth, pop, focus, roll, …) in `settings.json`.

---

## 4. UI — modern, clean, minimal

Dark theme, translucent panels, one accent, generous spacing, line icons. Everything uses these tokens.

| Token | Value |
|---|---|
| Panel background | `#0E0F13` @ 88 % |
| Surface / elevated | `#17181E` / `#1F2028` |
| Stroke | `#2A2B35` (1 dp) |
| Text primary / secondary / muted | `#F2F1F6` / `#A3A1AF` / `#6E6C7A` |
| Accent / pressed / soft | `#7C6CF2` / `#6656E0` / `#7C6CF2` @ 16 % |
| Success / danger | `#3CC48A` / `#E5596A` |
| Radius | panels 24 dp, buttons 14 dp, thumbnails 12 dp, pills 999 dp |
| Spacing | 4/8/12/16/24/32 dp scale |
| Type | Inter (OFL, bundled): Title 22/600, Body 15/400, Label 13/500, Caption 12/400 |
| Icons | Material Symbols Rounded (Apache-2.0), 20 dp, stroke style |
| Motion | 160 ms ease-out for hover/press; 240 ms for panel transitions; no bounce |

Controller/hand ray hover = surface lightens + 1 dp accent stroke. Minimum hit target 48 dp.

### Screens (world-locked panels, ~1.4 m in front, slightly below eye line)

1. **Library** (main panel ~ 1200×760 dp)
   - Top bar: logo mark + "Cyote3D" (left); **Import** (accent button) and **From phone** (ghost
     button) (right).
   - Grid of thumbnails (4 per row, 12 dp gaps), each with a small "3D" chip. Converting items show
     a circular progress ring + stage label ("Depth…", "Edges…", "Filling…").
   - Empty state: illustration-free; one line ("Bring in a photo to make it 3D") + the two buttons.
   - Footer caption: "Processed on this headset. Nothing is uploaded."
2. **Import sheet** (slides over Library)
   - "From this headset" → system picker (multi-select).
   - "From your phone" → QR code + `http://<quest-ip>:<port>` + live list of received files.
     Phone page: same theme, big "Choose photos" button, upload progress, works on iOS/Android.
3. **Viewer**
   - The 3D photo window (default 1.5 m wide at 1.7 m), ambient glow behind it.
   - Floating pill control bar under the photo: Back · ◀ ▶ · Depth slider · Pop-out slider ·
     Sway (toggle; long-press for mode/amount) · Compare (hold to show "typical converter") · ⋯ More.
   - **More** sheet: Focus: Auto · Rotate 90° · Size · Frame (None/Thin/Gallery/Soft) · Glow ·
     Export 3D image (SBS) · Delete.
   - Trigger on the photo = set focus there (toast "Focus set"). Grip = move window; both grips =
     resize. Thumbstick ←/→ = prev/next, ↑/↓ = depth. Hide controls after 4 s idle; any input shows them.

---

## 5. Viewer rendering (port of `viewer/app.js`)

- Mesh: grid with ~1 vertex per texel, max 1280 columns (fg); half density for bg.
- Vertex shader: decode closeness from `depthMap` (R,G); `z = 1/(invFar + c·P)` with
  `invFar = 1 − subj·P`; place along the ray from the (swaying) virtual eye E through the window point;
  window plane at local z = 0.
- Fragment shader (fg): cut the fragment if |interpolated c − c sampled at this texel| > tearThr
  (`max(0.04, 1.5/(0.1·P·width))`); alpha from the **linearly filtered** B channel; discard a < 0.02;
  blend over the bg layer. bg layer: opaque, never cut, polygon offset behind.
- Compare mode: fg uses `orig.jpg`, no alpha, no cutting, bg hidden.
- Auto-sway: animate E (figure-8 default, amount 0.5, speed 1.0; modes side/circle/off).
- Export SBS: render left/right with parallel cameras ±32 mm and a shifted frustum so the window
  plane has zero parallax; save `Pictures/Cyote3D/<name>_3D_LR.jpg` via MediaStore.

If the SDK's custom-shader path can't do vertex texture fetch, fall back to computing vertex
positions on the CPU in Kotlin (update on slider change; sway by moving the virtual eye via a
uniform-equivalent or by rebuilding at ≤15 Hz).

---

## 6. CI: `.github/workflows/android.yml`

- Triggers: push to `main`, `workflow_dispatch`.
- `ubuntu-latest`; `actions/setup-java` (Temurin 17); `android-actions/setup-android`;
  install the NDK version the Spatial SDK Gradle plugin requires (custom shaders need it).
- `bash scripts/fetch_models.sh`, copy both models into `app/src/main/assets/models/`.
- Copy `docs/photos/*` into `assets/samples/` so a fresh install has demo photos.
- `./gradlew assembleDebug` → upload `Cyote3D-debug.apk` as a workflow artifact.

Install: download the artifact (Actions tab → latest run), then `adb install -r Cyote3D-debug.apk`
or drag into SideQuest. Developer mode must be on.

---

## 7. Milestones (each ends with an APK Zach tests on the Quest)

| # | Deliverable | Done when |
|---|---|---|
| **M0** | Skeleton + **render proof**: Spatial SDK app, Library panel with the bundled sample photos, tapping one opens it in the 3D viewer with the custom shader (depth, alpha edges, cutting, bg layer). CI builds the APK. | Zach sees the samples in 3D in the headset, edges look like the Pages viewer. |
| **M1** | On-device conversion (ONNX Runtime CPU) from the headset picker, queue + progress, results saved to the gallery. | Acceptance in §2 passes; per-photo time logged. |
| **M2** | Phone upload over Wi-Fi (QR + URL + themed upload page). | Photos sent from a phone appear and convert. |
| **M3** | Viewer polish: control bar, sway modes, focus, rotate, size, frames, glow, compare, export SBS, delete, auto-hide. | Feature walkthrough passes; nothing janky at 72 Hz. |
| **M4** | Performance: profile; move depth and/or MI-GAN to the GPU (LiteRT) if a 12 MP photo takes > 20 s. | Timings recorded before/after. |

## 8. Known unknowns

- Spatial SDK custom shaders are marked experimental; M0 exists to retire this risk early.
- Quest 3 CPU speed for the two depth passes + MI-GAN tiles (estimate 15–30 s/photo; unmeasured).
- Memory: keep peak under ~1.5 GB (process one photo at a time, release Mats promptly).
- `ximgproc` replacements may shift results slightly; judge in the headset, not by numbers alone.
