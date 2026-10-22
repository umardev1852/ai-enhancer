package com.umar.aienhancer

import android.content.Intent
import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import kotlin.math.min
import kotlin.math.roundToInt

private val Scheme = darkColorScheme(
    primary = Color(0xFF8AB4FF),
    onPrimary = Color(0xFF00214D),
    secondary = Color(0xFFB39DFF),
    background = Color(0xFF0E1014),
    onBackground = Color(0xFFE6E8EE),
    surface = Color(0xFF171A21),
    onSurface = Color(0xFFE6E8EE),
    surfaceVariant = Color(0xFF222733),
    onSurfaceVariant = Color(0xFFB8BECC)
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}

@Composable
fun EditorScreen(vm: EditorViewModel, onOpenVideo: () -> Unit) {
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.load(uri)
    }
    val openPicker = {
        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val hasImage = vm.base != null

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(Modifier.fillMaxSize()) {

            // ---------------- top bar
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "AI Enhancer",
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 20.sp,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onOpenVideo, enabled = !vm.busy) { Text("Video") }
                TextButton(onClick = { openPicker() }, enabled = !vm.busy) { Text("Open") }
                TextButton(
                    onClick = {
                        vm.saveToGallery { uri ->
                            Toast.makeText(
                                context,
                                if (uri != null) "Saved to Pictures/AIEnhancer" else "Save failed",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    },
                    enabled = hasImage && !vm.busy
                ) { Text("Save") }
                TextButton(
                    onClick = {
                        vm.saveToGallery { uri ->
                            if (uri != null) {
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    type = context.contentResolver.getType(uri) ?: "image/*"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(send, "Share"))
                            } else {
                                Toast.makeText(context, "Save failed", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    enabled = hasImage && !vm.busy
                ) { Text("Share") }
            }

            // ---------------- image area
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                val before = vm.beforePreview
                val after = vm.afterPreview
                if (before != null && after != null) {
                    CompareView(before, after, Modifier.fillMaxSize())
                    Row(
                        Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Label("Original  <")
                        Label(">  Enhanced")
                    }
                } else if (!vm.busy) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "Pick a photo to make it HD",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Button(onClick = { openPicker() }, modifier = Modifier.padding(top = 12.dp)) {
                            Text("Choose photo")
                        }
                    }
                }
            }

            // ---------------- status / progress
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                val size = vm.base?.let { "${it.width} x ${it.height}" }
                val line = listOfNotNull(vm.status, size?.let { "Size: $it" }).joinToString("   |   ")
                if (line.isNotEmpty()) {
                    Text(line, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
                if (vm.busy) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (vm.progress >= 0f) {
                            LinearProgressIndicator(
                                progress = { vm.progress },
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(vertical = 6.dp)
                            )
                        } else {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(vertical = 6.dp)
                            )
                        }
                        TextButton(onClick = { vm.cancelWork() }) { Text("Cancel") }
                    }
                }
            }

            // ---------------- bottom panel
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(top = 8.dp)
            ) {
                ChipRow {
                    listOf("AI HD", "Adjust", "Filters", "Face", "Tools").forEachIndexed { i, name ->
                        FilterChip(selected = tab == i, onClick = { tab = i }, label = { Text(name) })
                    }
                }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    when (tab) {
                        0 -> AiTab(vm, hasImage)
                        1 -> AdjustTab(vm, hasImage)
                        2 -> FiltersTab(vm, hasImage)
                        3 -> FaceTab(vm, hasImage)
                        else -> ToolsTab(vm, hasImage)
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ tabs

@Composable
private fun AiTab(vm: EditorViewModel, hasImage: Boolean) {
    SectionTitle("Model")
    ChipRow {
        UpscaleModel.entries.forEach { m ->
            FilterChip(selected = vm.model == m, onClick = { vm.model = m }, label = { Text(m.label) })
        }
    }
    Text(vm.model.hint, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)

    SectionTitle("Output scale")
    ChipRow {
        listOf(2, 4).forEach { s ->
            FilterChip(selected = vm.outScale == s, onClick = { vm.outScale = s }, label = { Text("${s}x") })
        }
    }

    SectionTitle("Max input size (px) - bigger = more detail but slower")
    ChipRow {
        listOf(512, 768, 1024, 1536).forEach { s ->
            FilterChip(selected = vm.maxInput == s, onClick = { vm.maxInput = s }, label = { Text("$s") })
        }
    }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "Use GPU (Vulkan) - turn off if the app crashes",
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
            fontSize = 14.sp
        )
        Switch(checked = vm.useGpu, onCheckedChange = { vm.useGpu = it })
    }

    Button(
        onClick = { vm.runUpscale() },
        enabled = hasImage && !vm.busy,
        modifier = Modifier.fillMaxWidth()
    ) { Text("Make HD") }
}

