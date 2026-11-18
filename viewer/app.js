// Depth Window viewer v2 — layered 3D photos on desktop/phone and in WebXR (Quest).
//
// Scene graph (all lengths in metres):
//   scene
//    └─ root      placement in the room (XR: in front of the user; grab to move)
//        ├─ frame  user transform: size (scale), roll/tilt/turn
//        │   ├─ ambient  blurred glow far behind the photo
//        │   ├─ content  bg + fg layer meshes (window plane at local z = 0)
//        │   └─ border   optional picture frame
//        └─ ui     XR control bar + thumbnail strip (follows the window, never rotates/scales)
//
// A pixel with closeness c is placed on the ray from the nominal eye (WIN_D in
// front of the window) through its window position, at z = 1/(invFar + c*P)
// window-distances. P = depth strength, subject closeness sits at the window.
import * as THREE from 'three';
import { XRButton } from 'three/examples/jsm/webxr/XRButton.js';
import { Muxer, ArrayBufferTarget } from 'mp4-muxer';

const WIN_W = 1.0;      // window width in frame units (frame.scale = size in metres)
const WIN_D = 1.15;     // nominal eye distance in frame units
const $ = (id) => document.getElementById(id);
const clamp = (v, a, b) => Math.min(b, Math.max(a, v));

// ---------------------------------------------------------------- state
const DEFAULTS = { strength: 0.8, pop: 0, focus: null, roll: 0, tilt: 0, turn: 0 };
const state = {
  photos: [], idx: 0, mode: 'layered', swayMode: 'eight', swayAmt: 0.5, swaySpeed: 1, showDepth: false,
  glow: true, frameStyle: 'thin', slideshow: false, size: 1.5, distance: 1.7,
  per: {},                         // per-photo settings, remembered
};
function loadPrefs() {
  try { Object.assign(state.per, JSON.parse(localStorage.getItem('dw.per') || '{}')); } catch (e) {}
  try { const g = JSON.parse(localStorage.getItem('dw.global') || '{}');
    for (const k of ['glow', 'frameStyle', 'size', 'distance', 'mode', 'swayMode', 'swayAmt', 'swaySpeed']) if (k in g) state[k] = g[k]; } catch (e) {}
}
function savePrefs() {
  try { localStorage.setItem('dw.per', JSON.stringify(state.per)); } catch (e) {}
  try { localStorage.setItem('dw.global', JSON.stringify({ glow: state.glow, frameStyle: state.frameStyle, size: state.size, distance: state.distance, mode: state.mode, swayMode: state.swayMode, swayAmt: state.swayAmt, swaySpeed: state.swaySpeed })); } catch (e) {}
}
const cur = () => state.photos[state.idx];
function P() { const p = cur(); if (!p) return { ...DEFAULTS }; return (state.per[p.name] ||= { ...DEFAULTS }); }

// ---------------------------------------------------------------- renderer
const renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true });
renderer.setPixelRatio(Math.min(devicePixelRatio, 2));
renderer.setSize(innerWidth, innerHeight);
renderer.xr.enabled = true;
renderer.xr.setReferenceSpaceType('local-floor');
$('stage').appendChild(renderer.domElement);
const scene = new THREE.Scene();
const camera = new THREE.PerspectiveCamera(50, innerWidth / innerHeight, 0.03, 200);
const root = new THREE.Group(); scene.add(root);
const frame = new THREE.Group(); root.add(frame);
const content = new THREE.Group(); frame.add(content);
const ui = new THREE.Group(); root.add(ui); ui.visible = false;

// ---------------------------------------------------------------- shaders
const vert = /* glsl */`
  uniform sampler2D depthMap; uniform float P; uniform float subj; uniform float aspect;
  uniform float winW; uniform float winD; uniform vec2 eyeOff;
  varying vec2 vUv; varying float vC;
  float closeness(vec2 uv){ vec3 t = texture2D(depthMap, uv).rgb; return (t.r*255.0*256.0 + t.g*255.0)/65535.0; }
  void main(){
    vUv = uv;
    float c = closeness(uv);
    float invFar = 1.0 - subj*P;
    float z = 1.0 / (invFar + c*P);
    // rays start at a (possibly swaying) virtual eye E: moving E slides the
    // layers against each other, which is what auto-sway animates
    vec3 E = vec3(eyeOff * winW, 0.0);
    vec3 wp = vec3((uv.x-0.5)*winW, (uv.y-0.5)*winW/aspect, -winD);
    vec3 p = E + (wp - E) * z; p.z += winD;
    vC = c;
    gl_Position = projectionMatrix * modelViewMatrix * vec4(p, 1.0);
  }`;
const frag = /* glsl */`
  uniform sampler2D colorMap; uniform sampler2D origMap; uniform sampler2D depthMap; uniform sampler2D alphaMap;
  uniform float tear; uniform float tearThr; uniform float useAlpha; uniform float naive;
  uniform float showDepth; uniform float edgeFade;
  varying vec2 vUv; varying float vC;
  float closeness(vec2 uv){ vec3 t = texture2D(depthMap, uv).rgb; return (t.r*255.0*256.0 + t.g*255.0)/65535.0; }
  void main(){
    // A triangle stretched across a depth jump has an interpolated depth that
    // matches neither of the pixels it samples: cut it, so the layers separate
    // exactly at the pixel boundary instead of smearing.
    if (tear > 0.0 && abs(vC - closeness(vUv)) > tearThr) discard;
    vec3 col = naive > 0.5 ? texture2D(origMap, vUv).rgb : texture2D(colorMap, vUv).rgb;
    // matted edge: soft alpha + decontaminated colour, blended over the
    // background layer drawn behind it
    float a = useAlpha > 0.5 ? texture2D(alphaMap, vUv).b : 1.0;
    if (a < 0.02) discard;
    if (showDepth > 0.5) col = mix(vec3(0.05,0.0,0.15), vec3(1.0,0.85,0.3), vC);
    vec2 e = min(vUv, 1.0 - vUv);
    float f = edgeFade > 0.0 ? smoothstep(0.0, edgeFade, min(e.x, e.y)) : 1.0;
    if (f < 0.02) discard;
    gl_FragColor = vec4(col * f, a);
    #include <colorspace_fragment>
  }`;

