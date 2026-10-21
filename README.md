# AI Enhancer (Android)

Photos ko HD karne wali Android app. Sab kuch phone par chalta hai, internet ki zaroorat nahi.

## Features
- **AI HD upscale** - Real-ESRGAN (ncnn, Vulkan GPU) se 2x / 4x, do models: *Photo (best)* aur *Fast*
- **Before / after compare** - slider drag karke original aur enhanced dekhein
- **Adjust** - brightness, contrast, saturation, warmth, sharpness, auto enhance
- **Filters** - Vivid, Warm, Cool, Fade, Dramatic, Sepia, B&W, Noir
- **Remove background** (logon ki photos par) - transparent PNG
- Rotate, flip, undo, back to original
- Gallery mein save (`Pictures/AIEnhancer`) aur share

## APK download karna
1. GitHub par is repo ke **Releases** page par jayen, **"AI Enhancer - latest build"** kholein.
2. `AIEnhancer.apk` download karke phone mein install karein.

Har push par GitHub Actions khud naya APK banati hai (`.github/workflows/build-apk.yml`).
Build ke dauran ncnn aur models automatically download hote hain (`scripts/fetch_assets.sh`).

## Local build (optional)
```bash
bash scripts/fetch_assets.sh
gradle assembleDebug      # Gradle 8.9 (ya Android Studio se open karke Run karein)
```
Chahiye: JDK 17, Android SDK 35, NDK 27.2.12479018, CMake 3.22.1.

## Notes
- Phone par Vulkan GPU use hota hai. Agar app crash kare to AI HD tab mein **Use GPU** band kar dein.
- *Photo (best)* model bhari hai, CPU par bahut slow hoga. GPU wale phone par chalaein.
- Models: [Real-ESRGAN-ncnn-vulkan](https://github.com/xinntao/Real-ESRGAN-ncnn-vulkan) (BSD-3), inference: [ncnn](https://github.com/Tencent/ncnn) (BSD-3).
