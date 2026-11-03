#!/usr/bin/env bash
# Puts the offline speech model used for the "help help" voice SOS into app/src/main/assets/model.
# Indian English small model first; if that server is unreachable, the US English small model
# from Alpha Cephei's public Android demo repository is used instead.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/app/src/main/assets/model"
INFO="$ROOT/app/src/main/assets/model_info.txt"
WORK="$(mktemp -d)"
rm -rf "$DEST"
mkdir -p "$ROOT/app/src/main/assets"

if curl -fsSL --retry 3 --retry-delay 5 --max-time 600 -o "$WORK/model.zip" \
     https://alphacephei.com/vosk/models/vosk-model-small-en-in-0.4.zip; then
  unzip -q "$WORK/model.zip" -d "$WORK/unzipped"
  SRC="$(dirname "$(find "$WORK/unzipped" -maxdepth 3 -type d -name conf | head -n 1)")"
  cp -r "$SRC" "$DEST"
  echo "vosk-model-small-en-in-0.4 (Indian English)" > "$INFO"
else
  echo "Indian English model not reachable, using the US English model"
  git clone --depth 1 https://github.com/alphacep/vosk-android-demo.git "$WORK/demo"
  cp -r "$WORK/demo/models/src/main/assets/model-en-us" "$DEST"
  echo "vosk-model-small-en-us-0.15 (US English)" > "$INFO"
fi
python3 -c "import uuid; print(uuid.uuid4())" > "$DEST/uuid"
test -f "$DEST/conf/model.conf" || test -f "$DEST/conf/mfcc.conf"
du -sh "$DEST"
cat "$INFO"
