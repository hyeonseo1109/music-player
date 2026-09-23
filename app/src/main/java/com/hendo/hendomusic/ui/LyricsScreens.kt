@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.hendo.hendomusic.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.hendo.hendomusic.MainViewModel
import com.hendo.hendomusic.data.TrackEntity
import com.hendo.hendomusic.data.LyricsSource
import com.hendo.hendomusic.lyrics.LrcCodec
import com.hendo.hendomusic.lyrics.LyricsSearchResult
import com.hendo.hendomusic.lyrics.LyricsSearchState
import com.hendo.hendomusic.lyrics.SyncedLyricLine
import com.hendo.hendomusic.lyrics.buildSyncedLyrics
import com.hendo.hendomusic.lyrics.previousSyncedBlockStart
import com.hendo.hendomusic.network.CommunityActionState
import kotlinx.coroutines.delay

@Composable
fun LyricsSearchScreen(track: TrackEntity, viewModel: MainViewModel, back: () -> Unit, edit: () -> Unit) {
    val state by viewModel.lyricsSearch.collectAsStateWithLifecycle()
    var title by remember(track.id) { mutableStateOf(track.title) }
    var artist by remember(track.id) { mutableStateOf(track.artist) }
    var preview by remember { mutableStateOf<LyricsSearchResult?>(null) }
    var reportTarget by remember { mutableStateOf<LyricsSearchResult?>(null) }
    val community by viewModel.communityAction.collectAsStateWithLifecycle()
    LaunchedEffect(track.id) { viewModel.resetLyricsSearch(); viewModel.searchLyrics(title, artist) }
    Scaffold(topBar = { TopAppBar({ Text("가사 검색") }, navigationIcon = { IconButton(back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, scrolledContainerColor = Color.Transparent)) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text("곡 제목") }, singleLine = true)
            OutlinedTextField(artist, { artist = it }, Modifier.fillMaxWidth().padding(top = 6.dp), label = { Text("아티스트") }, singleLine = true)
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ viewModel.searchLyrics(title, artist) }, enabled = title.isNotBlank()) { Icon(Icons.Default.Search, null); Text("검색") }
                OutlinedButton({ viewModel.stageLyrics(null); edit() }) { Icon(Icons.Default.EditNote, null); Text("가사 직접 입력") }
            }
            when (val value = state) {
                LyricsSearchState.Idle -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("곡 정보가 자동 입력되었습니다. 검색을 누르세요.") }
                LyricsSearchState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                is LyricsSearchState.Error -> LyricsError(value.message) { viewModel.searchLyrics(title, artist) }
                is LyricsSearchState.Success -> if (value.results.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("가사 검색 결과가 없습니다"); OutlinedButton({ viewModel.searchLyrics(title, artist) }, Modifier.padding(top = 8.dp)) { Text("다시 시도") } } } else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(value.results, key = { "${it.source}:${it.id}" }) { result ->
                        Surface(Modifier.fillMaxWidth().purpleGlass(18).clickable { preview = result }, shape = RoundedCornerShape(18.dp), color = androidx.compose.ui.graphics.Color.Transparent) {
                            Column(Modifier.padding(14.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) { Text(result.source, Modifier.weight(1f), fontWeight = FontWeight.Bold); AssistChip({}, { Text(if (result.synced) "싱크 있음" else "싱크없음") }); if (result.votes > 0) Text("추천 ${result.votes}", Modifier.padding(start = 8.dp)) }
                                result.trackTitle?.let { title -> Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold) }
                                listOfNotNull(result.trackArtist, result.album, result.durationMs?.let(::formatDuration)).takeIf { it.isNotEmpty() }?.let { Text(it.joinToString(" · "), style = MaterialTheme.typography.labelMedium) }
                                Text(result.preview, maxLines = 4, style = MaterialTheme.typography.bodyMedium)
                                if (result.updatedAt.isNotBlank()) Text("업데이트 ${result.updatedAt.take(10)}", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
    }
    preview?.let { result -> AlertDialog(
        onDismissRequest = { preview = null }, title = { Text("전체 가사 확인") },
        text = { LazyColumn(Modifier.heightIn(max = 430.dp)) { item { Text(result.plainText) } } },
        confirmButton = { TextButton({ viewModel.stageLyrics(result); preview = null; edit() }) { Text("적용") } },
        dismissButton = { Row {
            if (result.source.startsWith("Luminara")) {
                TextButton({ viewModel.voteLyrics(result.id) }) { Text("추천") }
                TextButton({ preview = null; reportTarget = result }) { Text("신고") }
            }
            TextButton({ preview = null }) { Text("취소") }
        } },
    ) }
    reportTarget?.let { result ->
        val reasons = listOf("잘못된 가사", "잘못된 싱크", "다른 곡의 가사", "부적절한 내용", "기타")
        AlertDialog(onDismissRequest = { reportTarget = null }, title = { Text("신고 사유") }, text = { Column { reasons.forEach { reason -> TextButton({ viewModel.reportLyrics(result.id, reason); reportTarget = null }) { Text(reason) } } } }, confirmButton = {})
    }
    if (community !is CommunityActionState.Idle && community !is CommunityActionState.Loading) {
        val message = when (val value = community) { is CommunityActionState.Success -> value.message; is CommunityActionState.Error -> value.message; else -> "" }
        AlertDialog(onDismissRequest = viewModel::resetCommunityAction, title = { Text(if (community is CommunityActionState.Error) "처리 실패" else "완료") }, text = { Text(message) }, confirmButton = { TextButton(viewModel::resetCommunityAction) { Text("확인") } })
    }
}

private fun formatDuration(durationMs: Long): String = "%d:%02d".format(durationMs / 60_000, (durationMs / 1_000) % 60)

@Composable
fun LyricsEditorScreen(trackId: String, viewModel: MainViewModel, chooseLrc: () -> Unit, back: () -> Unit, openSync: () -> Unit) {
    val staged by viewModel.stagedLyrics.collectAsStateWithLifecycle()
    var text by remember(trackId) { mutableStateOf("") }
    var synced by remember(trackId) { mutableStateOf<List<SyncedLyricLine>>(emptyList()) }
    var originalText by remember(trackId) { mutableStateOf("") }
    var loaded by remember(trackId) { mutableStateOf(false) }
    var confirmBack by remember { mutableStateOf(false) }
    var syncChangedWarning by remember { mutableStateOf(false) }
    var removeSyncConfirm by remember { mutableStateOf(false) }
    var shareConfirm by remember { mutableStateOf(false) }
    var importedFromLrc by remember(trackId) { mutableStateOf(false) }
    val community by viewModel.communityAction.collectAsStateWithLifecycle()
    val importedLrc by viewModel.lrcImport.collectAsStateWithLifecycle()
    LaunchedEffect(trackId, staged) {
        if (!loaded) {
            if (staged != null) {
                text = staged!!.plainText
                synced = staged!!.syncedText?.let(LrcCodec::parse).orEmpty()
            } else {
                val existing = viewModel.lyrics(trackId); text = existing.first; synced = existing.second
            }
            originalText = text; loaded = true
        } else if (synced.isEmpty() && staged?.syncedText != null) {
            // Community line detail may arrive just after navigation; retain it in the local copy.
            synced = LrcCodec.parse(staged!!.syncedText!!)
        }
    }
    LaunchedEffect(importedLrc) {
        when (val value = importedLrc) {
            is com.hendo.hendomusic.LrcImportState.Success -> {
                text = value.plainText; synced = value.synced; importedFromLrc = true; viewModel.resetLrcImport()
            }
            else -> Unit
        }
    }
    val dirty = loaded && (text != originalText)
    fun leave() { if (dirty) confirmBack = true else { viewModel.stageLyrics(null); back() } }
    BackHandler(onBack = ::leave)
    Scaffold(topBar = { TopAppBar({ Text("가사 검토 및 편집") }, navigationIcon = { IconButton(::leave) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") } }, actions = {
        IconButton({ shareConfirm = true }, enabled = text.isNotBlank()) { Icon(Icons.Default.Share, "가사 공유") }
        TextButton({
        val structureChanged = synced.isNotEmpty() && text.lines().filter(String::isNotBlank) != originalText.lines().filter(String::isNotBlank)
        if (structureChanged) syncChangedWarning = true else {
            val source = when { importedFromLrc -> LyricsSource.USER_LRC; staged != null -> LyricsSource.USER_SEARCH; else -> LyricsSource.USER_MANUAL }
            viewModel.saveLyrics(trackId, text, synced, source); viewModel.stageLyrics(null); back()
        }
    }, enabled = text.isNotBlank()) { Text("저장") } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, scrolledContainerColor = Color.Transparent)) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (synced.isNotEmpty()) Text("싱크 ${synced.size}줄 유지 중", Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.primary)
            OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().weight(1f).padding(16.dp), placeholder = { Text("가사를 붙여넣거나 직접 입력하세요") })
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(chooseLrc) { Text("LRC 가져오기") }
                if (synced.isNotEmpty()) OutlinedButton({ removeSyncConfirm = true }) { Text("싱크 제거") }
                Button({ viewModel.stageLyrics(LyricsSearchResult("draft", text.take(180), "LOCAL", synced.isNotEmpty(), 0, "", text, synced.takeIf { it.isNotEmpty() }?.let(LrcCodec::encode))); openSync() }, enabled = text.isNotBlank()) { Text("싱크 편집") }
            }
        }
    }
    if (importedLrc is com.hendo.hendomusic.LrcImportState.Error) {
        val message = (importedLrc as com.hendo.hendomusic.LrcImportState.Error).message
        AlertDialog(onDismissRequest = viewModel::resetLrcImport, title = { Text("LRC 가져오기 실패") }, text = { Text(message) }, confirmButton = { TextButton(viewModel::resetLrcImport) { Text("확인") } })
    }
    if (confirmBack) AlertDialog({ confirmBack = false }, { TextButton({ viewModel.stageLyrics(null); confirmBack = false; back() }) { Text("나가기") } }, dismissButton = { TextButton({ confirmBack = false }) { Text("계속 편집") } }, title = { Text("변경사항을 저장하지 않고 나가시겠습니까?") })
    if (syncChangedWarning) AlertDialog(
        onDismissRequest = { syncChangedWarning = false }, title = { Text("가사 줄 구성이 변경되었습니다") },
        text = { Text("기존 싱크를 유지하면 다른 줄에 시간이 연결될 수 있습니다. 싱크를 초기화하고 저장할까요?") },
        confirmButton = { TextButton({
            val source = when { importedFromLrc -> LyricsSource.USER_LRC; staged != null -> LyricsSource.USER_SEARCH; else -> LyricsSource.USER_MANUAL }
            viewModel.saveLyrics(trackId, text, emptyList(), source); viewModel.stageLyrics(null); syncChangedWarning = false; back()
        }) { Text("싱크 초기화 후 저장") } },
        dismissButton = { TextButton({ syncChangedWarning = false }) { Text("계속 편집") } },
    )
    if (removeSyncConfirm) AlertDialog(
        onDismissRequest = { removeSyncConfirm = false },
        title = { Text("싱크를 제거할까요?") },
        text = { Text("가사는 그대로 유지하고 시간 정보만 삭제합니다.") },
        confirmButton = { TextButton({
            viewModel.removeLyricsSync(trackId, text) { synced = emptyList(); originalText = text }
            removeSyncConfirm = false
        }) { Text("싱크 제거") } },
        dismissButton = { TextButton({ removeSyncConfirm = false }) { Text("취소") } },
    )
    if (shareConfirm) AlertDialog(
        onDismissRequest = { shareConfirm = false }, title = { Text("가사를 공유할까요?") },
        text = { Column { Text("음원 파일은 업로드하지 않고, 곡 식별 정보와 가사만 공유합니다."); Text(text.take(500), Modifier.padding(top = 12.dp)) } },
        confirmButton = { TextButton({ shareConfirm = false; viewModel.shareLyrics(trackId, text, synced) }) { Text("공유하기") } },
        dismissButton = { TextButton({ shareConfirm = false }) { Text("취소") } },
    )
    when (val value = community) {
        CommunityActionState.Loading -> AlertDialog(onDismissRequest = {}, title = { Text("가사 공유 중") }, text = { LinearProgressIndicator(Modifier.fillMaxWidth()) }, confirmButton = {})
        is CommunityActionState.Success, is CommunityActionState.Error -> AlertDialog(
            onDismissRequest = viewModel::resetCommunityAction,
            title = { Text(if (value is CommunityActionState.Success) "완료" else "공유 실패") },
            text = { Text(if (value is CommunityActionState.Success) value.message else (value as CommunityActionState.Error).message) },
            confirmButton = { TextButton(viewModel::resetCommunityAction) { Text("확인") } },
        )
        else -> Unit
    }
}