function makeLayer(tex, w, h, step, isBg) {
  const gw = Math.max(2, Math.round(w / step)), gh = Math.max(2, Math.round(h / step));
  const mat = new THREE.ShaderMaterial({
    vertexShader: vert, fragmentShader: frag,
    uniforms: {
      colorMap: { value: tex.color }, origMap: { value: tex.orig || tex.color }, depthMap: { value: tex.depth },
      alphaMap: { value: tex.alpha || tex.depth }, P: { value: 0.8 }, subj: { value: 0.5 },
      aspect: { value: w / h }, winW: { value: WIN_W }, winD: { value: WIN_D }, eyeOff: { value: new THREE.Vector2() },
      tear: { value: isBg ? 0 : 1 }, tearThr: { value: 0.02 }, useAlpha: { value: isBg ? 0 : 1 }, naive: { value: 0 },
      showDepth: { value: 0 }, edgeFade: { value: 0 },
    },
    transparent: !isBg, depthWrite: true,
    polygonOffset: isBg, polygonOffsetFactor: isBg ? 2 : 0, polygonOffsetUnits: isBg ? 4 : 0,
  });
  const m = new THREE.Mesh(new THREE.PlaneGeometry(1, 1, gw, gh), mat);
  m.frustumCulled = false; m.renderOrder = isBg ? 0 : 1;
  return m;
}

// ambient glow: large soft plane far behind the photo, coloured by the photo
const ambientMat = new THREE.ShaderMaterial({
  transparent: true, depthWrite: false,
  uniforms: { map: { value: null }, opacity: { value: 0.55 } },
  vertexShader: `varying vec2 vUv; void main(){ vUv = uv; gl_Position = projectionMatrix*modelViewMatrix*vec4(position,1.0); }`,
  fragmentShader: `uniform sampler2D map; uniform float opacity; varying vec2 vUv;
    void main(){ vec2 d = (vUv-0.5)*2.0; float r = length(d);
      vec3 c = texture2D(map, 0.5 + (vUv-0.5)*0.8).rgb;
      gl_FragColor = vec4(c, opacity * smoothstep(1.0, 0.25, r));
      #include <colorspace_fragment>
    }`,
});
const ambient = new THREE.Mesh(new THREE.PlaneGeometry(1, 1), ambientMat);
ambient.renderOrder = -1; frame.add(ambient);

// picture frame: four thin bars at the window plane
const border = new THREE.Group(); frame.add(border);
const borderMat = new THREE.MeshBasicMaterial({ color: 0x1a1a1f });
function rebuildBorder(aspect) {
  border.clear();
  if (state.frameStyle === 'none' || state.frameStyle === 'soft') return;
  const t = state.frameStyle === 'thick' ? 0.045 : 0.012, H = WIN_W / aspect;
  const bars = [[WIN_W + 2 * t, t, 0, H / 2 + t / 2], [WIN_W + 2 * t, t, 0, -H / 2 - t / 2],
                [t, H, -WIN_W / 2 - t / 2, 0], [t, H, WIN_W / 2 + t / 2, 0]];
  for (const [w, h, x, y] of bars) {
    const b = new THREE.Mesh(new THREE.BoxGeometry(w, h, 0.02), borderMat);
    b.position.set(x, y, -0.01); border.add(b);
  }
}

// ---------------------------------------------------------------- loading
const loader = new THREE.TextureLoader();
const loadTex = (url, color) => new Promise((res, rej) => loader.load(url, (t) => {
  t.colorSpace = color ? THREE.SRGBColorSpace : THREE.NoColorSpace;
  t.generateMipmaps = false;
  t.minFilter = t.magFilter = color ? THREE.LinearFilter : THREE.NearestFilter;
  res(t);
}, undefined, rej));
const depthCPU = {};           // name -> {w,h,data} for tap-to-focus
function readDepthCPU(name, img) {
  try {
    const cv = document.createElement('canvas'); cv.width = img.width; cv.height = img.height;
    const g = cv.getContext('2d', { willReadFrequently: true }); g.drawImage(img, 0, 0);
    depthCPU[name] = { w: img.width, h: img.height, data: g.getImageData(0, 0, img.width, img.height).data };
  } catch (e) { /* focus-by-tap unavailable; sliders still work */ }
}
function closenessAt(name, u, v) {
  const d = depthCPU[name]; if (!d) return null;
  const x = clamp(Math.round(u * (d.w - 1)), 0, d.w - 1), y = clamp(Math.round((1 - v) * (d.h - 1)), 0, d.h - 1);
  const i = (y * d.w + x) * 4; return (d.data[i] * 256 + d.data[i + 1]) / 65535;
}

let layers = null, loadToken = 0;
async function show(i) {
  const n = state.photos.length; if (!n) return;
  state.idx = ((i % n) + n) % n;
  const p = cur(), tok = ++loadToken;
  $('status').textContent = `Loading ${p.name}…`;
  const base = `photos/${p.name}/`;
  const [fg, bg, fgd, bgd, amb, orig] = await Promise.all([
    loadTex(base + 'fg.jpg', true), loadTex(base + 'bg.jpg', true),
    loadTex(base + 'fg_depth.png', false), loadTex(base + 'bg_depth.png', false),
    loadTex(base + 'ambient.jpg', true).catch(() => null), loadTex(base + 'orig.jpg', true).catch(() => null)]);
  if (tok !== loadToken) return;                       // a newer request won
  readDepthCPU(p.name, fgd.image);
  const fga = fgd.clone(); fga.minFilter = fga.magFilter = THREE.LinearFilter; fga.needsUpdate = true;  // smooth alpha edges
  const step = Math.max(1, Math.max(p.width, p.height) / 1280);
  const bgM = makeLayer({ color: bg, depth: bgd }, p.width, p.height, step * 2, true);
  const fgM = makeLayer({ color: fg, orig, depth: fgd, alpha: fga }, p.width, p.height, step, false);
  if (layers) for (const m of [layers.fgM, layers.bgM]) { content.remove(m); m.geometry.dispose(); m.material.dispose(); }
  layers = { fgM, bgM, aspect: p.width / p.height };
  content.add(bgM, fgM);
  if (amb) { ambientMat.uniforms.map.value = amb; }
  ambient.scale.set(WIN_W * 3.2, WIN_W * 3.2 / layers.aspect * 1.2, 1);
  ambient.position.z = -WIN_D * 1.6;
  rebuildBorder(layers.aspect);
  apply();
  buildThumbs();
  $('status').textContent = `${state.idx + 1} / ${n}  ·  ${p.name}`;
  slideshowTimer = performance.now();
}