@Composable
private fun AdjustTab(vm: EditorViewModel, hasImage: Boolean) {
    val a = vm.adjust
    LabeledSlider("Brightness", a.brightness, -100f..100f, hasImage) { vm.updateAdjust(a.copy(brightness = it)) }
    LabeledSlider("Contrast", a.contrast, 0.5f..1.5f, hasImage) { vm.updateAdjust(a.copy(contrast = it)) }
    LabeledSlider("Saturation", a.saturation, 0f..2f, hasImage) { vm.updateAdjust(a.copy(saturation = it)) }
    LabeledSlider("Warmth", a.warmth, -1f..1f, hasImage) { vm.updateAdjust(a.copy(warmth = it)) }
    LabeledSlider("Sharpness", a.sharpness, 0f..2f, hasImage) { vm.updateAdjust(a.copy(sharpness = it)) }
    ChipRow {
        Button(onClick = { vm.autoEnhance() }, enabled = hasImage) { Text("Auto enhance") }
        OutlinedButton(onClick = { vm.resetAdjust() }, enabled = hasImage) { Text("Reset") }
    }
}

@Composable
private fun FiltersTab(vm: EditorViewModel, hasImage: Boolean) {
    SectionTitle("Look")
    ChipRow {
        Filters.entries.forEach { f ->
            FilterChip(
                selected = vm.filter == f,
                onClick = { vm.updateFilter(f) },
                enabled = hasImage,
                label = { Text(f.label) }
            )
        }
    }
}

@Composable
private fun FaceTab(vm: EditorViewModel, hasImage: Boolean) {
    val p = vm.faceParams
    LabeledSlider("Skin smooth", p.smooth, 0f..1f, hasImage) { vm.faceParams = p.copy(smooth = it) }
    LabeledSlider("Skin glow", p.glow, 0f..1f, hasImage) { vm.faceParams = p.copy(glow = it) }
    LabeledSlider("Eyes", p.eyes, 0f..1f, hasImage) { vm.faceParams = p.copy(eyes = it) }
    LabeledSlider("Teeth", p.teeth, 0f..1f, hasImage) { vm.faceParams = p.copy(teeth = it) }
    Button(
        onClick = { vm.retouchFaces() },
        enabled = hasImage && !vm.busy,
        modifier = Modifier.fillMaxWidth()
    ) { Text("Retouch faces") }
    Text(
        "Finds every face, smooths skin while keeping eyes, brows and lips sharp. Use Undo in Tools to go back.",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 12.sp,
        modifier = Modifier.padding(top = 6.dp)
    )
}

@Composable
private fun ToolsTab(vm: EditorViewModel, hasImage: Boolean) {
    SectionTitle("Tools")
    ChipRow {
        Button(onClick = { vm.removeBackground() }, enabled = hasImage && !vm.busy) { Text("Remove background") }
        OutlinedButton(onClick = { vm.rotate() }, enabled = hasImage && !vm.busy) { Text("Rotate") }
        OutlinedButton(onClick = { vm.flip() }, enabled = hasImage && !vm.busy) { Text("Flip") }
    }
    ChipRow {
        OutlinedButton(onClick = { vm.undo() }, enabled = vm.undoDepth > 0 && !vm.busy) {
            Text("Undo (${vm.undoDepth})")
        }
        OutlinedButton(onClick = { vm.resetToOriginal() }, enabled = hasImage && !vm.busy) {
            Text("Back to original")
        }
    }
    Text(
        "Remove background works best on photos of people. The result is saved as a transparent PNG.",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 12.sp,
        modifier = Modifier.padding(top = 6.dp)
    )
}

