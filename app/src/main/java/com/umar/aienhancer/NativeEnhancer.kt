package com.umar.aienhancer

import android.content.Context
import android.graphics.Bitmap
import java.io.File

/** Called by native code after every tile. Return false to cancel. */
fun interface ProgressListener {
    fun onProgress(done: Int, total: Int): Boolean
}

object NativeEnhancer {
    init {
        System.loadLibrary("enhancer")
    }

    external fun gpuCount(): Int

    /** Loads a Real-ESRGAN ncnn model once. Returns a handle (0 = failed). Free it with [destroySession]. */
    external fun createSession(paramPath: String, binPath: String, useGpu: Boolean): Long

    external fun destroySession(handle: Long)

    /**
     * Runs the loaded model tile by tile.
     * [input] and [output] must be ARGB_8888, output = input * modelScale in both sides.
     */
    external fun upscaleWith(
        handle: Long,
        input: Bitmap,
        output: Bitmap,
        modelScale: Int,
        tile: Int,
        listener: ProgressListener
    ): Boolean

    /** One-shot helper for single photos. */
    fun upscale(
        input: Bitmap,
        output: Bitmap,
        paramPath: String,
        binPath: String,
        modelScale: Int,
        tile: Int,
        useGpu: Boolean,
        listener: ProgressListener
    ): Boolean {
        val handle = createSession(paramPath, binPath, useGpu)
        if (handle == 0L) return false
        try {
            return upscaleWith(handle, input, output, modelScale, tile, listener)
        } finally {
            destroySession(handle)
        }
    }
}

enum class UpscaleModel(val label: String, val hint: String, val file: String, val tile: Int) {
    PHOTO("Photo (best)", "Real-ESRGAN x4+  -  most detail but VERY slow on phones, use for small photos only", "realesrgan-x4plus", 192),
    FAST("Fast", "Compact model  -  several times faster, recommended", "realesr-animevideov3-x4", 256)
}

object ModelStore {
    /** Copies the model out of the APK assets (once) and returns (paramPath, binPath). */
    fun ensure(context: Context, model: UpscaleModel): Pair<String, String> {
        val dir = File(context.filesDir, "models").apply { mkdirs() }
        val param = copyAsset(context, "models/${model.file}.param", File(dir, "${model.file}.param"))
        val bin = copyAsset(context, "models/${model.file}.bin", File(dir, "${model.file}.bin"))
        return param to bin
    }

    private fun copyAsset(context: Context, asset: String, dest: File): String {
        if (!dest.exists() || dest.length() == 0L) {
            val tmp = File(dest.parentFile, dest.name + ".tmp")
            context.assets.open(asset).use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            }
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
        }
        return dest.absolutePath
    }
}