// push state into uniforms / transforms / UI
function apply() {
  if (!layers) return;
  const s = P(), p = cur();
  const auto = p.grading.subject;
  const subj = clamp((s.focus ?? auto) - s.pop, 0.02, 0.98);
  for (const m of [layers.fgM, layers.bgM]) {
    m.material.uniforms.P.value = s.strength;
    m.material.uniforms.subj.value = subj;
    m.material.uniforms.showDepth.value = state.showDepth ? 1 : 0;
    m.material.uniforms.edgeFade.value = state.frameStyle === 'soft' ? 0.04 : 0;
  }
  const layered = state.mode === 'layered';
  const u = layers.fgM.material.uniforms;
  u.tear.value = layered ? 1 : 0; u.useAlpha.value = layered ? 1 : 0; u.naive.value = layered ? 0 : 1;
  u.tearThr.value = Math.max(0.04, 1.5 / (0.1 * s.strength * cur().width));
  layers.bgM.visible = layered;
  ambient.visible = state.glow;
  rebuildBorder(layers.aspect);
  frame.scale.setScalar(state.size);
  frame.rotation.set(THREE.MathUtils.degToRad(s.tilt), THREE.MathUtils.degToRad(s.turn), THREE.MathUtils.degToRad(s.roll), 'YXZ');
  layoutUI();
  syncPanel();
  savePrefs();
}

// ---------------------------------------------------------------- desktop camera
const eyeTarget = new THREE.Vector2(), eye = new THREE.Vector2();
let dragging = null;
renderer.domElement.addEventListener('pointerdown', (e) => { dragging = { x: e.clientX, y: e.clientY, moved: false }; });
addEventListener('pointerup', (e) => {
  if (dragging && !dragging.moved && e.target === renderer.domElement) focusAtScreen(e.clientX, e.clientY);
  dragging = null;
});
addEventListener('pointermove', (e) => {
  if (dragging && Math.hypot(e.clientX - dragging.x, e.clientY - dragging.y) > 6) dragging.moved = true;
  if (e.target !== renderer.domElement) return;
  eyeTarget.set((e.clientX / innerWidth - 0.5) * 2, (e.clientY / innerHeight - 0.5) * 2);
});
function placeDesktopCamera(t) {
  eye.lerp(eyeTarget, 0.12);
  const lean = 0.15;                           // metres: matches the fill budget
  root.position.set(0, 0, -state.distance);
  root.quaternion.identity();
  camera.position.set(eye.x * lean, -eye.y * lean * 0.6, 0);
  camera.lookAt(0, 0, -state.distance);
  const asp = layers ? layers.aspect : 1.5;
  const quarter = layers && (P().roll % 180) !== 0;            // rotated a quarter turn
  const needW = (quarter ? state.size / asp : state.size) * 1.25;
  const needH = (quarter ? state.size : state.size / asp) * 1.25;
  camera.fov = 2 * Math.atan(Math.max(needH, needW / camera.aspect) / 2 / state.distance) * 180 / Math.PI;
  camera.updateProjectionMatrix();
}

// ---------------------------------------------------------------- tap / point to focus
const ray = new THREE.Raycaster();
const windowPlane = new THREE.Mesh(new THREE.PlaneGeometry(1, 1), new THREE.MeshBasicMaterial({ visible: false }));
frame.add(windowPlane);
function windowUVFromRay(r) {
  if (!layers) return null;
  windowPlane.scale.set(WIN_W, WIN_W / layers.aspect, 1);
  windowPlane.updateMatrixWorld();
  const hit = r.intersectObject(windowPlane, false)[0];
  return hit ? hit.uv : null;
}
function focusAtUV(uv) {
  const c = closenessAt(cur().name, uv.x, uv.y);
  if (c == null) return;
  const s = P(); s.focus = c; s.pop = 0; apply();
  toast('Focus set — that point now sits at the window');
}
function focusAtScreen(x, y) {
  ray.setFromCamera(new THREE.Vector2(x / innerWidth * 2 - 1, -(y / innerHeight) * 2 + 1), camera);
  const uv = windowUVFromRay(ray); if (uv) focusAtUV(uv);
}

// ---------------------------------------------------------------- XR: placement, grab, rays, UI
let xrPlaced = false;
renderer.xr.addEventListener('sessionstart', () => { xrPlaced = false; document.body.classList.add('xr'); ui.visible = true; });
renderer.xr.addEventListener('sessionend', () => { document.body.classList.remove('xr'); ui.visible = false; });
function placeXR() {
  const xrCam = renderer.xr.getCamera();
  const pos = new THREE.Vector3(); xrCam.getWorldPosition(pos);
  const dir = new THREE.Vector3(); xrCam.getWorldDirection(dir); dir.y = 0; dir.normalize();
  const c = pos.clone().addScaledVector(dir, state.distance); c.y = pos.y;
  root.position.copy(c); root.lookAt(pos.x, c.y, pos.z);
  xrPlaced = true;
}

