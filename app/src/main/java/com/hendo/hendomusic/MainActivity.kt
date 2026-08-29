package com.hendo.hendomusic

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.provider.MediaStore
import android.app.Activity
import android.app.RecoverableSecurityException
import androidx.activity.result.IntentSenderRequest
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
import com.hendo.hendomusic.floating.FloatingLyricsService
import com.hendo.hendomusic.data.TrackEntity
import com.hendo.hendomusic.library.MetadataUpdate
import com.hendo.hendomusic.ui.LuminaraApp
import com.hendo.hendomusic.ui.LuminaraTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private data class PendingMetadata(val track: TrackEntity, val update: MetadataUpdate, val done: (String) -> Unit)
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
            DisposableEffect(ui.settings.keepScreenOn) {
                if (ui.settings.keepScreenOn) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                onDispose { window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            }
            var pendingDelete by remember { mutableStateOf<TrackEntity?>(null) }
            var pendingMetadata by remember { mutableStateOf<PendingMetadata?>(null) }
            val deleteApproval = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
                val track = pendingDelete
                if (result.resultCode == Activity.RESULT_OK && track != null) {
                    if (Build.VERSION.SDK_INT == 29) runCatching { contentResolver.delete(Uri.parse(track.uri), null, null) }
                    viewModel.completeApprovedDelete(track.id)
                }
                pendingDelete = null
            }
            val writeApproval = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
                val pending = pendingMetadata
                if (pending != null) {
                    if (result.resultCode == Activity.RESULT_OK) viewModel.updateMetadata(
                        pending.track, pending.update.title, pending.update.artist, pending.update.album, pending.update.albumArtist, pending.done,
                    ) else pending.done("사용자가 파일 변경 승인을 취소했습니다")
                }
                pendingMetadata = null
            }
            val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                uri?.let {
                    contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    viewModel.addTree(it.toString())
                }
            }
            val lrcPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                uri?.let(viewModel::importLrcUri)
            }
            val playlistPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                uri?.let(viewModel::importPlaylistUri)
            }
            var pendingPlaylistExport by remember { mutableStateOf<String?>(null) }
            val playlistExporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/x-mpegurl")) { uri ->
                pendingPlaylistExport?.let { contents -> uri?.let { target -> contentResolver.openOutputStream(target)?.bufferedWriter(Charsets.UTF_8)?.use { it.write(contents) } } }
                pendingPlaylistExport = null
            }
            val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
            var onboardingPermissionResult by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }
            val onboardingPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                onboardingPermissionResult?.invoke(granted)
                onboardingPermissionResult = null
            }
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
                    requestOnboardingPermission = { requestedPermission, result ->
                        onboardingPermissionResult = result
                        onboardingPermission.launch(requestedPermission)
                    },
                    requestOverlay = {
                        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                    },
                    chooseTree = { treePicker.launch(null) },
                    chooseLrc = { lrcPicker.launch(arrayOf("text/plain", "text/*", "application/octet-stream")) },
                    choosePlaylist = { playlistPicker.launch(arrayOf("audio/x-mpegurl", "application/x-mpegurl", "audio/mpegurl", "text/plain", "application/octet-stream")) },
                    exportPlaylist = { album -> viewModel.exportAlbumM3u(album.id) { name, contents -> pendingPlaylistExport = contents; playlistExporter.launch(name) } },
                    onFloatingChanged = { enabled ->
                        viewModel.setFloating(enabled)
                        if (enabled && Settings.canDrawOverlays(this)) startService(Intent(this, FloatingLyricsService::class.java))
                        else stopService(Intent(this, FloatingLyricsService::class.java))
                    },
                    requestDelete = { track ->
                        pendingDelete = track
                        if (Build.VERSION.SDK_INT >= 30) {
                            val request = MediaStore.createDeleteRequest(contentResolver, listOf(Uri.parse(track.uri)))
                            deleteApproval.launch(IntentSenderRequest.Builder(request.intentSender).build())
                        } else {
                            try {
                                check(contentResolver.delete(Uri.parse(track.uri), null, null) > 0) { "파일을 삭제하지 못했습니다." }
                                viewModel.completeApprovedDelete(track.id); pendingDelete = null
                            } catch (security: RecoverableSecurityException) {
                                deleteApproval.launch(IntentSenderRequest.Builder(security.userAction.actionIntent.intentSender).build())
                            }
                        }
                    },
                    requestMetadataWrite = { track, update, done ->
                        if (track.mediaStoreId == null) {
                            done("이 SAF 문서 공급자는 표준 음악 태그 쓰기를 지원하지 않습니다")
                        } else if (Build.VERSION.SDK_INT >= 30) {
                            pendingMetadata = PendingMetadata(track, update, done)
                            val request = MediaStore.createWriteRequest(contentResolver, listOf(Uri.parse(track.uri)))
                            writeApproval.launch(IntentSenderRequest.Builder(request.intentSender).build())
                        } else {
                            viewModel.updateMetadata(track, update.title, update.artist, update.album, update.albumArtist, done)
                        }
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
