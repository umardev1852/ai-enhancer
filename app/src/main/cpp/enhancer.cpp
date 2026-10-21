// Real-ESRGAN (ncnn) tiled super-resolution for Android bitmaps.
//
// Kotlin side:  object NativeEnhancer { external fun upscale(...) }
// The model (param + bin) is any Real-ESRGAN ncnn model with input blob "data"
// and output blob "output" (realesr-animevideov3-x4, realesrgan-x4plus, ...).

#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>

#include <algorithm>
#include <cstdint>
#include <string>
#include <vector>

#include "net.h"
#include "gpu.h"

#define LOG_TAG "Enhancer"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM*, void*)
{
    ncnn::create_gpu_instance();
    LOGI("ncnn gpu count = %d", ncnn::get_gpu_count());
    return JNI_VERSION_1_4;
}

extern "C" JNIEXPORT void JNICALL JNI_OnUnload(JavaVM*, void*)
{
    ncnn::destroy_gpu_instance();
}

namespace {

// RAII helper so pixels are always unlocked.
struct BitmapLock
{
    JNIEnv* env;
    jobject bmp;
    void* ptr = nullptr;
    bool ok = false;

    BitmapLock(JNIEnv* e, jobject b) : env(e), bmp(b)
    {
        ok = AndroidBitmap_lockPixels(env, bmp, &ptr) == ANDROID_BITMAP_RESULT_SUCCESS && ptr != nullptr;
    }
    ~BitmapLock()
    {
        if (ok) AndroidBitmap_unlockPixels(env, bmp);
    }
};

std::string toStdString(JNIEnv* env, jstring s)
{
    const char* chars = env->GetStringUTFChars(s, nullptr);
    std::string out(chars ? chars : "");
    if (chars) env->ReleaseStringUTFChars(s, chars);
    return out;
}

} // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_umar_aienhancer_NativeEnhancer_gpuCount(JNIEnv*, jobject)
{
    return ncnn::get_gpu_count();
}