const controllers = [];
const rayGeo = new THREE.BufferGeometry().setFromPoints([new THREE.Vector3(0, 0, 0), new THREE.Vector3(0, 0, -1)]);
for (let i = 0; i < 2; i++) {
  const ctl = renderer.xr.getController(i);
  const line = new THREE.Line(rayGeo, new THREE.LineBasicMaterial({ color: 0xffffff, transparent: true, opacity: 0.6 }));
  line.scale.z = 3; ctl.add(line);
  ctl.userData = { line, squeezing: false, hover: null };
  ctl.addEventListener('selectstart', () => onSelect(ctl));
  ctl.addEventListener('squeezestart', () => startGrab(ctl));
  ctl.addEventListener('squeezeend', () => endGrab(ctl));
  ctl.addEventListener('connected', (e) => { ctl.userData.hand = !!e.data.hand; });
  scene.add(ctl); controllers.push(ctl);
}
const tmpM = new THREE.Matrix4();
function ctlRay(ctl) {
  tmpM.identity().extractRotation(ctl.matrixWorld);
  ray.ray.origin.setFromMatrixPosition(ctl.matrixWorld);
  ray.ray.direction.set(0, 0, -1).applyMatrix4(tmpM);
  return ray;
}

// grab: one hand moves/rotates the window, two hands also resize it
let grab = null;
function startGrab(ctl) {
  ctl.userData.squeezing = true;
  const both = controllers.filter((c) => c.userData.squeezing);
  if (both.length === 2) {
    const a = both[0].getWorldPosition(new THREE.Vector3()), b = both[1].getWorldPosition(new THREE.Vector3());
    grab = { two: true, d0: a.distanceTo(b), size0: state.size };
  } else {
    grab = { two: false, ctl, offset: new THREE.Matrix4().copy(ctl.matrixWorld).invert().multiply(root.matrixWorld) };
  }
}
function endGrab(ctl) {
  ctl.userData.squeezing = false;
  const still = controllers.find((c) => c.userData.squeezing);
  grab = still ? { two: false, ctl: still, offset: new THREE.Matrix4().copy(still.matrixWorld).invert().multiply(root.matrixWorld) } : null;
  savePrefs();
}
const tmpP = new THREE.Vector3(), tmpQ = new THREE.Quaternion(), tmpS = new THREE.Vector3();
function updateGrab() {
  if (!grab) return;
  if (grab.two) {
    const both = controllers.filter((c) => c.userData.squeezing);
    if (both.length < 2) return;
    const a = both[0].getWorldPosition(new THREE.Vector3()), b = both[1].getWorldPosition(new THREE.Vector3());
    state.size = clamp(grab.size0 * a.distanceTo(b) / Math.max(grab.d0, 0.05), 0.3, 6);
    frame.scale.setScalar(state.size); layoutUI(); syncPanel();
  } else {
    tmpM.multiplyMatrices(grab.ctl.matrixWorld, grab.offset);
    tmpM.decompose(tmpP, tmpQ, tmpS);
    root.position.copy(tmpP); root.quaternion.copy(tmpQ);
  }
}

