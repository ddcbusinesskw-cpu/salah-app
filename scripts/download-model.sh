#!/usr/bin/env bash
# Downloads MoveNet SinglePose Lightning v4 weights for offline use.
# Run once before building: bash scripts/download-model.sh
set -e
BASE="https://tfhub.dev/google/tfjs-model/movenet/singlepose/lightning/4"
DEST="$(dirname "$0")/../models/movenet"
mkdir -p "$DEST"
curl -sL "${BASE}/model.json" -o "${DEST}/model.json"
# Parse shard filenames from model.json and download each
python3 - "${DEST}" << 'PY'
import json, sys, subprocess, os, urllib.request
d = sys.argv[1]
with open(os.path.join(d, "model.json")) as f:
    m = json.load(f)
base = "https://tfhub.dev/google/tfjs-model/movenet/singlepose/lightning/4/"
for w in m.get("weightsManifest", []):
    for path in w.get("paths", []):
        out = os.path.join(d, path)
        os.makedirs(os.path.dirname(out), exist_ok=True)
        print(f"  downloading {path}…")
        urllib.request.urlretrieve(base + path, out)
print("Done — models/movenet/ ready for offline use.")
PY
