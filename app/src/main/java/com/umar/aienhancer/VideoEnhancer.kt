package com.umar.aienhancer

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.provider.MediaStore
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Video enhancer: every frame goes through the AI model, then the frames are re-encoded to H.264
 * and the original audio track is copied over untouched.
 */
class VideoEnhancer(private val context: Context) {

    data class Info(
        val width: Int,
        val height: Int,
        val durationMs: Long,
        val fps: Float,
        val hasAudio: Boolean
    )

    data class Options(
        val model: UpscaleModel,
        val maxInputSide: Int,
        val outScale: Int,     // 2 or 4
        val maxSeconds: Int,
        val useGpu: Boolean
    )

    companion object {
        /** Fits (w,h) so the longest side is at most [maxSide]; never enlarges. */
        fun fitSize(w: Int, h: Int, maxSide: Int): Pair<Int, Int> {
            val longest = max(w, h)
            if (longest <= maxSide) return max(2, w) to max(2, h)
            val f = maxSide.toFloat() / longest
            return max(2, (w * f).roundToInt()) to max(2, (h * f).roundToInt())
        }

        fun processedFps(sourceFps: Float): Float = sourceFps.coerceIn(5f, 30f)

        fun frameCount(info: Info, maxSeconds: Int): Int {
            val seconds = min(info.durationMs / 1000f, maxSeconds.toFloat())
            return max(1, (seconds * processedFps(info.fps)).toInt())
        }
    }

    // ------------------------------------------------------------------ probe