// XR control bar + thumbnail strip, drawn on canvases
const buttons = [];
function makeButton(label, onClick, w = 0.11, h = 0.06) {
  const cv = document.createElement('canvas'); cv.width = 256; cv.height = Math.round(256 * h / w);
  const tex = new THREE.CanvasTexture(cv); tex.colorSpace = THREE.SRGBColorSpace;
  const mesh = new THREE.Mesh(new THREE.PlaneGeometry(w, h), new THREE.MeshBasicMaterial({ map: tex, transparent: true }));
  mesh.userData = { onClick, label, cv, tex, hover: false, active: false };
  drawButton(mesh); buttons.push(mesh);
  return mesh;
}
function drawButton(m) {
  const { cv, tex, label, hover, active } = m.userData, g = cv.getContext('2d');
  g.clearRect(0, 0, cv.width, cv.height);
  g.fillStyle = active ? '#5a48a0' : hover ? '#3a3a4c' : 'rgba(24,24,32,0.92)';
  const r = 26; g.beginPath(); g.roundRect(4, 4, cv.width - 8, cv.height - 8, r); g.fill();
  g.strokeStyle = hover ? '#a99cf0' : '#4a4a5c'; g.lineWidth = 4; g.stroke();
  g.fillStyle = '#f0eef6'; g.font = `600 ${Math.round(cv.height * 0.42)}px system-ui, sans-serif`;
  g.textAlign = 'center'; g.textBaseline = 'middle'; g.fillText(typeof label === 'function' ? label() : label, cv.width / 2, cv.height / 2 + 2);
  tex.needsUpdate = true;
}
const bar = new THREE.Group(); ui.add(bar);
const BAR = [
  ['◀', () => show(state.idx - 1)], ['▶', () => show(state.idx + 1)],
  ['Depth −', () => step('strength', -0.05, 0.1, 0.95)], ['Depth +', () => step('strength', 0.05, 0.1, 0.95)],
  ['Pop −', () => step('pop', -0.04, -0.5, 0.5)], ['Pop +', () => step('pop', 0.04, -0.5, 0.5)],
  ['Smaller', () => { state.size = clamp(state.size / 1.15, 0.3, 6); apply(); }], ['Larger', () => { state.size = clamp(state.size * 1.15, 0.3, 6); apply(); }],
  ['Rotate', () => { const s = P(); s.roll = ((s.roll + 90) % 360); apply(); }],
  [() => state.mode === 'layered' ? 'Layered' : 'Typical', () => { toggleMode(); }],
  [() => state.glow ? 'Glow ✓' : 'Glow', () => { state.glow = !state.glow; apply(); }],
  [() => state.swayMode === 'off' ? 'Sway' : 'Sway ✓', () => { const m = ['off', 'eight', 'side', 'circle']; state.swayMode = m[(m.indexOf(state.swayMode) + 1) % m.length]; apply(); toast('Sway: ' + state.swayMode); }],
  ['Recenter', () => { xrPlaced = false; }], ['Reset', () => resetPhoto()],
];
let bx = 0;
for (const [label, fn] of BAR) {
  const w = typeof label === 'string' && label.length <= 1 ? 0.07 : 0.13;
  const b = makeButton(label, fn, w); b.position.x = bx + w / 2; bx += w + 0.01; bar.add(b);
}
for (const b of bar.children) b.position.x -= bx / 2;
const thumbs = new THREE.Group(); ui.add(thumbs);
const thumbTex = {};
function buildThumbs() {
  thumbs.clear();
  for (const b of [...buttons]) if (b.userData.thumb) buttons.splice(buttons.indexOf(b), 1);
  const n = state.photos.length, tw = 0.09, gap = 0.012;
  state.photos.forEach((p, i) => {
    const asp = p.width / p.height, h = 0.07, w = Math.min(tw, h * asp);
    const mat = new THREE.MeshBasicMaterial({ color: 0x888888 });
    const m = new THREE.Mesh(new THREE.PlaneGeometry(w, h), mat);
    m.position.x = (i - (n - 1) / 2) * (tw + gap);
    m.userData = { onClick: () => show(i), thumb: true, hover: false, idx: i };
    const setTex = (t) => { mat.map = t; mat.color.set(i === state.idx ? 0xffffff : 0xb0b0b0); mat.needsUpdate = true; };
    if (thumbTex[p.name]) setTex(thumbTex[p.name]);
    else loadTex(`photos/${p.name}/thumb.jpg`, true).then((t) => { thumbTex[p.name] = t; setTex(t); }).catch(() => {});
    if (i === state.idx) { const ring = new THREE.Mesh(new THREE.PlaneGeometry(w + 0.012, h + 0.012), new THREE.MeshBasicMaterial({ color: 0xa99cf0 })); ring.position.set(m.position.x, 0, -0.001); thumbs.add(ring); }
    thumbs.add(m); buttons.push(m);
  });
}
function layoutUI() {
  if (!layers) return;
  const halfH = state.size * WIN_W / layers.aspect / 2;
  bar.position.set(0, -halfH - 0.12, 0.08);
  thumbs.position.set(0, -halfH - 0.22, 0.1);
  bar.rotation.x = thumbs.rotation.x = -0.25;
  for (const b of bar.children) drawButton(b);
}
function hoverAndRays() {
  for (const ctl of controllers) {
    const r = ctlRay(ctl);
    const hits = ui.visible ? r.intersectObjects(buttons, false) : [];
    const h = hits[0]?.object || null;
    if (ctl.userData.hover !== h) {
      if (ctl.userData.hover) { ctl.userData.hover.userData.hover = false; if (!ctl.userData.hover.userData.thumb) drawButton(ctl.userData.hover); }
      if (h) { h.userData.hover = true; if (!h.userData.thumb) drawButton(h); }
      ctl.userData.hover = h;
    }
    let len = hits[0]?.distance;
    if (len == null) { const uvHit = r.intersectObject(windowPlane, false)[0]; len = uvHit?.distance; }
    ctl.userData.line.scale.z = len ?? 3;
  }
}
function onSelect(ctl) {
  const r = ctlRay(ctl);
  const hit = ui.visible ? r.intersectObjects(buttons, false)[0] : null;
  if (hit) { hit.object.userData.onClick(); return; }
  const uv = windowUVFromRay(r);
  if (uv) focusAtUV(uv);                      // trigger on the photo = focus there
}

// thumbsticks: X = prev/next, Y = depth; A/X = layered/typical, B/Y = hide controls
let lastStick = 0; const lastBtn = {};
function pollGamepads(t) {
  const s = renderer.xr.getSession(); if (!s) return;
  for (const src of s.inputSources) {
    const gp = src.gamepad; if (!gp) continue;
    if (gp.axes.length >= 4 && t - lastStick > 300) {
      const ax = gp.axes[2], ay = gp.axes[3];
      if (ax > 0.7) { show(state.idx + 1); lastStick = t; }
      else if (ax < -0.7) { show(state.idx - 1); lastStick = t; }
      else if (ay < -0.7) { step('strength', 0.05, 0.1, 0.95); lastStick = t; }
      else if (ay > 0.7) { step('strength', -0.05, 0.1, 0.95); lastStick = t; }
    }
    const key = src.handedness;
    const a = gp.buttons[4]?.pressed, b = gp.buttons[5]?.pressed;
    if (a && !lastBtn[key + 'a']) toggleMode();
    if (b && !lastBtn[key + 'b']) ui.visible = !ui.visible;
    lastBtn[key + 'a'] = a; lastBtn[key + 'b'] = b;
  }
}

// ---------------------------------------------------------------- actions
function step(k, d, lo, hi) { const s = P(); s[k] = clamp(+(s[k] + d).toFixed(3), lo, hi); apply(); }
function toggleMode() { state.mode = state.mode === 'layered' ? 'naive' : 'layered'; apply(); }
function resetPhoto() { const p = cur(); if (!p) return; state.per[p.name] = { ...DEFAULTS }; apply(); toast('Photo settings reset'); }
let slideshowTimer = 0;
let toastT = 0;
function toast(msg) { const el = $('toast'); el.textContent = msg; el.classList.add('on'); toastT = performance.now(); }

// ---------------------------------------------------------------- auto-sway
// Moves the virtual eye the photo is projected from, so near and far layers
// slide against each other while you (and the window) stay still. Works the
// same on desktop, in the headset and in exported videos.
const SWAY_MAX = 0.07;                 // window widths at amount = 1
function swayOffset(mode, amt, phase) {   // phase in radians, one loop = 2*PI
  const A = SWAY_MAX * amt;
  switch (mode) {
    case 'side':   return [A * Math.sin(phase), 0];
    case 'circle': return [A * Math.cos(phase), 0.55 * A * Math.sin(phase)];
    case 'eight':  return [A * Math.sin(phase), 0.4 * A * Math.sin(2 * phase)];
    case 'zoom':   return [0, 0];
    default:       return [0, 0];
  }
}
let swayOverride = null;
function setEyeOff(x, y) {
  if (!layers) return;
  layers.fgM.material.uniforms.eyeOff.value.set(x, y);
  layers.bgM.material.uniforms.eyeOff.value.set(x, y);
}

