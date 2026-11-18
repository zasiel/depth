#!/usr/bin/env bash
# Depth Anything V2 Small (Apache-2.0), dynamic-resolution ONNX export by fabio-sim
set -e
mkdir -p "$(dirname "$0")/../models"
cd "$(dirname "$0")/../models"
curl -L -o depth_anything_v2_vits_dynamic.onnx \
  https://github.com/fabio-sim/Depth-Anything-ONNX/releases/download/v2.0.0/depth_anything_v2_vits_dynamic.onnx
