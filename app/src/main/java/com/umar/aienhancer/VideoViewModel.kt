package com.umar.aienhancer

import android.app.Application
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
import kotlinx.coroutines.launch
import java.io.File

class VideoViewModel(app: Application) : AndroidViewModel(app) {

    var uri by mutableStateOf<Uri?>(null); private set
    var info by mutableStateOf<VideoEnhancer.Info?>(null); private set
    var busy by mutableStateOf(false); private set
    var progress by mutableFloatStateOf(0f); private set
    var status by mutableStateOf<String?>(null); private set
    var eta by mutableStateOf(""); private set
    var resultUri by mutableStateOf<Uri?>(null); private set

    // settings
    var model by mutableStateOf(UpscaleModel.FAST)
    var maxInput by mutableIntStateOf(480)
    var outScale by mutableIntStateOf(2)
    var maxSeconds by mutableIntStateOf(10)
    var useGpu by mutableStateOf(true)

    @Volatile
    private var cancelled = false

    fun pick(newUri: Uri) {
        if (busy) return
        viewModelScope.launch(Dispatchers.Default) {
            busy = true
            status = "Reading video..."
            resultUri = null
            try {
                val i = VideoEnhancer(getApplication()).probe(newUri)
                uri = newUri
                info = i
                status = null
            } catch (t: Throwable) {
                uri = null
                info = null
                status = "Could not open this video: ${t.message}"
            } finally {
                busy = false
            }
        }
    }

    /** Short description of what the current settings will produce. */
    fun summary(): String? {
        val i = info ?: return null
        val (iw, ih) = VideoEnhancer.fitSize(i.width, i.height, maxInput)
        val frames = VideoEnhancer.frameCount(i, maxSeconds)
        val secs = frames / VideoEnhancer.processedFps(i.fps)
        return "Output ${iw * outScale} x ${ih * outScale}  |  ${"%.0f".format(secs)} s  |  $frames frames"
    }

    fun cancel() {
        cancelled = true
    }

    fun start() {
        val source = uri ?: return
        if (busy) return
        val opts = VideoEnhancer.Options(model, maxInput, outScale, maxSeconds, useGpu)
        viewModelScope.launch(Dispatchers.Default) {
            busy = true
            cancelled = false
            resultUri = null
            progress = 0f
            eta = ""
            status = "Starting..."
            val app = getApplication<Application>()
            val tmp = File(app.cacheDir, "enhanced_${System.currentTimeMillis()}.mp4")
            val t0 = SystemClock.elapsedRealtime()
            try {
                val ok = VideoEnhancer(app).run(
                    source, opts, tmp,
                    onProgress = { done, total ->
                        progress = if (total > 0) done.toFloat() / total else 0f
                        status = "Frame $done / $total"
                        if (done > 0) {
                            val perFrame = (SystemClock.elapsedRealtime() - t0) / done.toFloat()
                            val left = (perFrame * (total - done) / 1000f).toInt()
                            eta = "About ${left / 60} min ${left % 60} s left"
                        }
                    },
                    cancelled = { cancelled }
                )
                if (!ok) {
                    status = "Cancelled"
                } else {
                    status = "Saving..."
                    val saved = saveVideoToGallery(app, tmp)
                    resultUri = saved
                    status = if (saved != null) "Done - saved to Movies/AIEnhancer" else "Could not save the video"
                }
            } catch (t: Throwable) {
                status = "Failed: ${t.message ?: t.javaClass.simpleName}"
            } finally {
                tmp.delete()
                eta = ""
                busy = false
            }
        }
    }
}
