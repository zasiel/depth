#!/usr/bin/env bash
# Stages everything the Quest app bundles into app/src/main/assets/ (generated; not in git):
#   samples/   <- docs/photos/*   (demo photos so a fresh install is never empty)
#   models/    <- models/*.onnx   (conversion models; bundled now, used from Milestone 2)
# Usage: bash scripts/prepare_assets.sh [--skip-fetch]
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ASSETS="$ROOT/app/src/main/assets"

if [ "${1:-}" != "--skip-fetch" ]; then
  bash "$ROOT/scripts/fetch_models.sh"
fi

rm -rf "$ASSETS/samples" "$ASSETS/models"
mkdir -p "$ASSETS/samples" "$ASSETS/models"
cp -r "$ROOT"/docs/photos/. "$ASSETS/samples/"
cp "$ROOT"/models/*.onnx "$ASSETS/models/"

echo "samples: $(find "$ASSETS/samples" -mindepth 1 -maxdepth 1 -type d | wc -l) photos"
ls -la "$ASSETS/models"
test -f "$ASSETS/samples/manifest.json" || { echo "docs/photos/manifest.json missing" >&2; exit 1; }
