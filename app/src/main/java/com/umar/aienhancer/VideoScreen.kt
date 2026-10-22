package com.umar.aienhancer

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun VideoScreen(vm: VideoViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current

    // keep the screen awake while a video is being processed
    DisposableEffect(vm.busy) {
        view.keepScreenOn = vm.busy
        onDispose { view.keepScreenOn = false }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.pick(uri)
    }
    val hasVideo = vm.info != null

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(Modifier.fillMaxSize()) {

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Video Enhancer",
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 20.sp,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onBack, enabled = !vm.busy) { Text("Photo") }
            }

            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
            ) {
                Button(
                    onClick = {
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
                    },
                    enabled = !vm.busy,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (hasVideo) "Choose another video" else "Choose video") }

                vm.info?.let { i ->
                    Text(
                        "Source: ${i.width} x ${i.height}  |  ${i.durationMs / 1000} s  |  ${"%.0f".format(i.fps)} fps" +
                            if (i.hasAudio) "  |  audio" else "",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                vm.summary()?.let {
                    Text(it, color = MaterialTheme.colorScheme.primary, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
                }

                SectionTitle("Model")
                ChipRow {
                    UpscaleModel.entries.forEach { m ->
                        FilterChip(selected = vm.model == m, onClick = { vm.model = m }, enabled = !vm.busy, label = { Text(m.label) })
                    }
                }
                Text(
                    if (vm.model == UpscaleModel.FAST) "Fast model is recommended for video."
                    else "Photo model gives more detail but is several times slower.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )

                SectionTitle("Work on frames up to (px, longest side)")
                ChipRow {
                    listOf(360, 480, 720).forEach { s ->
                        FilterChip(selected = vm.maxInput == s, onClick = { vm.maxInput = s }, enabled = !vm.busy, label = { Text("$s") })
                    }
                }

                SectionTitle("Output scale")
                ChipRow {
                    listOf(2, 4).forEach { s ->
                        FilterChip(selected = vm.outScale == s, onClick = { vm.outScale = s }, enabled = !vm.busy, label = { Text("${s}x") })
                    }
                }

                SectionTitle("Only enhance the first ... seconds")
                ChipRow {
                    listOf(5, 10, 20, 30, 60).forEach { s ->
                        FilterChip(selected = vm.maxSeconds == s, onClick = { vm.maxSeconds = s }, enabled = !vm.busy, label = { Text("$s s") })
                    }
                }

                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Use GPU (Vulkan) - turn off if it fails",
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                        fontSize = 14.sp
                    )
                    Switch(checked = vm.useGpu, onCheckedChange = { vm.useGpu = it }, enabled = !vm.busy)
                }

                Text(
                    "Every frame goes through the AI, so this takes a while. Keep the app open and the screen on until it finishes. Start with a 5 second clip to see how fast your phone is.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            Column(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                vm.status?.let {
                    Text(it, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
                }
                if (vm.busy) {
                    LinearProgressIndicator(progress = { vm.progress }, modifier = Modifier.fillMaxWidth())
                    if (vm.eta.isNotEmpty()) {
                        Text(vm.eta, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                    OutlinedButton(onClick = { vm.cancel() }, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
                } else {
                    Button(
                        onClick = { vm.start() },
                        enabled = hasVideo,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Enhance video") }

                    vm.resultUri?.let { result ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    val view2 = Intent(Intent.ACTION_VIEW).apply {
                                        setDataAndType(result, "video/mp4")
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(view2)
                                },
                                modifier = Modifier.weight(1f)
                            ) { Text("Play") }
                            OutlinedButton(
                                onClick = {
                                    val send = Intent(Intent.ACTION_SEND).apply {
                                        type = "video/mp4"
                                        putExtra(Intent.EXTRA_STREAM, result)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(Intent.createChooser(send, "Share"))
                                },
                                modifier = Modifier.weight(1f)
                            ) { Text("Share") }
                        }
                    }
                }
            }
        }
    }
}
