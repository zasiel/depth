#!/usr/bin/env bash
# rebuild viewer/index.html (single file with three.js inlined)
set -e; cd "$(dirname "$0")"
npm install three esbuild mp4-muxer
npx esbuild app.js --bundle --minify --format=iife --outfile=app.bundle.js
python3 -c "
src=open('index.src.html').read(); js=open('app.bundle.js').read().replace('</script','<\\\\/script')
open('index.html','w').write(src.replace('/*APP*/',js))"
