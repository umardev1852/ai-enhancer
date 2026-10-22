package com.umar.aienhancer

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

/** Longest side of the on-screen previews. Large enough that pinch-zoom still shows real detail. */
private const val PREVIEW_SIDE = 2400

private class Snapshot(val bitmap: Bitmap, val hasAlpha: Boolean, val before: Bitmap?)

class EditorViewModel(app: Application) : AndroidViewModel(app) {

    // ---- what the UI shows
    var base by mutableStateOf<Bitmap?>(null); private set
    var baseHasAlpha by mutableStateOf(false); private set
    var beforePreview by mutableStateOf<Bitmap?>(null); private set
    var afterPreview by mutableStateOf<Bitmap?>(null); private set
    var adjust by mutableStateOf(Adjust()); private set
    var filter by mutableStateOf(Filters.ORIGINAL); private set
    var busy by mutableStateOf(false); private set
    var progress by mutableFloatStateOf(-1f); private set   // -1 = indeterminate
    var status by mutableStateOf<String?>(null); private set
    var undoDepth by mutableIntStateOf(0); private set
    var eta by mutableStateOf(""); private set

    // ---- AI settings
    var model by mutableStateOf(UpscaleModel.FAST)
    var outScale by mutableIntStateOf(4)
    var maxInput by mutableIntStateOf(512)
    var useGpu by mutableStateOf(true)

    // ---- face retouch settings
    var faceParams by mutableStateOf(FaceParams())

    private var originalFull: Bitmap? = null
    private var basePreview: Bitmap? = null
    private val undo = ArrayDeque<Snapshot>()
    private var refreshJob: Job? = null

    @Volatile
    private var cancelled = false

    // ------------------------------------------------------------ open

    fun load(uri: Uri) {
        if (busy) return
        viewModelScope.launch(Dispatchers.Default) {
            busy = true
            progress = -1f
            status = "Opening photo..."
            try {
                val bmp = ImageOps.loadBitmap(getApplication(), uri, 4096)
                originalFull = bmp
                undo.clear()
                undoDepth = 0
                adjust = Adjust()
                filter = Filters.ORIGINAL
                beforePreview = ImageOps.downscaleTo(bmp, PREVIEW_SIDE)
                setBase(bmp, alpha = false, push = false)
                status = "Loaded ${bmp.width}x${bmp.height}"
            } catch (t: Throwable) {
                status = "Could not open this photo"
            } finally {
                busy = false
            }
        }
    }

    // ------------------------------------------------------------ state helpers

    private fun setBase(b: Bitmap, alpha: Boolean, push: Boolean) {
        val current = base
        if (push && current != null) {
            undo.addLast(Snapshot(current, baseHasAlpha, beforePreview))
            while (undo.size > 3) undo.removeFirst()
            undoDepth = undo.size
        }
        base = b
        baseHasAlpha = alpha
        basePreview = ImageOps.downscaleTo(b, PREVIEW_SIDE)
        recompute()
    }

    private fun recompute() {
        val preview = basePreview ?: return
        val a = adjust
        val f = filter
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch(Dispatchers.Default) {
            afterPreview = ImageOps.applyAdjust(preview, a, f)
        }
    }

    // ------------------------------------------------------------ adjust / filters

    fun updateAdjust(a: Adjust) {
        adjust = a
        recompute()
    }

    fun updateFilter(f: Filters) {
        filter = f
        recompute()
    }

    fun resetAdjust() {
        adjust = Adjust()
        filter = Filters.ORIGINAL
        recompute()
    }

    fun autoEnhance() {
        val preview = basePreview ?: return
        viewModelScope.launch(Dispatchers.Default) {
            val (lo, hi) = ImageOps.autoLevels(preview)
            adjust = adjust.copy(
                levelLow = lo,
                levelHigh = hi,
                saturation = max(adjust.saturation, 1.12f),
                sharpness = max(adjust.sharpness, 0.5f)
            )
            recompute()
            status = "Auto enhance applied"
        }
    }

    // ------------------------------------------------------------ AI HD

    fun cancelWork() {
        cancelled = true
    }

