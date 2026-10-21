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
LISTING="$(mktemp)"
fetch "https://api.github.com/repos/xinntao/Real-ESRGAN-ncnn-vulkan/contents/models" "$LISTING"
echo "Models available upstream:"
python3 - "$LISTING" <<'PY'
import json, sys
for f in json.load(open(sys.argv[1])):
    print("  ", f["name"], f.get("size"))
PY

for NAME in realesr-animevideov3-x4 realesrgan-x4plus; do
  for EXT in param bin; do
    OUT="$MODEL_DIR/$NAME.$EXT"
    if [ ! -s "$OUT" ]; then
      URL="$(python3 - "$LISTING" "$NAME.$EXT" <<'PY'
import json, sys
want = sys.argv[2]
for f in json.load(open(sys.argv[1])):
    if f["name"] == want:
        print(f["download_url"])
        break
else:
    print("MISSING " + ", ".join(f["name"] for f in json.load(open(sys.argv[1]))))
PY
)"
      case "$URL" in
        http*) ;;
        *) fail "Model file $NAME.$EXT not found upstream. Available: $URL" ;;
      esac
      echo "Downloading $NAME.$EXT"
      fetch "$URL" "$OUT"
    fi
  done
  # a git-lfs pointer would be only ~130 bytes
  if [ "$(stat -c%s "$MODEL_DIR/$NAME.bin")" -lt 100000 ]; then
    fail "$NAME.bin looks like a Git LFS pointer, not a real model"
  fi
done

echo "Assets ready:"
ls -la "$MODEL_DIR"
ls "$CPP_DIR/ncnn"