// ------------------------------------------------------------------ small pieces

@Composable
internal fun SectionTitle(text: String) {
    Text(
        text,
        color = MaterialTheme.colorScheme.primary,
        fontSize = 12.sp,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
    )
}

@Composable
internal fun ChipRow(content: @Composable () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) { content() }
}

@Composable
private fun LabeledSlider(
    name: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    onChange: (Float) -> Unit
) {
    Column {
        Text(name, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        Slider(value = value, onValueChange = onChange, valueRange = range, enabled = enabled)
    }
}

@Composable
private fun Label(text: String) {
    Text(
        text,
        color = Color.White,
        fontSize = 11.sp,
        modifier = Modifier
            .background(Color(0x99000000))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    )
}

private fun fitRect(imgW: Int, imgH: Int, boxW: Float, boxH: Float): Rect {
    if (imgW <= 0 || imgH <= 0 || boxW <= 0f || boxH <= 0f) return Rect(0f, 0f, 0f, 0f)
    val s = min(boxW / imgW, boxH / imgH)
    val w = imgW * s
    val h = imgH * s
    val left = (boxW - w) / 2f
    val top = (boxH - h) / 2f
    return Rect(left, top, left + w, top + h)
}

/** Before / after viewer. Drag (or tap) to move the divider. */
@Composable
private fun CompareView(before: Bitmap, after: Bitmap, modifier: Modifier = Modifier) {
    val beforeImg = remember(before) { before.asImageBitmap() }
    val afterImg = remember(after) { after.asImageBitmap() }
    var fraction by remember { mutableFloatStateOf(0.5f) }
    var box by remember { mutableStateOf(IntSize.Zero) }

    Canvas(
        modifier
            .onSizeChanged { box = it }
            .pointerInput(afterImg) {
                detectDragGestures { change, _ ->
                    change.consume()
                    val r = fitRect(afterImg.width, afterImg.height, size.width.toFloat(), size.height.toFloat())
                    if (r.width > 0f) fraction = ((change.position.x - r.left) / r.width).coerceIn(0f, 1f)
                }
            }
            .pointerInput(afterImg) {
                detectTapGestures { pos ->
                    val r = fitRect(afterImg.width, afterImg.height, size.width.toFloat(), size.height.toFloat())
                    if (r.width > 0f) fraction = ((pos.x - r.left) / r.width).coerceIn(0f, 1f)
                }
            }
    ) {
        val r = fitRect(afterImg.width, afterImg.height, size.width, size.height)
        if (r.width <= 0f) return@Canvas
        val offset = IntOffset(r.left.roundToInt(), r.top.roundToInt())
        val dstSize = IntSize(r.width.roundToInt(), r.height.roundToInt())

        drawImage(
            afterImg,
            dstOffset = offset,
            dstSize = dstSize,
            filterQuality = FilterQuality.High
        )
        val x = r.left + r.width * fraction
        clipRect(left = r.left, top = r.top, right = x, bottom = r.bottom) {
            drawImage(
                beforeImg,
                dstOffset = offset,
                dstSize = dstSize,
                filterQuality = FilterQuality.High
            )
        }
        drawLine(Color.White, Offset(x, r.top), Offset(x, r.bottom), strokeWidth = 3f)
        drawCircle(Color.White, radius = 18f, center = Offset(x, (r.top + r.bottom) / 2f))
        drawCircle(Color(0xFF0E1014), radius = 7f, center = Offset(x, (r.top + r.bottom) / 2f))
    }
}
