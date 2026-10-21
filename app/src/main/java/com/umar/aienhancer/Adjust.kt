package com.umar.aienhancer

import android.graphics.ColorMatrix

/** Non-destructive adjustments, applied live on the preview and again at full size on save. */
data class Adjust(
    val brightness: Float = 0f,   // -100 .. 100
    val contrast: Float = 1f,     // 0.5 .. 1.5
    val saturation: Float = 1f,   // 0 .. 2
    val warmth: Float = 0f,       // -1 .. 1
    val sharpness: Float = 0f,    // 0 .. 2
    val levelLow: Float = 0f,     // 0 .. 255 (auto-enhance sets these)
    val levelHigh: Float = 255f   // 0 .. 255
) {
    val isDefault: Boolean
        get() = this == Adjust()
}

private fun scaleOffset(r: Float, g: Float, b: Float, offset: Float = 0f) = ColorMatrix(
    floatArrayOf(
        r, 0f, 0f, 0f, offset,
        0f, g, 0f, 0f, offset,
        0f, 0f, b, 0f, offset,
        0f, 0f, 0f, 1f, 0f
    )
)

private fun contrastMatrix(c: Float, brightness: Float = 0f): ColorMatrix =
    scaleOffset(c, c, c, (-0.5f * c + 0.5f) * 255f + brightness)

private fun saturationMatrix(s: Float): ColorMatrix = ColorMatrix().apply { setSaturation(s) }

private fun combine(vararg steps: ColorMatrix): ColorMatrix {
    val m = ColorMatrix()
    steps.forEach { m.postConcat(it) }
    return m
}

enum class Filters(val label: String, val build: (() -> ColorMatrix)?) {
    ORIGINAL("Original", null),
    VIVID("Vivid", { combine(saturationMatrix(1.35f), contrastMatrix(1.08f)) }),
    WARM("Warm", { scaleOffset(1.1f, 1.0f, 0.88f) }),
    COOL("Cool", { scaleOffset(0.9f, 1.0f, 1.12f) }),
    FADE("Fade", { combine(saturationMatrix(0.8f), contrastMatrix(0.85f, 18f)) }),
    DRAMATIC("Dramatic", { combine(saturationMatrix(0.9f), contrastMatrix(1.3f, -10f)) }),
    SEPIA(
        "Sepia",
        {
            ColorMatrix(
                floatArrayOf(
                    0.393f, 0.769f, 0.189f, 0f, 0f,
                    0.349f, 0.686f, 0.168f, 0f, 0f,
                    0.272f, 0.534f, 0.131f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        }
    ),
    BW("B&W", { saturationMatrix(0f) }),
    NOIR("Noir", { combine(saturationMatrix(0f), contrastMatrix(1.4f, -8f)) })
}

fun buildColorMatrix(a: Adjust, f: Filters): ColorMatrix {
    val levelScale = 255f / (a.levelHigh - a.levelLow).coerceAtLeast(1f)
    val levels = scaleOffset(levelScale, levelScale, levelScale, -a.levelLow * levelScale)
    val tone = contrastMatrix(a.contrast, a.brightness)
    val sat = saturationMatrix(a.saturation)
    val warm = scaleOffset(1f + 0.15f * a.warmth, 1f, 1f - 0.15f * a.warmth)
    val m = combine(levels, tone, sat, warm)
    f.build?.invoke()?.let { m.postConcat(it) }
    return m
}
