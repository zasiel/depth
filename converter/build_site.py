"""Assemble the viewer site: index.html + photos/<name>/... + photos/manifest.json

usage: python3 build_site.py <converted_dir> <site_dir>
"""
import json, os, shutil, sys

src, dst = sys.argv[1], sys.argv[2]
VIEWER = os.environ.get("VIEWER_HTML", os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "viewer", "index.html"))
os.makedirs(os.path.join(dst, "photos"), exist_ok=True)
shutil.copy(VIEWER, os.path.join(dst, "index.html"))
manifest = []
for name in sorted(os.listdir(src)):
    d = os.path.join(src, name)
    mp = os.path.join(d, "meta.json")
    if not os.path.exists(mp):
        continue
    meta = json.load(open(mp))
    od = os.path.join(dst, "photos", name)
    os.makedirs(od, exist_ok=True)
    for f in ("fg.jpg", "bg.jpg", "fg_depth.png", "bg_depth.png", "thumb.jpg", "ambient.jpg", "orig.jpg"):
        shutil.copy(os.path.join(d, f), od)
    manifest.append({"name": name, "width": meta["width"], "height": meta["height"], "grading": meta["grading"]})
json.dump(manifest, open(os.path.join(dst, "photos", "manifest.json"), "w"), indent=1)
print(f"{len(manifest)} photos -> {dst}")
