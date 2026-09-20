@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.hendo.hendomusic.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.hendo.hendomusic.MainViewModel
import com.hendo.hendomusic.data.TrackEntity
import com.hendo.hendomusic.library.AudioEditMode
import com.hendo.hendomusic.library.AudioEditState
import kotlinx.coroutines.delay
import kotlin.math.roundToLong

@Composable
fun AudioTrimScreen(track: TrackEntity, viewModel: MainViewModel, back: () -> Unit) {
    val context = LocalContext.current
    val editState by viewModel.audioEdit.collectAsStateWithLifecycle()
    val durationMs = track.durationMs.coerceAtLeast(1L)
    var selection by remember(track.id) { mutableStateOf(0f..durationMs.toFloat()) }
    var mode by remember(track.id) { mutableStateOf(AudioEditMode.KEEP_SELECTION) }
    var previewPlaying by remember(track.id) { mutableStateOf(false) }
    var previewPosition by remember(track.id) { mutableLongStateOf(selection.start.roundToLong()) }
    val preview = remember(track.id) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(track.uri))
            prepare()
        }
    }
    LaunchedEffect(track.id) {
        // The editor owns preview audio. Pause the app player to prevent two players sounding.
        viewModel.player.pause()
    }
    DisposableEffect(preview) { onDispose { preview.release() } }
    LaunchedEffect(previewPlaying, selection) {
        while (previewPlaying) {
            previewPosition = preview.currentPosition
            if (previewPosition >= selection.endInclusive.roundToLong()) {
                preview.pause()
                previewPlaying = false
                previewPosition = selection.endInclusive.roundToLong()
            }
            delay(80)
        }
    }

    Scaffold(
        topBar = { TopAppBar(
            title = { Text("음원 자르기") },
            navigationIcon = { IconButton(back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
        ) },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.background) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 8.dp)) {
                    Button(
                        onClick = { viewModel.editAudio(track, selection.start.roundToLong(), selection.endInclusive.roundToLong(), mode) },
                        enabled = editState !is AudioEditState.Saving && selection.endInclusive - selection.start >= 100f,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(
                        if (editState is AudioEditState.Saving) "저장 중…"
                        else if (mode == AudioEditMode.KEEP_SELECTION) "선택 구간만 새 파일로 만들기"
                        else "선택 구간을 삭제한 새 파일 만들기",
                    ) }
                    TextButton(back, Modifier.fillMaxWidth()) { Text("취소") }
                }
            }
        },
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text(track.title, style = MaterialTheme.typography.titleLarge)
            Text(track.artist, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .5f), shape = MaterialTheme.shapes.large) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("전체 ${formatEditTime(durationMs)}")
                    RangeSlider(
                        value = selection,
                        onValueChange = { range ->
                            val start = range.start.coerceIn(0f, durationMs.toFloat())
                            val end = range.endInclusive.coerceIn(start, durationMs.toFloat())
                            selection = start..end
                            preview.pause(); previewPlaying = false
                            preview.seekTo(start.roundToLong()); previewPosition = start.roundToLong()
                        },
                        valueRange = 0f..durationMs.toFloat(),
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("시작 ${formatEditTime(selection.start.roundToLong())}")
                        Text("끝 ${formatEditTime(selection.endInclusive.roundToLong())}")
                    }
                    Text("선택 구간 ${formatEditTime((selection.endInclusive - selection.start).roundToLong())}")
                    Text("미리듣기 ${formatEditTime(previewPosition)}", color = MaterialTheme.colorScheme.primary)
                    Slider(
                        value = previewPosition.toFloat().coerceIn(selection.start, selection.endInclusive),
                        onValueChange = { value ->
                            preview.pause(); previewPlaying = false
                            previewPosition = value.roundToLong()
                            preview.seekTo(previewPosition)
                        },
                        valueRange = selection.start..selection.endInclusive.coerceAtLeast(selection.start + 1f),
                    )
                    FilledTonalButton(onClick = {
                        viewModel.player.pause()
                        if (previewPlaying) {
                            preview.pause(); previewPlaying = false
                        } else {
                            if (preview.currentPosition !in selection.start.roundToLong() until selection.endInclusive.roundToLong()) {
                                preview.seekTo(selection.start.roundToLong())
                            }
                            preview.play(); previewPlaying = true
                        }
                    }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Icon(if (previewPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null)
                        Spacer(Modifier.width(6.dp)); Text(if (previewPlaying) "일시정지" else "선택 구간 미리듣기")
                    }
                }
            }
            Text("편집 방식", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilterChip(mode == AudioEditMode.KEEP_SELECTION, { mode = AudioEditMode.KEEP_SELECTION }, { Text("선택 구간 유지") })
                FilterChip(mode == AudioEditMode.REMOVE_SELECTION, { mode = AudioEditMode.REMOVE_SELECTION }, { Text("선택 구간 삭제") })
            }
            Text(
                if (mode == AudioEditMode.KEEP_SELECTION) "시작점과 종료점 사이만 새 파일로 저장합니다."
                else "선택한 구간을 제외하고 앞뒤 구간을 이어 새 파일로 저장합니다.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("원본 파일은 변경하지 않습니다. 결과는 새 M4A 파일로 생성됩니다.", style = MaterialTheme.typography.bodySmall)
        }
    }
    when (val state = editState) {
        is AudioEditState.Success -> AlertDialog(
            onDismissRequest = {}, title = { Text("편집 완료") },
            text = { Text("${state.result.displayName}\n${formatEditTime(state.result.durationMs)} 길이의 새 파일을 만들었습니다.") },
            confirmButton = { TextButton({ viewModel.resetAudioEdit(); back() }) { Text("확인") } },
        )
        is AudioEditState.Error -> AlertDialog(
            onDismissRequest = viewModel::resetAudioEdit, title = { Text("저장 실패") }, text = { Text(state.message) },
            confirmButton = { TextButton(viewModel::resetAudioEdit) { Text("확인") } },
        )
        else -> Unit
    }
}

private fun formatEditTime(ms: Long): String {
    val seconds = (ms / 1_000).coerceAtLeast(0)
    return "%d:%02d.%d".format(seconds / 60, seconds % 60, (ms % 1_000) / 100)
}