// ---------------------------------------------------------------- loop
renderer.setAnimationLoop((t) => {
  if (renderer.xr.isPresenting) {
    if (!xrPlaced) placeXR();
    updateGrab(); hoverAndRays(); pollGamepads(t);
  } else {
    placeDesktopCamera(t);
  }
  if (swayOverride) setEyeOff(...swayOverride);
  else if (state.swayMode !== 'off') setEyeOff(...swayOffset(state.swayMode, state.swayAmt, t * 0.0011 * state.swaySpeed));
  else setEyeOff(0, 0);
  if (state.slideshow && t - slideshowTimer > 8000) show(state.idx + 1);
  if (toastT && t - toastT > 2200) { $('toast').classList.remove('on'); toastT = 0; }
  renderer.render(scene, camera);
});
addEventListener('resize', () => {
  camera.aspect = innerWidth / innerHeight; camera.updateProjectionMatrix(); renderer.setSize(innerWidth, innerHeight);
});

// ---------------------------------------------------------------- desktop panel
const bind = (id, get, set, fmt = (v) => (+v).toFixed(2)) => {
  const el = $(id), out = $(id + 'V');
  el.addEventListener('input', () => { set(+el.value); apply(); });
  return () => { const v = get(); el.value = v; if (out) out.textContent = fmt(v); };
};
const syncers = [
  bind('strength', () => P().strength, (v) => P().strength = v),
  bind('pop', () => P().pop, (v) => P().pop = v),
  bind('size', () => state.size, (v) => state.size = v, (v) => (+v).toFixed(2) + ' m'),
  bind('distance', () => state.distance, (v) => state.distance = v, (v) => (+v).toFixed(1) + ' m'),
  bind('tilt', () => P().tilt, (v) => P().tilt = v, (v) => Math.round(v) + '°'),
  bind('turn', () => P().turn, (v) => P().turn = v, (v) => Math.round(v) + '°'),
  bind('swayAmt', () => state.swayAmt, (v) => state.swayAmt = v, (v) => Math.round(v * 100) + '%'),
  bind('swaySpeed', () => state.swaySpeed, (v) => state.swaySpeed = v, (v) => (+v).toFixed(1) + '×'),
];
function syncPanel() {
  for (const s of syncers) s();
  $('mode').textContent = state.mode === 'layered' ? 'Layered (ours)' : 'Typical converter';
  $('mode').classList.toggle('alt', state.mode !== 'layered');
  $('swayMode').value = state.swayMode; $('glow').checked = state.glow; $('depthvis').checked = state.showDepth;
  $('slideshow').checked = state.slideshow; $('frameStyle').value = state.frameStyle;
  $('rollV').textContent = (P().roll || 0) + '°';
  $('focusV').textContent = P().focus == null ? 'auto' : 'set';
  document.querySelectorAll('#thumbs img').forEach((im, i) => im.classList.toggle('on', i === state.idx));
}
$('mode').addEventListener('click', toggleMode);
$('swayMode').addEventListener('change', (e) => { state.swayMode = e.target.value; savePrefs(); });
$('glow').addEventListener('change', (e) => { state.glow = e.target.checked; apply(); });
$('depthvis').addEventListener('change', (e) => { state.showDepth = e.target.checked; apply(); });
$('slideshow').addEventListener('change', (e) => { state.slideshow = e.target.checked; slideshowTimer = performance.now(); });
$('frameStyle').addEventListener('change', (e) => { state.frameStyle = e.target.value; apply(); });
$('rotL').addEventListener('click', () => { const s = P(); s.roll = (s.roll + 270) % 360; apply(); });
$('rotR').addEventListener('click', () => { const s = P(); s.roll = (s.roll + 90) % 360; apply(); });
$('focusAuto').addEventListener('click', () => { const s = P(); s.focus = null; s.pop = 0; apply(); });
$('reset').addEventListener('click', resetPhoto);
$('prev').addEventListener('click', () => show(state.idx - 1));
$('next').addEventListener('click', () => show(state.idx + 1));
$('collapse').addEventListener('click', () => document.body.classList.toggle('mini'));
addEventListener('keydown', (e) => {
  if (e.target.tagName === 'INPUT' || e.target.tagName === 'SELECT') return;
  if (e.key === 'ArrowRight') show(state.idx + 1);
  if (e.key === 'ArrowLeft') show(state.idx - 1);
  if (e.key === 'r') { const s = P(); s.roll = (s.roll + 90) % 360; apply(); }
  if (e.key === ' ') { toggleMode(); e.preventDefault(); }
});
function buildPanelThumbs() {
  const box = $('thumbs'); box.innerHTML = '';
  state.photos.forEach((p, i) => {
    const im = new Image(); im.src = `photos/${p.name}/thumb.jpg`; im.alt = p.name; im.title = p.name;
    im.addEventListener('click', () => show(i)); box.appendChild(im);
  });
}

async function initXRButton() {
  const holder = $('xrHolder');
  try {
    if (!navigator.xr) throw 0;
    const ar = await navigator.xr.isSessionSupported('immersive-ar').catch(() => false);
    const vr = await navigator.xr.isSessionSupported('immersive-vr').catch(() => false);
    if (!ar && !vr) throw 0;
    const btn = XRButton.createButton(renderer, { optionalFeatures: ['local-floor', 'hand-tracking'] });
    btn.style.position = 'static'; btn.style.transform = 'none'; btn.style.left = 'auto';
    holder.appendChild(btn);
  } catch (e) {
    holder.innerHTML = '<span class="hint">Headset mode needs the Meta Quest Browser and an https address (see README).</span>';
  }
}


