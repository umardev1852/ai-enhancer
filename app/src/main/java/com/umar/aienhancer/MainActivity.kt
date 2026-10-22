package com.umar.aienhancer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppTheme {
                val photoVm: EditorViewModel = viewModel()
                val videoVm: VideoViewModel = viewModel()
                var mode by rememberSaveable { mutableIntStateOf(0) }
                if (mode == 0) {
                    EditorScreen(photoVm, onOpenVideo = { mode = 1 })
                } else {
                    VideoScreen(videoVm, onBack = { mode = 0 })
                }
            }
        }
    }
}