    fun probe(uri: Uri): Info {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(context, uri, null)
            var video: MediaFormat? = null
            var hasAudio = false
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") && video == null) video = f
                else if (mime.startsWith("audio/")) hasAudio = true
            }
            val f = video ?: throw IllegalStateException("This file has no video track")
            var w = f.getInteger(MediaFormat.KEY_WIDTH)
            var h = f.getInteger(MediaFormat.KEY_HEIGHT)
            val rotation = if (f.containsKey(MediaFormat.KEY_ROTATION)) f.getInteger(MediaFormat.KEY_ROTATION) else 0
            if (rotation == 90 || rotation == 270) {
                val t = w; w = h; h = t
            }
            val durationUs = if (f.containsKey(MediaFormat.KEY_DURATION)) f.getLong(MediaFormat.KEY_DURATION) else 0L
            val fps = if (f.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                runCatching { f.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat() }
                    .getOrElse { runCatching { f.getFloat(MediaFormat.KEY_FRAME_RATE) }.getOrDefault(30f) }
            } else 30f
            return Info(w, h, durationUs / 1000, if (fps > 0f) fps else 30f, hasAudio)
        } finally {
            ex.release()
        }
    }

    // ------------------------------------------------------------------ main pipeline

    /**
     * Returns true when the file was written, false when cancelled. Throws with a readable message
     * when something goes wrong.
     */
    fun run(
        uri: Uri,
        o: Options,
        out: File,
        onProgress: (done: Int, total: Int) -> Unit,
        cancelled: () -> Boolean
    ): Boolean {
        val info = probe(uri)
        val fps = processedFps(info.fps)
        val total = frameCount(info, o.maxSeconds)
        val clipUs = (total * 1_000_000.0 / fps).toLong()

        val retriever = MediaMetadataRetriever()
        var session = 0L
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false

        try {
            retriever.setDataSource(context, uri)
            var last = grab(retriever, 0L) ?: throw IllegalStateException("Could not read the first frame")

            val (iw, ih) = fitSize(last.width, last.height, o.maxInputSide)
            val ow = iw * o.outScale
            val oh = ih * o.outScale

            // ---- AI model
            val (param, bin) = ModelStore.ensure(context, o.model)
            val wantGpu = o.useGpu && NativeEnhancer.gpuCount() > 0
            session = NativeEnhancer.createSession(param, bin, wantGpu)
            if (session == 0L) throw IllegalStateException("Could not load the AI model")

            // ---- encoder
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, ow, oh).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, (ow.toLong() * oh * fps * 0.12).toLong().coerceIn(2_000_000L, 40_000_000L).toInt())
                setInteger(MediaFormat.KEY_FRAME_RATE, fps.roundToInt())
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            val codecName = MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(format)
                ?: throw IllegalStateException(
                    "This phone's video encoder cannot make ${ow}x$oh. Choose a smaller input size or 2x output."
                )
            val enc = MediaCodec.createByCodecName(codecName)
            encoder = enc
            enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            enc.start()

            val mux = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = mux
            var videoTrack = -1
            var audioTrack = -1
            val audioExtractor: MediaExtractor? = if (info.hasAudio) openAudio(uri) else null
            val bi = MediaCodec.BufferInfo()

            fun drain(eos: Boolean) {
                var spins = 0
                while (true) {
                    val idx = enc.dequeueOutputBuffer(bi, if (eos) 10_000L else 0L)
                    if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        if (!eos) return
                        if (++spins > 500) throw IllegalStateException("The video encoder did not finish")
                        continue
                    }
                    if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        videoTrack = mux.addTrack(enc.outputFormat)
                        if (audioExtractor != null) {
                            audioTrack = runCatching { mux.addTrack(audioExtractor.getTrackFormat(audioExtractor.sampleTrackIndex)) }
                                .getOrDefault(-1)
                        }
                        mux.start()
                        muxerStarted = true
                        continue
                    }
                    if (idx < 0) continue
                    val buf = enc.getOutputBuffer(idx)
                    if (bi.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) bi.size = 0
                    if (bi.size > 0 && buf != null && muxerStarted) {
                        buf.position(bi.offset)
                        buf.limit(bi.offset + bi.size)
                        mux.writeSampleData(videoTrack, buf, bi)
                    }
                    enc.releaseOutputBuffer(idx, false)
                    if (bi.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }

            fun feed(argb: IntArray, ptsUs: Long, eos: Boolean) {
                while (true) {
                    val ii = enc.dequeueInputBuffer(10_000L)
                    if (ii < 0) {
                        drain(false)
                        continue
                    }
                    if (eos) {
                        enc.queueInputBuffer(ii, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        return
                    }
                    val image = enc.getInputImage(ii)
                        ?: throw IllegalStateException("This phone's encoder does not accept YUV frames")
                    fillImage(image, argb, ow, oh)
                    enc.queueInputBuffer(ii, 0, ow * oh * 3 / 2, ptsUs, 0)
                    drain(false)
                    return
                }
            }

            // ---- reusable buffers
            val inBmp = Bitmap.createBitmap(iw, ih, Bitmap.Config.ARGB_8888)
            val inCanvas = Canvas(inBmp)
            val big = Bitmap.createBitmap(iw * 4, ih * 4, Bitmap.Config.ARGB_8888)
            val smooth = Paint(Paint.FILTER_BITMAP_FLAG)
            val outBmp = if (o.outScale == 4) null else Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
            val outCanvas = outBmp?.let { Canvas(it) }
            val argb = IntArray(ow * oh)

            onProgress(0, total)
            var lastPts = 0L
            for (i in 0 until total) {
                if (cancelled()) return false
                val tUs = (i * 1_000_000.0 / fps).toLong()
                val frame = if (i == 0) last else (grab(retriever, tUs) ?: last)
                last = frame

                inCanvas.drawBitmap(frame, null, Rect(0, 0, iw, ih), smooth)
                val ok = NativeEnhancer.upscaleWith(
                    session, inBmp, big, 4, o.model.tile,
                    ProgressListener { _, _ -> !cancelled() }
                )
                if (!ok) {
                    if (cancelled()) return false
                    throw IllegalStateException("The AI step failed on frame ${i + 1}. Try turning GPU off or a smaller size.")
                }

                val result: Bitmap = if (outBmp != null && outCanvas != null) {
                    outCanvas.drawBitmap(big, null, Rect(0, 0, ow, oh), smooth)
                    outBmp
                } else big
                result.getPixels(argb, 0, ow, 0, 0, ow, oh)

                feed(argb, tUs, false)
                lastPts = tUs
                onProgress(i + 1, total)
            }

            feed(argb, lastPts + 1, true)
            drain(true)

            if (!muxerStarted) throw IllegalStateException("The encoder produced no video")
            if (audioExtractor != null && audioTrack >= 0) {
                copyAudio(audioExtractor, mux, audioTrack, clipUs)
            }
            audioExtractor?.release()

            mux.stop()
            muxerStarted = false
            return true
        } finally {
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { retriever.release() }
            if (session != 0L) NativeEnhancer.destroySession(session)
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun grab(r: MediaMetadataRetriever, timeUs: Long): Bitmap? {
        val raw = r.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST) ?: return null
        return if (raw.config == Bitmap.Config.ARGB_8888) raw else raw.copy(Bitmap.Config.ARGB_8888, false)
    }

    private fun openAudio(uri: Uri): MediaExtractor? {
        val ex = MediaExtractor()
        return try {
            ex.setDataSource(context, uri, null)
            var found = -1
            for (i in 0 until ex.trackCount) {
                val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    found = i
                    break
                }
            }
            if (found < 0) {
                ex.release()
                null
            } else {
                ex.selectTrack(found)
                ex
            }
        } catch (t: Throwable) {
            ex.release()
            null
        }
    }

    private fun copyAudio(ex: MediaExtractor, mux: MediaMuxer, track: Int, maxUs: Long) {
        val fmt = ex.getTrackFormat(ex.sampleTrackIndex)
        val size = if (fmt.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) fmt.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0
        val buf = ByteBuffer.allocate(max(size, 1 shl 20))
        val bi = MediaCodec.BufferInfo()
        ex.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        while (true) {
            buf.clear()
            val n = ex.readSampleData(buf, 0)
            if (n < 0) break
            val t = ex.sampleTime
            if (t < 0 || t > maxUs) break
            bi.set(0, n, t, if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
            mux.writeSampleData(track, buf, bi)
            ex.advance()
        }
    }

    /** ARGB -> YUV420 (BT.601, limited range) straight into the encoder's input image. */
    private fun fillImage(image: Image, argb: IntArray, w: Int, h: Int) {
        val yp = image.planes[0]
        val up = image.planes[1]
        val vp = image.planes[2]
        val yb = yp.buffer
        val ub = up.buffer
        val vb = vp.buffer
        val yRow = yp.rowStride
        val yPx = yp.pixelStride
        val uRow = up.rowStride
        val uPx = up.pixelStride
        val vRow = vp.rowStride
        val vPx = vp.pixelStride

        for (y in 0 until h) {
            val base = y * w
            val yOff = y * yRow
            for (x in 0 until w) {
                val p = argb[base + x]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                yb.put(yOff + x * yPx, (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).toByte())
            }
        }

        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                var r = 0
                var g = 0
                var b = 0
                for (dy in 0..1) {
                    for (dx in 0..1) {
                        val p = argb[(y + dy) * w + x + dx]
                        r += (p shr 16) and 0xFF
                        g += (p shr 8) and 0xFF
                        b += p and 0xFF
                    }
                }
                r /= 4; g /= 4; b /= 4
                val u = (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).coerceIn(0, 255)
                val v = (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).coerceIn(0, 255)
                ub.put((y / 2) * uRow + (x / 2) * uPx, u.toByte())
                vb.put((y / 2) * vRow + (x / 2) * vPx, v.toByte())
                x += 2
            }
            y += 2
        }
    }
}

/** Copies [file] into Movies/AIEnhancer so it shows up in the gallery. */
fun saveVideoToGallery(context: Context, file: File): Uri? {
    val values = ContentValues().apply {
        put(MediaStore.Video.Media.DISPLAY_NAME, "AIEnhancer_${System.currentTimeMillis()}.mp4")
        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/AIEnhancer")
        put(MediaStore.Video.Media.IS_PENDING, 1)
    }
    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return null
    return try {
        resolver.openOutputStream(uri)?.use { o -> file.inputStream().use { it.copyTo(o) } }
            ?: throw IllegalStateException("no output stream")
        val done = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
        resolver.update(uri, done, null, null)
        uri
    } catch (t: Throwable) {
        resolver.delete(uri, null, null)
        null
    }
}