// ---------------------------------------------------------------- export
// Files are offered through the claude.ai "downloads" capability when the
// page runs there; anywhere else (GitHub Pages, local) a normal download.
let dlNs = null, exporting = false;
(async () => { try { if (window.claude && window.claude.use) dlNs = await window.claude.use('downloads'); } catch (e) {} })();
async function saveFile(filename, blob) {
  if (dlNs) {
    try { await dlNs.save({ filename, data: blob }); toast('Saved ' + filename); return; }
    catch (e) {
      const code = e && e.code;
      if (code === 'declined') { toast('Save cancelled'); return; }
      if (code === 'too_large') { toast('File too large here — try a smaller export'); return; }
      if (code && !['unavailable', 'not_granted', 'capability_disabled', 'capability_removed'].includes(code)) { toast('Could not save (' + code + ')'); return; }
    }
  }
  const url = URL.createObjectURL(blob), a = document.createElement('a');
  a.href = url; a.download = filename; document.body.appendChild(a); a.click(); a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 5000);
  toast('Downloading ' + filename);
}
const toBlob = (cv, type, q) => new Promise((res) => cv.toBlob(res, type, q));
function exportSize(maxLong) {
  const p = cur(); let w = p.width, h = p.height;
  if ((P().roll % 180) !== 0) [w, h] = [h, w];
  const s = Math.min(1, maxLong / Math.max(w, h));
  return [Math.round(w * s / 2) * 2, Math.round(h * s / 2) * 2];
}
// Parallel cameras with a shifted frustum, so the window plane (your focus
// point) has zero parallax - the standard way 3D films are framed.
function exportCamera(W, H, eyeX) {
  const D = state.distance, cam = new THREE.PerspectiveCamera(50, W / H, 0.03, 200);
  const quarter = (P().roll % 180) !== 0, asp = layers.aspect;
  const wW = quarter ? state.size / asp : state.size, wH = quarter ? state.size : state.size / asp;
  const margin = (state.frameStyle === 'thin' || state.frameStyle === 'thick') ? 1.06 : 0.98;
  cam.fov = 2 * Math.atan(Math.max(wH, wW / (W / H)) * margin / 2 / D) * 180 / Math.PI;
  cam.position.set(eyeX, 0, 0); cam.lookAt(eyeX, 0, -D);
  const th = Math.tan(cam.fov * Math.PI / 360) * (W / H);
  cam.setViewOffset(W, H, -(eyeX / (D * th)) * (W / 2), 0, W, H);
  cam.updateProjectionMatrix();
  return cam;
}
function placeForExport() { root.position.set(0, 0, -state.distance); root.quaternion.identity(); scene.updateMatrixWorld(true); }
function renderStill(cam, W, H) {
  const S = 2;                                   // 2x supersampling for clean edges
  const rt = new THREE.WebGLRenderTarget(W * S, H * S); rt.texture.colorSpace = THREE.SRGBColorSpace;
  const xr = renderer.xr.enabled; renderer.xr.enabled = false;
  renderer.setRenderTarget(rt); renderer.setClearColor(0x000000, 1); renderer.clear(); renderer.render(scene, cam);
  const px = new Uint8Array(W * S * H * S * 4); renderer.readRenderTargetPixels(rt, 0, 0, W * S, H * S, px);
  renderer.setRenderTarget(null); renderer.setClearColor(0x000000, 0); renderer.xr.enabled = xr; rt.dispose();
  const big = document.createElement('canvas'); big.width = W * S; big.height = H * S;
  const gb = big.getContext('2d'), id = gb.createImageData(W * S, H * S), row = W * S * 4;
  for (let y = 0; y < H * S; y++) id.data.set(px.subarray((H * S - 1 - y) * row, (H * S - y) * row), y * row);
  gb.putImageData(id, 0, 0);
  const cv = document.createElement('canvas'); cv.width = W; cv.height = H;
  const g = cv.getContext('2d'); g.imageSmoothingQuality = 'high'; g.drawImage(big, 0, 0, W, H);
  return cv;
}
function stereoPair(maxLong) {
  const [W, H] = exportSize(maxLong); const ipd = 0.064;
  swayOverride = [0, 0]; setEyeOff(0, 0); placeForExport();
  const L = renderStill(exportCamera(W, H, -ipd / 2), W, H), R = renderStill(exportCamera(W, H, +ipd / 2), W, H);
  swayOverride = null;
  return [L, R, W, H];
}
async function exportSBS() {
  const [L, R, W, H] = stereoPair(1920);
  const cv = document.createElement('canvas'); cv.width = W * 2; cv.height = H;
  const g = cv.getContext('2d'); g.drawImage(L, 0, 0); g.drawImage(R, W, 0);
  await saveFile(`${cur().name}_3D_LR.jpg`, await toBlob(cv, 'image/jpeg', 0.93));
}
async function exportAnaglyph() {
  const [L, R, W, H] = stereoPair(1920);
  const a = L.getContext('2d').getImageData(0, 0, W, H), b = R.getContext('2d').getImageData(0, 0, W, H);
  for (let i = 0; i < a.data.length; i += 4) { a.data[i + 1] = b.data[i + 1]; a.data[i + 2] = b.data[i + 2]; }
  const cv = document.createElement('canvas'); cv.width = W; cv.height = H; cv.getContext('2d').putImageData(a, 0, 0);
  await saveFile(`${cur().name}_anaglyph.jpg`, await toBlob(cv, 'image/jpeg', 0.93));
}
async function exportDepth() {
  const d = depthCPU[cur().name]; if (!d) { toast('Depth not available for export'); return; }
  const cv = document.createElement('canvas'); cv.width = d.w; cv.height = d.h;
  const g = cv.getContext('2d'), id = g.createImageData(d.w, d.h);
  for (let i = 0; i < d.data.length; i += 4) { const v = Math.round((d.data[i] * 256 + d.data[i + 1]) / 65535 * 255); id.data[i] = id.data[i + 1] = id.data[i + 2] = v; id.data[i + 3] = 255; }
  g.putImageData(id, 0, 0);
  await saveFile(`${cur().name}_depth.png`, await toBlob(cv, 'image/png'));
}
// Frame-accurate MP4: exactly fps*seconds frames are rendered and encoded
// with WebCodecs, so the clip is smooth and loops seamlessly no matter how
// fast the device renders. Falls back to MediaRecorder where WebCodecs is
// missing.
async function exportVideo(seconds = 6, fps = 30) {
  const [W, H] = exportSize(1920);
  const glc = document.createElement('canvas'); glc.width = W; glc.height = H;
  const r2 = new THREE.WebGLRenderer({ canvas: glc, antialias: true, preserveDrawingBuffer: true });
  r2.setPixelRatio(1); r2.setSize(W, H, false); r2.setClearColor(0x000000, 1);
  const cam = exportCamera(W, H, 0);
  const mode = state.swayMode === 'off' ? 'eight' : state.swayMode, amt = Math.max(0.2, state.swayAmt);
  const drawAt = (k) => {
    swayOverride = swayOffset(mode, amt, k * Math.PI * 2); setEyeOff(...swayOverride);
    placeForExport(); r2.render(scene, cam);
  };
  const N = Math.round(seconds * fps);
  const status = (i) => { $('exStatus').textContent = `Rendering sway video… ${Math.round(i / N * 100)}%`; };
  try {
    let blob = null, ext = 'mp4';
    if (window.VideoEncoder && window.VideoFrame) {
      const codecs = ['avc1.640033', 'avc1.640028', 'avc1.4d0028', 'avc1.42e01f'];
      let cfg = null;
      for (const codec of codecs) {
        const c = { codec, width: W, height: H, bitrate: 16e6, framerate: fps };
        try { if ((await VideoEncoder.isConfigSupported(c)).supported) { cfg = c; break; } } catch (e) {}
      }
      if (cfg) {
        const muxer = new Muxer({ target: new ArrayBufferTarget(), video: { codec: 'avc', width: W, height: H, frameRate: fps }, fastStart: 'in-memory' });
        let encErr = null;
        const enc = new VideoEncoder({ output: (chunk, meta) => muxer.addVideoChunk(chunk, meta), error: (e) => { encErr = e; } });
        enc.configure(cfg);
        for (let i = 0; i < N; i++) {
          drawAt(i / N);
          const vf = new VideoFrame(glc, { timestamp: Math.round(i * 1e6 / fps), duration: Math.round(1e6 / fps) });
          enc.encode(vf, { keyFrame: i % fps === 0 }); vf.close();
          if (encErr) throw encErr;
          while (enc.encodeQueueSize > 6) await new Promise((r) => setTimeout(r, 5));
          if (i % 5 === 0) { status(i); await new Promise((r) => setTimeout(r, 0)); }
        }
        await enc.flush(); enc.close(); muxer.finalize();
        blob = new Blob([muxer.target.buffer], { type: 'video/mp4' });
      }
    }
    if (!blob) [blob, ext] = await recordRealtime(glc, drawAt, seconds, status, N);
    if (!blob) { toast('This browser could not create the video'); return; }
    $('exStatus').textContent = '';
    await saveFile(`${cur().name}_sway.${ext}`, blob);
  } finally { swayOverride = null; r2.dispose(); $('exStatus').textContent = ''; }
}
async function recordRealtime(glc, drawAt, seconds, status, N) {
  if (!window.MediaRecorder) return [null, null];
  const type = ['video/mp4', 'video/webm;codecs=vp9', 'video/webm'].find((t) => MediaRecorder.isTypeSupported(t));
  if (!type) return [null, null];
  const stream = glc.captureStream(30);
  const rec = new MediaRecorder(stream, { mimeType: type, videoBitsPerSecond: 14e6 });
  const chunks = []; rec.ondataavailable = (e) => { if (e.data && e.data.size) chunks.push(e.data); };
  const stopped = new Promise((res) => { rec.onstop = res; });
  rec.start(250);
  const t0 = performance.now();
  await new Promise((resolve) => {
    const tick = () => {
      const k = (performance.now() - t0) / (seconds * 1000);
      if (k >= 1) { resolve(); return; }
      drawAt(k); status(Math.round(k * N)); requestAnimationFrame(tick);
    };
    requestAnimationFrame(tick);
  });
  rec.stop(); await stopped; stream.getTracks().forEach((t) => t.stop());
  const b = new Blob(chunks, { type: type.split(';')[0] });
  return b.size > 1000 ? [b, type.startsWith('video/mp4') ? 'mp4' : 'webm'] : [null, null];
}
async function runExport(fn, label) {
  if (exporting || !layers) return;
  exporting = true; $('exStatus').textContent = label + '…';
  try { await new Promise((r) => setTimeout(r, 30)); await fn(); }
  catch (e) { console.error(e); toast('Export failed: ' + (e.message || e)); }
  finally { exporting = false; $('exStatus').textContent = ''; }
}
$('exSBS').addEventListener('click', () => runExport(exportSBS, 'Rendering 3D image'));
$('exAna').addEventListener('click', () => runExport(exportAnaglyph, 'Rendering red/cyan image'));
$('exVid').addEventListener('click', () => runExport(() => exportVideo(6), 'Rendering sway video'));
$('exDepth').addEventListener('click', () => runExport(exportDepth, 'Saving depth map'));

window.__dbg = { state, apply, show, P, focusAtUV, stereoPair, exportSize, exportVideo, get layers() { return layers; } };
(async () => {
  loadPrefs();
  initXRButton();
  try { state.photos = await (await fetch('photos/manifest.json')).json(); }
  catch (e) { $('status').textContent = 'No photos/manifest.json found'; return; }
  buildPanelThumbs();
  await show(0);
})();
