package com.umar.aienhancer

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrixColorFilter
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.net.Uri
import android.provider.MediaStore
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.SegmentationMask
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object ImageOps {

    // ------------------------------------------------------------ loading

    /** Decodes [uri] (EXIF rotation handled) so that its longest side is at most [maxSide]. */
    fun loadBitmap(context: Context, uri: Uri, maxSide: Int): Bitmap {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val longest = max(info.size.width, info.size.height)
            if (longest > maxSide) {
                val f = maxSide.toFloat() / longest
                decoder.setTargetSize(
                    max(1, (info.size.width * f).roundToInt()),
                    max(1, (info.size.height * f).roundToInt())
                )
            }
        }.let { ensureArgb(it) }
    }

    private fun ensureArgb(b: Bitmap): Bitmap =
        if (b.config == Bitmap.Config.ARGB_8888 && b.isMutable) b
        else b.copy(Bitmap.Config.ARGB_8888, true)

    fun downscaleTo(src: Bitmap, maxSide: Int): Bitmap {
        val longest = max(src.width, src.height)
        if (longest <= maxSide) return src
        val f = maxSide.toFloat() / longest
        return Bitmap.createScaledBitmap(
            src,
            max(1, (src.width * f).roundToInt()),
            max(1, (src.height * f).roundToInt()),
            true
        )
    }

    // ------------------------------------------------------------ adjust / filters

    fun applyAdjust(src: Bitmap, a: Adjust, f: Filters): Bitmap {
        if (a.isDefault && f == Filters.ORIGINAL) return src
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(buildColorMatrix(a, f))
        }
        Canvas(out).drawBitmap(src, 0f, 0f, paint)
        if (a.sharpness > 0.01f) unsharp(out, a.sharpness)
        return out
    }

    /** Unsharp mask / local contrast. [amount] 0..2. Works in place, in row chunks to save memory. */
    private fun unsharp(bmp: Bitmap, amount: Float) {
        val w = bmp.width
        val h = bmp.height
        val factor = max(2, max(w, h) / 600)
        val small = Bitmap.createScaledBitmap(bmp, max(1, w / factor), max(1, h / factor), true)
        val blur = Bitmap.createScaledBitmap(small, w, h, true)
        val k = amount * 0.8f
        val rows = 256
        val a = IntArray(w * rows)
        val b = IntArray(w * rows)
        var y = 0
        while (y < h) {
            val r = min(rows, h - y)
            bmp.getPixels(a, 0, w, 0, y, w, r)
            blur.getPixels(b, 0, w, 0, y, w, r)
            for (i in 0 until w * r) {
                val pa = a[i]
                val pb = b[i]
                val rr = clamp(((pa shr 16) and 0xFF) + k * (((pa shr 16) and 0xFF) - ((pb shr 16) and 0xFF)))
                val gg = clamp(((pa shr 8) and 0xFF) + k * (((pa shr 8) and 0xFF) - ((pb shr 8) and 0xFF)))
                val bb = clamp((pa and 0xFF) + k * ((pa and 0xFF) - (pb and 0xFF)))
                a[i] = (pa and 0xFF000000.toInt()) or (rr shl 16) or (gg shl 8) or bb
            }
            bmp.setPixels(a, 0, w, 0, y, w, r)
            y += r
        }
    }

    private fun clamp(v: Float): Int = v.roundToInt().coerceIn(0, 255)

    /** Finds black/white points (0.5% / 99.5% of luminance) for auto-enhance. */
    fun autoLevels(src: Bitmap): Pair<Float, Float> {
        val s = downscaleTo(src, 256)
        val px = IntArray(s.width * s.height)
        s.getPixels(px, 0, s.width, 0, 0, s.width, s.height)
        val hist = IntArray(256)
        var n = 0
        for (p in px) {
            if ((p ushr 24) < 16) continue
            val lum = (0.299f * ((p shr 16) and 0xFF) + 0.587f * ((p shr 8) and 0xFF) + 0.114f * (p and 0xFF)).toInt()
            hist[lum.coerceIn(0, 255)]++
            n++
        }
        if (n == 0) return 0f to 255f
        val cut = (n * 0.005f).toInt()
        var acc = 0
        var lo = 0
        for (i in 0..255) { acc += hist[i]; if (acc > cut) { lo = i; break } }
        acc = 0
        var hi = 255
        for (i in 255 downTo 0) { acc += hist[i]; if (acc > cut) { hi = i; break } }
        return if (hi - lo < 50) 0f to 255f else lo.toFloat() to hi.toFloat()
    }

    // ------------------------------------------------------------ geometry

    fun rotate90(src: Bitmap): Bitmap {
        val m = Matrix().apply { postRotate(90f) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    fun flipH(src: Bitmap): Bitmap {
        val m = Matrix().apply { postScale(-1f, 1f, src.width / 2f, src.height / 2f) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    // ------------------------------------------------------------ transparency helpers

    /**
     * After AI upscaling the alpha channel is lost; copy it back from [alphaSource],
     * scaled to the size of [rgb].
     */
    fun restoreAlpha(rgb: Bitmap, alphaSource: Bitmap): Bitmap {
        val w = rgb.width
        val h = rgb.height
        val mask = alphaSource.extractAlpha()
        val maskScaled = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(maskScaled).drawBitmap(
            mask, null, Rect(0, 0, w, h),
            Paint(Paint.FILTER_BITMAP_FLAG).apply { color = 0xFF000000.toInt() }
        )
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawBitmap(rgb, 0f, 0f, null)
        c.drawBitmap(maskScaled, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) })
        return out
    }

    // ------------------------------------------------------------ background removal (people)

    suspend fun removeBackground(src: Bitmap): Bitmap {
        val options = SelfieSegmenterOptions.Builder()
            .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
            .build()
        val segmenter = Segmentation.getClient(options)
        try {
            val mask: SegmentationMask = suspendCancellableCoroutine { cont ->
                segmenter.process(InputImage.fromBitmap(src, 0))
                    .addOnSuccessListener { cont.resume(it) }
                    .addOnFailureListener { cont.resumeWithException(it) }
            }
            val mw = mask.width
            val mh = mask.height
            val buf = mask.buffer
            buf.rewind()
            val conf = FloatArray(mw * mh)
            for (i in conf.indices) conf[i] = buf.float

            val w = src.width
            val h = src.height
            val px = IntArray(w * h)
            src.getPixels(px, 0, w, 0, 0, w, h)
            for (y in 0 until h) {
                val my = min(mh - 1, y * mh / h)
                for (x in 0 until w) {
                    val mx = min(mw - 1, x * mw / w)
                    val c = conf[my * mw + mx]
                    val alpha = (((c - 0.3f) / 0.4f).coerceIn(0f, 1f) * 255f).roundToInt()
                    val i = y * w + x
                    px[i] = (alpha shl 24) or (px[i] and 0x00FFFFFF)
                }
            }
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            out.setPixels(px, 0, w, 0, 0, w, h)
            return out
        } finally {
            segmenter.close()
        }
    }

    // ------------------------------------------------------------ saving

    fun saveToGallery(context: Context, bmp: Bitmap, asPng: Boolean): Uri? {
        val name = "AIEnhancer_${System.currentTimeMillis()}"
        val mime = if (asPng) "image/png" else "image/jpeg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name + if (asPng) ".png" else ".jpg")
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/AIEnhancer")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { out ->
                val format = if (asPng) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
                bmp.compress(format, 95, out)
            } ?: error("no output stream")
            val done = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
            uri
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            null
        }
    }
}