@Composable
fun LyricsSyncScreen(
    trackId: String,
    viewModel: MainViewModel,
    back: () -> Unit,
    saved: () -> Unit,
) {
    val context = LocalContext.current
    val staged by viewModel.stagedLyrics.collectAsStateWithLifecycle()
    val track by produceState<TrackEntity?>(null, trackId) { value = viewModel.track(trackId) }
    var lines by remember(trackId) { mutableStateOf<List<String>>(emptyList()) }
    val stamps = remember(trackId) { mutableStateMapOf<Int, Long>() }
    var index by remember(trackId) { mutableIntStateOf(0) }
    var groupSize by remember(trackId) { mutableIntStateOf(1) }
    var loaded by remember(trackId) { mutableStateOf(false) }
    var dirty by remember(trackId) { mutableStateOf(false) }
    var confirmBack by remember(trackId) { mutableStateOf(false) }
    var previewPositionMs by remember(trackId) { mutableLongStateOf(0L) }
    var previewDurationMs by remember(trackId) { mutableLongStateOf(0L) }
    var previewPlaying by remember(trackId) { mutableStateOf(false) }
    val previewPlayer = remember(track?.uri) {
        track?.let { selected ->
            ExoPlayer.Builder(context).build().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(),
                    true,
                )
                setMediaItem(MediaItem.fromUri(selected.uri))
                prepare()
                seekTo(0L)
                playWhenReady = true
            }
        }
    }
    LaunchedEffect(trackId) {
        // The sync editor owns an isolated preview player. The service player must not keep
        // sounding underneath it, and editing always begins against the source from 00:00.
        viewModel.player.pause()
    }
    DisposableEffect(previewPlayer) {
        onDispose { previewPlayer?.release() }
    }
    LaunchedEffect(previewPlayer) {
        val player = previewPlayer ?: return@LaunchedEffect
        while (true) {
            previewPositionMs = player.currentPosition.coerceAtLeast(0L)
            previewDurationMs = player.duration.takeIf { it != C.TIME_UNSET && it > 0L }
                ?: track?.durationMs
                ?: 0L
            previewPlaying = player.isPlaying
            delay(80L)
        }
    }
    LaunchedEffect(trackId, staged) {
        if (!loaded) {
            val existing = if (staged != null) staged!!.plainText to staged!!.syncedText?.let(LrcCodec::parse).orEmpty() else viewModel.lyrics(trackId)
            lines = existing.first.lines().filter { it.isNotBlank() }
            stamps.clear()
            stamps.putAll(existing.second.take(lines.size).mapIndexed { i, line -> i to line.startTimeMs })
            loaded = true
        }
    }
    fun seekToStampedLine(targetIndex: Int) {
        val safeIndex = targetIndex.coerceIn(0, lines.lastIndex.coerceAtLeast(0))
        index = safeIndex
        val targetTime = stamps[safeIndex]
            ?: stamps.filterKeys { it <= safeIndex }.maxByOrNull { it.key }?.value
            ?: 0L
        previewPlayer?.seekTo(targetTime)
        previewPositionMs = targetTime
    }
    fun leave() { if (dirty) confirmBack = true else back() }
    BackHandler(onBack = ::leave)
    Column(Modifier.fillMaxSize().padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { IconButton(::leave) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") }; Text("가사 싱크 편집", style = MaterialTheme.typography.titleLarge) }
        Text("${formatLyricsTime(previewPositionMs)} / ${formatLyricsTime(previewDurationMs)}", color = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            val currentStart = index.coerceIn(0, lines.size)
            val currentEnd = (currentStart + groupSize).coerceAtMost(lines.size)
            lines.subList((currentStart - groupSize).coerceAtLeast(0), currentStart).forEach { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.clickable { seekToStampedLine((currentStart - groupSize).coerceAtLeast(0)) }) }
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(16.dp), modifier = Modifier.padding(vertical = 14.dp)) {
                Text(
                    if (currentStart == lines.size) "모든 줄의 싱크를 지정했습니다." else lines.subList(currentStart, currentEnd).joinToString("\n"),
                    Modifier.padding(22.dp),
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            if (currentStart < lines.size) stamps[currentStart]?.let { Text("지정 ${formatLyricsTime(it)}", color = MaterialTheme.colorScheme.primary) }
            lines.subList(currentEnd, (currentEnd + groupSize).coerceAtMost(lines.size)).forEach { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.clickable { seekToStampedLine(currentEnd) }) }
        }
        // 싱크는 현재 재생 위치에만 기록한다. ±초 미세 조절은 제공하지 않는다.
        FilledIconButton({ previewPlayer?.let { if (it.isPlaying) it.pause() else it.play() } }) {
            Icon(if (previewPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "재생/일시정지")
        }
        Row(verticalAlignment = Alignment.CenterVertically) { Text("한 번에"); (1..3).forEach { count -> FilterChip(groupSize == count, { groupSize = count }, { Text("${count}줄") }, Modifier.padding(start = 4.dp)) } }
        val syncedCount = lines.indices.count(stamps::containsKey)
        Text("${syncedCount}/${lines.size}줄 싱크 완료", color = if (syncedCount == lines.size && lines.isNotEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton({
                val target = previousSyncedBlockStart(stamps, index)
                    ?: (index - groupSize).coerceAtLeast(0)
                seekToStampedLine(target)
            }, enabled = index > 0) { Text("이전") }
            Button({
                if (lines.isNotEmpty() && index < lines.size) {
                    val targets = index until (index + groupSize).coerceAtMost(lines.size)
                    val exactPosition = previewPlayer?.currentPosition?.coerceAtLeast(0L) ?: previewPositionMs
                    targets.forEach { stamps[it] = exactPosition }
                    dirty = true
                    index = (index + groupSize).coerceAtMost(lines.size)
                }
            }, enabled = index < lines.size) { Text("다음") }
        }
        Button({
            buildSyncedLyrics(trackId, lines, stamps)?.let { result ->
                viewModel.saveLyrics(trackId, lines.joinToString("\n"), result, if (staged != null) LyricsSource.USER_SEARCH else LyricsSource.USER_MANUAL) {
                    viewModel.stageLyrics(null)
                    saved()
                }
            }
        }, enabled = loaded && syncedCount == lines.size && lines.isNotEmpty(), modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { Text("싱크 가사 저장") }
        TextButton(::leave) { Text("취소") }
    }
    if (confirmBack) AlertDialog({ confirmBack = false }, { TextButton({ confirmBack = false; back() }) { Text("나가기") } }, dismissButton = { TextButton({ confirmBack = false }) { Text("계속 편집") } }, title = { Text("변경사항을 저장하지 않고 나가시겠습니까?") })
}

@Composable private fun LyricsError(message: String, retry: () -> Unit) = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(message, color = MaterialTheme.colorScheme.error); Button(retry, Modifier.padding(top = 8.dp)) { Text("다시 시도") } } }
private fun formatLyricsTime(ms: Long): String { val seconds = (ms / 1000).coerceAtLeast(0); return "%d:%02d".format(seconds / 60, seconds % 60) }