// Upscales `inBmp` (RGBA_8888) into `outBmp` (RGBA_8888, size = in * modelScale).
// Returns true on success, false on failure or when the listener asked to cancel.
extern "C" JNIEXPORT jboolean JNICALL
Java_com_umar_aienhancer_NativeEnhancer_upscale(JNIEnv* env, jobject /*thiz*/,
                                                jobject inBmp, jobject outBmp,
                                                jstring jParam, jstring jBin,
                                                jint modelScale, jint tileSize,
                                                jboolean useGpu, jobject listener)
{
    AndroidBitmapInfo ii;
    AndroidBitmapInfo oi;
    if (AndroidBitmap_getInfo(env, inBmp, &ii) != ANDROID_BITMAP_RESULT_SUCCESS ||
        AndroidBitmap_getInfo(env, outBmp, &oi) != ANDROID_BITMAP_RESULT_SUCCESS)
    {
        LOGE("getInfo failed");
        return JNI_FALSE;
    }
    if (ii.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || oi.format != ANDROID_BITMAP_FORMAT_RGBA_8888)
    {
        LOGE("bitmaps must be RGBA_8888");
        return JNI_FALSE;
    }

    const int S = modelScale;
    const int w = (int)ii.width;
    const int h = (int)ii.height;
    if ((int)oi.width != w * S || (int)oi.height != h * S)
    {
        LOGE("output size mismatch: in %dx%d out %ux%u scale %d", w, h, oi.width, oi.height, S);
        return JNI_FALSE;
    }

    BitmapLock lockIn(env, inBmp);
    BitmapLock lockOut(env, outBmp);
    if (!lockIn.ok || !lockOut.ok)
    {
        LOGE("lockPixels failed");
        return JNI_FALSE;
    }

    const std::string paramPath = toStdString(env, jParam);
    const std::string binPath = toStdString(env, jBin);

    ncnn::Net net;
    const bool gpu = (useGpu == JNI_TRUE) && ncnn::get_gpu_count() > 0;
    net.opt.use_vulkan_compute = gpu;
    LOGI("model=%s gpu=%d tile=%d", paramPath.c_str(), (int)gpu, (int)tileSize);

    if (net.load_param(paramPath.c_str()) != 0 || net.load_model(binPath.c_str()) != 0)
    {
        LOGE("failed to load model");
        return JNI_FALSE;
    }

    jclass listenerCls = env->GetObjectClass(listener);
    jmethodID onProgress = env->GetMethodID(listenerCls, "onProgress", "(II)Z");
    if (onProgress == nullptr)
    {
        LOGE("listener.onProgress not found");
        return JNI_FALSE;
    }

    const int pad = 10;
    const int tile = std::max(32, (int)tileSize);
    const int xTiles = (w + tile - 1) / tile;
    const int yTiles = (h + tile - 1) / tile;
    const int total = xTiles * yTiles;

    const uint8_t* src = static_cast<const uint8_t*>(lockIn.ptr);
    uint8_t* dst = static_cast<uint8_t*>(lockOut.ptr);
    const size_t srcStride = ii.stride;
    const size_t dstStride = oi.stride;

    const float meanVals[3] = {0.f, 0.f, 0.f};
    const float normIn[3] = {1.f / 255.f, 1.f / 255.f, 1.f / 255.f};
    const float normOut[3] = {255.f, 255.f, 255.f};

    if (env->CallBooleanMethod(listener, onProgress, 0, total) == JNI_FALSE)
        return JNI_FALSE;

    int done = 0;
    for (int yi = 0; yi < yTiles; yi++)
    {
        for (int xi = 0; xi < xTiles; xi++)
        {
            // area of the source image this tile is responsible for
            const int x0 = xi * tile;
            const int y0 = yi * tile;
            const int x1 = std::min(x0 + tile, w);
            const int y1 = std::min(y0 + tile, h);

            // same area with some extra context around it
            const int ix0 = std::max(x0 - pad, 0);
            const int iy0 = std::max(y0 - pad, 0);
            const int ix1 = std::min(x1 + pad, w);
            const int iy1 = std::min(y1 + pad, h);
            const int tw = ix1 - ix0;
            const int th = iy1 - iy0;

            ncnn::Mat in = ncnn::Mat::from_pixels(src + (size_t)iy0 * srcStride + (size_t)ix0 * 4,
                                                  ncnn::Mat::PIXEL_RGBA2RGB, tw, th, (int)srcStride);
            in.substract_mean_normalize(meanVals, normIn);

            ncnn::Mat out;
            {
                ncnn::Extractor ex = net.create_extractor();
                ex.input("data", in);
                if (ex.extract("output", out) != 0)
                {
                    LOGE("extract failed at tile %d,%d", xi, yi);
                    return JNI_FALSE;
                }
            }
            out.substract_mean_normalize(nullptr, normOut);

            std::vector<uint8_t> rgb((size_t)out.w * out.h * 3);
            out.to_pixels(rgb.data(), ncnn::Mat::PIXEL_RGB);

            // copy the useful (non-padding) part into the big output image
            const int offX = (x0 - ix0) * S;
            const int offY = (y0 - iy0) * S;
            const int copyW = (x1 - x0) * S;
            const int copyH = (y1 - y0) * S;
            for (int r = 0; r < copyH; r++)
            {
                const uint8_t* from = rgb.data() + ((size_t)(offY + r) * out.w + offX) * 3;
                uint8_t* to = dst + (size_t)(y0 * S + r) * dstStride + (size_t)x0 * S * 4;
                for (int c = 0; c < copyW; c++)
                {
                    to[c * 4 + 0] = from[c * 3 + 0];
                    to[c * 4 + 1] = from[c * 3 + 1];
                    to[c * 4 + 2] = from[c * 3 + 2];
                    to[c * 4 + 3] = 255;
                }
            }

            done++;
            if (env->CallBooleanMethod(listener, onProgress, done, total) == JNI_FALSE)
                return JNI_FALSE;
        }
    }

    return JNI_TRUE;
}