    fun runUpscale() {
        val src = base ?: return
        if (busy) return
        viewModelScope.launch(Dispatchers.Default) {
            busy = true
            progress = 0f
            cancelled = false
            try {
                status = "Preparing AI model..."
                val limited = ImageOps.downscaleTo(src, maxInput)
                val (param, bin) = ModelStore.ensure(getApplication(), model)
                val big = Bitmap.createBitmap(limited.width * 4, limited.height * 4, Bitmap.Config.ARGB_8888)
                val wantGpu = useGpu && NativeEnhancer.gpuCount() > 0
                status = if (wantGpu) "Enhancing with GPU..." else "No GPU found - using CPU, this will be very slow"
                val t0 = SystemClock.elapsedRealtime()
                val ok = NativeEnhancer.upscale(
                    limited, big, param, bin, 4, model.tile, wantGpu,
                    ProgressListener { done, total ->
                        progress = if (total > 0) done.toFloat() / total else -1f
                        if (done > 0 && total > 0) {
                            val perTile = (SystemClock.elapsedRealtime() - t0) / done.toFloat()
                            val left = (perTile * (total - done) / 1000f).toInt()
                            eta = "About ${left / 60} min ${left % 60} s left"
                        }
                        !cancelled
                    }
                )
                if (!ok) {
                    status = if (cancelled) "Cancelled" else "AI enhance failed (try CPU mode or a smaller size)"
                    return@launch
                }
                var result = if (outScale == 4) big else Bitmap.createScaledBitmap(
                    big, limited.width * outScale, limited.height * outScale, true
                )
                if (baseHasAlpha) result = ImageOps.restoreAlpha(result, limited)
                setBase(result, baseHasAlpha, push = true)
                val shrunk = if (maxOf(src.width, src.height) > maxInput)
                    "  (photo was first shrunk to ${limited.width}x${limited.height}, raise 'Max input size' for more detail)" else ""
                status = "Done: ${src.width}x${src.height} -> ${result.width}x${result.height}$shrunk"
            } catch (t: OutOfMemoryError) {
                status = "Not enough memory - pick a smaller max input size"
            } catch (t: Throwable) {
                status = "AI enhance failed: ${t.message}"
            } finally {
                busy = false
                progress = -1f
                eta = ""
            }
        }
    }

    // ------------------------------------------------------------ tools

    fun retouchFaces() {
        val src = base ?: return
        if (busy) return
        val params = faceParams
        viewModelScope.launch(Dispatchers.Default) {
            busy = true
            progress = -1f
            status = "Looking for faces..."
            try {
                val faces = FaceRetouch.detect(src)
                if (faces.isEmpty()) {
                    status = "No face found in this photo"
                } else {
                    status = "Retouching ${faces.size} face(s)..."
                    val out = FaceRetouch.apply(src, faces, params)
                    setBase(out, baseHasAlpha, push = true)
                    status = "Retouched ${faces.size} face(s)"
                }
            } catch (t: OutOfMemoryError) {
                status = "Not enough memory for this photo size"
            } catch (t: Throwable) {
                status = "Face retouch failed: ${t.message}"
            } finally {
                busy = false
            }
        }
    }

    fun removeBackground() {
        val src = base ?: return
        if (busy) return
        viewModelScope.launch(Dispatchers.Default) {
            busy = true
            progress = -1f
            status = "Removing background (works best on people)..."
            try {
                val limited = ImageOps.downscaleTo(src, 2048)
                val out = ImageOps.removeBackground(limited)
                setBase(out, alpha = true, push = true)
                status = "Background removed - it will be saved as PNG"
            } catch (t: Throwable) {
                status = "Background removal failed: ${t.message}"
            } finally {
                busy = false
            }
        }
    }

    fun rotate() = transform(rotate = true)

    fun flip() = transform(rotate = false)

    private fun transform(rotate: Boolean) {
        val src = base ?: return
        if (busy) return
        viewModelScope.launch(Dispatchers.Default) {
            busy = true
            progress = -1f
            try {
                val nb = if (rotate) ImageOps.rotate90(src) else ImageOps.flipH(src)
                val before = beforePreview
                setBase(nb, baseHasAlpha, push = true)
                beforePreview = before?.let { if (rotate) ImageOps.rotate90(it) else ImageOps.flipH(it) }
            } catch (t: Throwable) {
                status = "Failed: ${t.message}"
            } finally {
                busy = false
            }
        }
    }

    fun undo() {
        if (busy || undo.isEmpty()) return
        val s = undo.removeLast()
        undoDepth = undo.size
        beforePreview = s.before
        base = s.bitmap
        baseHasAlpha = s.hasAlpha
        basePreview = ImageOps.downscaleTo(s.bitmap, PREVIEW_SIDE)
        recompute()
        status = "Undone"
    }

    fun resetToOriginal() {
        val orig = originalFull ?: return
        if (busy) return
        undo.clear()
        undoDepth = 0
        adjust = Adjust()
        filter = Filters.ORIGINAL
        beforePreview = ImageOps.downscaleTo(orig, PREVIEW_SIDE)
        setBase(orig, alpha = false, push = false)
        status = "Back to original"
    }

    // ------------------------------------------------------------ save

    fun saveToGallery(onDone: (Uri?) -> Unit) {
        val src = base ?: return
        if (busy) return
        viewModelScope.launch(Dispatchers.Default) {
            busy = true
            progress = -1f
            status = "Saving..."
            val uri = try {
                val final = ImageOps.applyAdjust(src, adjust, filter)
                ImageOps.saveToGallery(getApplication(), final, asPng = baseHasAlpha)
            } catch (t: Throwable) {
                null
            }
            busy = false
            status = if (uri != null) "Saved to Pictures/AIEnhancer" else "Save failed"
            withContext(Dispatchers.Main) { onDone(uri) }
        }
    }
}
