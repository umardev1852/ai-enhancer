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

fail() {
  echo "::error::$*"
  echo "$*" >&2
  exit 1
}

# curl with a readable error instead of a bare exit code
fetch() { # url out
  local url="$1" out="$2" code
  code="$(curl -sSL "${AUTH[@]}" -o "$out" -w '%{http_code}' "$url" || true)"
  if [ "$code" != "200" ]; then
    fail "Download failed (HTTP $code): $url"
  fi
}

# ---------------------------------------------------------------- ncnn
if [ -d "$CPP_DIR/ncnn/arm64-v8a" ]; then
  echo "ncnn already present, skipping"
else
  echo "Looking up latest ncnn release..."
  TMP="$(mktemp -d)"
  fetch "https://api.github.com/repos/Tencent/ncnn/releases/latest" "$TMP/release.json"
  URL="$(python3 - "$TMP/release.json" <<'PY'
import json, sys
assets = json.load(open(sys.argv[1]))["assets"]
names = [a["name"] for a in assets]
for a in assets:
    n = a["name"]
    if n.endswith("android-vulkan.zip"):
        print(a["browser_download_url"])
        break
else:
    print("NONE android assets: " + ", ".join(n for n in names if "android" in n))
PY
)"
  case "$URL" in
    http*) ;;
    *) fail "No android-vulkan zip in latest ncnn release. $URL" ;;
  esac
  echo "Downloading $URL"
  fetch "$URL" "$TMP/ncnn.zip"
  unzip -q "$TMP/ncnn.zip" -d "$TMP/x"
  ABI_DIR="$(find "$TMP/x" -maxdepth 3 -type d -name arm64-v8a | head -1)"
  [ -n "$ABI_DIR" ] || fail "arm64-v8a not found inside the ncnn zip"
  rm -rf "$CPP_DIR/ncnn"
  mv "$(dirname "$ABI_DIR")" "$CPP_DIR/ncnn"
  rm -rf "$TMP"
fi

# ---------------------------------------------------------------- models
# The models are not stored in the git repo itself; they ship inside the release zips
# of Real-ESRGAN-ncnn-vulkan (the Linux zip is the smallest that contains them).
need_models=0
for NAME in realesr-animevideov3-x4 realesrgan-x4plus; do
  for EXT in param bin; do
    [ -s "$MODEL_DIR/$NAME.$EXT" ] || need_models=1
  done
done

if [ "$need_models" = "1" ]; then
  echo "Looking up latest Real-ESRGAN-ncnn-vulkan release..."
  TMP="$(mktemp -d)"
  fetch "https://api.github.com/repos/xinntao/Real-ESRGAN-ncnn-vulkan/releases/latest" "$TMP/release.json"
  URL="$(python3 - "$TMP/release.json" <<'PY'
import json, sys
assets = json.load(open(sys.argv[1]))["assets"]
for a in assets:
    if a["name"].endswith("ubuntu.zip"):
        print(a["browser_download_url"])
        break
else:
    print("NONE " + ", ".join(a["name"] for a in assets))
PY
)"
  case "$URL" in
    http*) ;;
    *) fail "No ubuntu zip in Real-ESRGAN-ncnn-vulkan release. Assets: $URL" ;;
  esac
  echo "Downloading $URL"
  fetch "$URL" "$TMP/models.zip"
  unzip -q "$TMP/models.zip" -d "$TMP/m"
  echo "Models found in zip:"
  find "$TMP/m" -name '*.param' -printf '  %f\n'
  for NAME in realesr-animevideov3-x4 realesrgan-x4plus; do
    for EXT in param bin; do
      SRC="$(find "$TMP/m" -name "$NAME.$EXT" | head -1)"
      [ -n "$SRC" ] || fail "Model file $NAME.$EXT not found in zip $URL. Files: $(find "$TMP/m" -type f | sed "s#$TMP/m/##" | tr '\n' ' ')"
      cp "$SRC" "$MODEL_DIR/$NAME.$EXT"
    done
  done
  rm -rf "$TMP"
fi

for NAME in realesr-animevideov3-x4 realesrgan-x4plus; do
  # a git-lfs pointer / error page would be tiny
  if [ "$(stat -c%s "$MODEL_DIR/$NAME.bin")" -lt 100000 ]; then
    fail "$NAME.bin is suspiciously small, not a real model"
  fi
done

echo "Assets ready:"
ls -la "$MODEL_DIR"
ls "$CPP_DIR/ncnn"
