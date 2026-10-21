#!/usr/bin/env bash
# Downloads everything that is too big / not ours to commit:
#   * ncnn prebuilt for Android with Vulkan  -> app/src/main/cpp/ncnn/
#   * Real-ESRGAN ncnn models                -> app/src/main/assets/models/
# Run this once before building locally. GitHub Actions runs it automatically.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CPP_DIR="$ROOT/app/src/main/cpp"
MODEL_DIR="$ROOT/app/src/main/assets/models"
mkdir -p "$MODEL_DIR"

AUTH=()
if [ -n "${GH_TOKEN:-}" ]; then
  AUTH=(-H "Authorization: Bearer ${GH_TOKEN}")
fi

# ---------------------------------------------------------------- ncnn
if [ -d "$CPP_DIR/ncnn/arm64-v8a" ]; then
  echo "ncnn already present, skipping"
else
  echo "Looking up latest ncnn release..."
  URL="$(curl -fsSL "${AUTH[@]}" https://api.github.com/repos/Tencent/ncnn/releases/latest \
    | python3 -c "import sys,json; a=json.load(sys.stdin)['assets']; print(next(x['browser_download_url'] for x in a if x['name'].endswith('android-vulkan.zip')))")"
  echo "Downloading $URL"
  TMP="$(mktemp -d)"
  curl -fsSL -o "$TMP/ncnn.zip" "$URL"
  unzip -q "$TMP/ncnn.zip" -d "$TMP/x"
  ABI_DIR="$(find "$TMP/x" -maxdepth 3 -type d -name arm64-v8a | head -1)"
  if [ -z "$ABI_DIR" ]; then
    echo "arm64-v8a not found inside ncnn zip" >&2
    exit 1
  fi
  rm -rf "$CPP_DIR/ncnn"
  mv "$(dirname "$ABI_DIR")" "$CPP_DIR/ncnn"
  rm -rf "$TMP"
fi

# ---------------------------------------------------------------- models
BASE="https://raw.githubusercontent.com/xinntao/Real-ESRGAN-ncnn-vulkan/master/models"
for NAME in realesr-animevideov3-x4 realesrgan-x4plus; do
  for EXT in param bin; do
    OUT="$MODEL_DIR/$NAME.$EXT"
    if [ ! -s "$OUT" ]; then
      echo "Downloading $NAME.$EXT"
      curl -fsSL -o "$OUT" "$BASE/$NAME.$EXT"
    fi
  done
  # a git-lfs pointer would be only ~130 bytes
  if [ "$(stat -c%s "$MODEL_DIR/$NAME.bin")" -lt 100000 ]; then
    echo "$NAME.bin looks like a Git LFS pointer, not a model" >&2
    exit 1
  fi
done

echo "Assets ready:"
ls -la "$MODEL_DIR"
ls "$CPP_DIR/ncnn"
