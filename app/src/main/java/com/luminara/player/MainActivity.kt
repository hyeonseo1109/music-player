package com.luminara.player

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.luminara.player.floating.FloatingLyricsService
import com.luminara.player.ui.LuminaraApp
import com.luminara.player.ui.LuminaraTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        viewModel.player.connect()
        setContent {
            val ui by viewModel.uiState.collectAsStateWithLifecycle()
            val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                uri?.let {
                    contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    viewModel.addTree(it.toString())
                }
            }
            val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
            LuminaraTheme(ui.settings.theme) {
                LuminaraApp(
                    viewModel = viewModel,
                    requestMediaPermission = {
                        val permissions = buildList {
                            add(if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE)
                            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        permission.launch(permissions.toTypedArray())
                    },
                    requestOverlay = {
                        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                    },
                    chooseTree = { treePicker.launch(null) },
                    onFloatingChanged = { enabled ->
                        viewModel.setFloating(enabled)
                        if (enabled && Settings.canDrawOverlays(this)) startService(Intent(this, FloatingLyricsService::class.java))
                        else stopService(Intent(this, FloatingLyricsService::class.java))
                    },
                )
            }
        }
    }
    override fun onStart() {
        super.onStart(); viewModel.player.connect()
        stopService(Intent(this, FloatingLyricsService::class.java))
    }
    override fun onStop() {
        lifecycleScope.launch {
            val settings = (application as LuminaraApplication).container.preferences.settings.first()
            if (settings.floatingLyrics && Settings.canDrawOverlays(this@MainActivity)) startService(Intent(this@MainActivity, FloatingLyricsService::class.java))
        }
        super.onStop()
    }
}
