package com.umar.aienhancer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceContour
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** All values 0..1. */
data class FaceParams(
    val smooth: Float = 0.6f,   // skin smoothing (keeps edges)
    val glow: Float = 0.25f,    // soft skin brightening
    val eyes: Float = 0.4f,     // brighter, cleaner eyes
    val teeth: Float = 0.4f     // whiter teeth
)

/** One detected face, all coordinates in pixels of the bitmap that was passed to [FaceRetouch.detect]. */
class DetectedFace(val box: Rect, val contours: Map<Int, List<PointF>>)

object FaceRetouch {

    // ------------------------------------------------------------ detection

    suspend fun detect(src: Bitmap): List<DetectedFace> {
        val longest = max(src.width, src.height)
        val k = if (longest > 1280) 1280f / longest else 1f
        val small = if (k < 1f) {
            Bitmap.createScaledBitmap(
                src,
                max(1, (src.width * k).roundToInt()),
                max(1, (src.height * k).roundToInt()),
                true
            )
        } else src

        val detector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
                .setMinFaceSize(0.08f)
                .build()
        )
        try {
            val faces: List<Face> = suspendCancellableCoroutine { cont ->
                detector.process(InputImage.fromBitmap(small, 0))
                    .addOnSuccessListener { cont.resume(it) }
                    .addOnFailureListener { cont.resumeWithException(it) }
            }
            val inv = 1f / k
            val wanted = intArrayOf(
                FaceContour.FACE,
                FaceContour.LEFT_EYE, FaceContour.RIGHT_EYE,
                FaceContour.LEFT_EYEBROW_TOP, FaceContour.LEFT_EYEBROW_BOTTOM,
                FaceContour.RIGHT_EYEBROW_TOP, FaceContour.RIGHT_EYEBROW_BOTTOM,
                FaceContour.UPPER_LIP_TOP, FaceContour.UPPER_LIP_BOTTOM,
                FaceContour.LOWER_LIP_TOP, FaceContour.LOWER_LIP_BOTTOM
            )
            return faces.map { f ->
                val map = HashMap<Int, List<PointF>>()
                for (type in wanted) {
                    f.getContour(type)?.points?.let { pts ->
                        map[type] = pts.map { PointF(it.x * inv, it.y * inv) }
                    }
                }
                val b = f.boundingBox
                DetectedFace(
                    Rect(
                        (b.left * inv).toInt(), (b.top * inv).toInt(),
                        (b.right * inv).toInt(), (b.bottom * inv).toInt()
                    ),
                    map
                )
            }
        } finally {
            detector.close()
        }
    }

    // ------------------------------------------------------------ retouch

    /** Returns a new bitmap with every face retouched. [src] is not modified. */
    fun apply(src: Bitmap, faces: List<DetectedFace>, p: FaceParams): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        for (f in faces) retouchOne(out, f, p)
        return out
    }

    private fun retouchOne(img: Bitmap, f: DetectedFace, p: FaceParams) {
        val pad = (0.12f * max(f.box.width(), f.box.height())).toInt()
        val r = Rect(f.box.left - pad, f.box.top - pad, f.box.right + pad, f.box.bottom + pad)
        if (!r.intersect(0, 0, img.width, img.height)) return
        val rw = r.width()
        val rh = r.height()
        if (rw < 32 || rh < 32) return
        val dx = r.left.toFloat()
        val dy = r.top.toFloat()

        val px = IntArray(rw * rh)
        img.getPixels(px, 0, rw, r.left, r.top, rw, rh)

        fun pts(vararg types: Int): List<PointF> = types.flatMap { f.contours[it].orEmpty() }

        val leftEye = pts(FaceContour.LEFT_EYE)
        val rightEye = pts(FaceContour.RIGHT_EYE)
        val leftBrow = pts(FaceContour.LEFT_EYEBROW_TOP, FaceContour.LEFT_EYEBROW_BOTTOM)
        val rightBrow = pts(FaceContour.RIGHT_EYEBROW_TOP, FaceContour.RIGHT_EYEBROW_BOTTOM)
        val lips = pts(
            FaceContour.UPPER_LIP_TOP, FaceContour.UPPER_LIP_BOTTOM,
            FaceContour.LOWER_LIP_TOP, FaceContour.LOWER_LIP_BOTTOM
        )
        val mouthInside = pts(FaceContour.UPPER_LIP_BOTTOM, FaceContour.LOWER_LIP_TOP)
        val faceOutline = f.contours[FaceContour.FACE].orEmpty()

        // ---- masks (1 = apply effect)
        val skinMask = if (faceOutline.size >= 3) {
            mask(rw, rh, max(2, rw / 45)) { c ->
                c.drawPath(path(hull(faceOutline), dx, dy), WHITE)
                val cut = Paint(BLACK).apply {
                    style = Paint.Style.FILL_AND_STROKE
                    strokeWidth = rw * 0.012f
                }
                for (hole in listOf(leftEye, rightEye, leftBrow, rightBrow, lips)) {
                    if (hole.size >= 3) c.drawPath(path(hull(hole), dx, dy), cut)
                }
            }
        } else null
        val eyeMask = if (leftEye.size >= 3 || rightEye.size >= 3) {
            mask(rw, rh, max(2, rw / 120)) { c ->
                for (e in listOf(leftEye, rightEye)) if (e.size >= 3) c.drawPath(path(hull(e), dx, dy), WHITE)
            }
        } else null
        val teethMask = if (mouthInside.size >= 6) {
            mask(rw, rh, max(2, rw / 150)) { c ->
                c.drawPath(path(hull(mouthInside), dx, dy), WHITE)
            }
        } else null

        // ---- a blurred copy for smoothing
        val low = if (skinMask != null && p.smooth > 0.01f) {
            val tmp = Bitmap.createBitmap(rw, rh, Bitmap.Config.ARGB_8888)
            tmp.setPixels(px, 0, rw, 0, 0, rw, rh)
            val blurred = blur(tmp, max(2, rw / 70))
            val a = IntArray(rw * rh)
            blurred.getPixels(a, 0, rw, 0, 0, rw, rh)
            a
        } else null

        val edgeThreshold = 28f
        for (i in px.indices) {
            val c = px[i]
            var rr = ((c shr 16) and 0xFF).toFloat()
            var gg = ((c shr 8) and 0xFF).toFloat()
            var bb = (c and 0xFF).toFloat()

            val ms = skinMask?.get(i) ?: 0f
            if (ms > 0.003f) {
                if (low != null) {
                    val l = low[i]
                    val lr = ((l shr 16) and 0xFF).toFloat()
                    val lg = ((l shr 8) and 0xFF).toFloat()
                    val lb = (l and 0xFF).toFloat()
                    val dl = abs(0.299f * (rr - lr) + 0.587f * (gg - lg) + 0.114f * (bb - lb))
                    val edge = (1f - dl / edgeThreshold).coerceIn(0f, 1f)
                    val a = ms * p.smooth * 0.85f * edge
                    rr += a * (lr - rr)
                    gg += a * (lg - gg)
                    bb += a * (lb - bb)
                }
                val k = ms * p.glow * 0.18f
                rr += k * (255f - rr)
                gg += k * (255f - gg)
                bb += k * (255f - bb)
            }

            val me = eyeMask?.get(i) ?: 0f
            if (me > 0.003f && p.eyes > 0f) {
                val k = me * p.eyes * 0.35f
                rr += k * (255f - rr)
                gg += k * (255f - gg)
                bb += k * (255f - bb)
            }

            val mt = teethMask?.get(i) ?: 0f
            if (mt > 0.003f && p.teeth > 0f) {
                val lum = 0.299f * rr + 0.587f * gg + 0.114f * bb
                val target = lum + (255f - lum) * 0.45f   // lighter and neutral (less yellow)
                val k = mt * p.teeth
                rr += k * (target - rr)
                gg += k * (target - gg)
                bb += k * (target - bb)
            }

            px[i] = (c and 0xFF000000.toInt()) or
                (rr.roundToInt().coerceIn(0, 255) shl 16) or
                (gg.roundToInt().coerceIn(0, 255) shl 8) or
                bb.roundToInt().coerceIn(0, 255)
        }
        img.setPixels(px, 0, rw, r.left, r.top, rw, rh)
    }

    // ------------------------------------------------------------ helpers

    private val WHITE = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
    private val BLACK = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; style = Paint.Style.FILL }

    private fun path(pts: List<PointF>, dx: Float, dy: Float): Path = Path().apply {
        pts.forEachIndexed { i, p ->
            if (i == 0) moveTo(p.x - dx, p.y - dy) else lineTo(p.x - dx, p.y - dy)
        }
        close()
    }

    /** Convex hull (monotone chain). Contour point order differs between landmarks, a hull is always a clean outline. */
    private fun hull(points: List<PointF>): List<PointF> {
        if (points.size < 3) return points
        val s = points.sortedWith(compareBy({ it.x }, { it.y }))
        fun cross(o: PointF, a: PointF, b: PointF) = (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)
        val lower = ArrayList<PointF>()
        for (p in s) {
            while (lower.size >= 2 && cross(lower[lower.size - 2], lower[lower.size - 1], p) <= 0f) lower.removeAt(lower.size - 1)
            lower.add(p)
        }
        val upper = ArrayList<PointF>()
        for (p in s.asReversed()) {
            while (upper.size >= 2 && cross(upper[upper.size - 2], upper[upper.size - 1], p) <= 0f) upper.removeAt(upper.size - 1)
            upper.add(p)
        }
        lower.removeAt(lower.size - 1)
        upper.removeAt(upper.size - 1)
        return lower + upper
    }

    /** Draws a shape on black and returns a feathered 0..1 mask. */
    private fun mask(w: Int, h: Int, feather: Int, draw: (Canvas) -> Unit): FloatArray {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.BLACK)
        draw(c)
        val soft = blur(bmp, feather)
        val a = IntArray(w * h)
        soft.getPixels(a, 0, w, 0, 0, w, h)
        return FloatArray(w * h) { ((a[it] shr 8) and 0xFF) / 255f }
    }

    /** Cheap blur: shrink by [d], then enlarge again with bilinear filtering. */
    private fun blur(src: Bitmap, d: Int): Bitmap {
        val w = src.width
        val h = src.height
        val down = Bitmap.createScaledBitmap(src, max(1, w / d), max(1, h / d), true)
        return Bitmap.createScaledBitmap(down, w, h, true)
    }
}
