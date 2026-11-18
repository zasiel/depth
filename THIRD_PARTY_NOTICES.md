# Third-party components

| Component | Used for | License |
|---|---|---|
| Depth Anything V2 **Small** (weights + code), ONNX export by fabio-sim | Depth estimation | Apache-2.0 |
| MI-GAN (Picsart AI Research), 512 Places2 generator | Background fill (`models/migan.onnx`, converted to ONNX) | MIT |
| three.js | 3D viewer | MIT |
| mp4-muxer | MP4 packaging of exported videos | MIT |
| ONNX Runtime | Model inference | MIT |
| OpenCV (+ contrib) | Image processing | Apache-2.0 |

Not used, by design (non-commercial or research-only licenses): Depth Anything V2
Base/Large/Giant, Video Depth Anything Large, Apple Depth Pro, Apple SHARP.

MI-GAN and most image models are trained on public datasets (Places2 here) whose
terms are a general caveat for commercial products; get a legal review before launch.
