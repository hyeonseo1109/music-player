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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hendo.hendomusic.MainViewModel
import com.hendo.hendomusic.data.TrackEntity
import com.hendo.hendomusic.data.LyricsSource
import com.hendo.hendomusic.lyrics.LrcCodec
import com.hendo.hendomusic.lyrics.LyricsSearchResult
import com.hendo.hendomusic.lyrics.LyricsSearchState
import com.hendo.hendomusic.lyrics.SyncedLyricLine
import com.hendo.hendomusic.playback.PlaybackState
import com.hendo.hendomusic.network.CommunityActionState

@Composable
fun LyricsSearchScreen(track: TrackEntity, viewModel: MainViewModel, back: () -> Unit, edit: () -> Unit) {
    val state by viewModel.lyricsSearch.collectAsStateWithLifecycle()
    var title by remember(track.id) { mutableStateOf(track.title) }
    var artist by remember(track.id) { mutableStateOf(track.artist) }
    var preview by remember { mutableStateOf<LyricsSearchResult?>(null) }
    var reportTarget by remember { mutableStateOf<LyricsSearchResult?>(null) }
    val community by viewModel.communityAction.collectAsStateWithLifecycle()
    Scaffold(topBar = { TopAppBar({ Text("가사 검색") }, navigationIcon = { IconButton(back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") } }) }) { padding ->
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
                                Row(verticalAlignment = Alignment.CenterVertically) { Text(result.source, Modifier.weight(1f), fontWeight = FontWeight.Bold); AssistChip({}, { Text(if (result.synced) "싱크 있음" else "Plain") }); if (result.votes > 0) Text("추천 ${result.votes}", Modifier.padding(start = 8.dp)) }
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
        onDismissRequest = { preview = null }, title = { Text("${result.source} 가사 전체 확인") },
        text = { LazyColumn(Modifier.heightIn(max = 430.dp)) { item { Text(result.plainText) } } },
        confirmButton = { TextButton({ viewModel.stageLyrics(result); preview = null; edit() }) { Text("편집 화면에서 검토") } },
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

@Composable
fun LyricsEditorScreen(trackId: String, viewModel: MainViewModel, chooseLrc: () -> Unit, back: () -> Unit, openSync: () -> Unit) {
    val staged by viewModel.stagedLyrics.collectAsStateWithLifecycle()
    var text by remember(trackId) { mutableStateOf("") }
    var synced by remember(trackId) { mutableStateOf<List<SyncedLyricLine>>(emptyList()) }
    var originalText by remember(trackId) { mutableStateOf("") }
    var loaded by remember(trackId) { mutableStateOf(false) }
    var confirmBack by remember { mutableStateOf(false) }
    var syncChangedWarning by remember { mutableStateOf(false) }
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
    }, enabled = text.isNotBlank()) { Text("저장") } }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (staged != null) AssistChip({}, { Text("${staged!!.source}에서 local copy로 가져옴") }, Modifier.padding(horizontal = 16.dp))
            if (synced.isNotEmpty()) Text("싱크 ${synced.size}줄 유지 중", Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.primary)
            OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().weight(1f).padding(16.dp), placeholder = { Text("가사를 붙여넣거나 직접 입력하세요") })
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(chooseLrc) { Text("LRC 가져오기") }
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
fun LyricsSyncScreen(trackId: String, playback: PlaybackState, viewModel: MainViewModel, back: () -> Unit) {
    val staged by viewModel.stagedLyrics.collectAsStateWithLifecycle()
    var lines by remember { mutableStateOf<List<String>>(emptyList()) }
    var stamps by remember { mutableStateOf<Map<Int, Long>>(emptyMap()) }
    var index by remember { mutableIntStateOf(0) }
    var loaded by remember { mutableStateOf(false) }
    var dirty by remember { mutableStateOf(false) }
    var confirmBack by remember { mutableStateOf(false) }
    LaunchedEffect(trackId, staged) {
        if (!loaded) {
            val existing = if (staged != null) staged!!.plainText to staged!!.syncedText?.let(LrcCodec::parse).orEmpty() else viewModel.lyrics(trackId)
            lines = existing.first.lines().filter { it.isNotBlank() }
            stamps = existing.second.mapIndexed { i, line -> i to line.startTimeMs }.toMap(); loaded = true
            // Manual syncing always starts with a predictable zero-based playback position.
            viewModel.startSyncPlayback(trackId)
        }
    }
    fun leave() { if (dirty) confirmBack = true else back() }
    fun offset(delta: Long) { stamps = stamps.mapValues { (_, value) -> (value + delta).coerceAtLeast(0) }; dirty = true }
    BackHandler(onBack = ::leave)
    Column(Modifier.fillMaxSize().padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { IconButton(::leave) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") }; Text("가사 싱크 편집", style = MaterialTheme.typography.titleLarge) }
        Text("${formatLyricsTime(playback.positionMs)} / ${formatLyricsTime(playback.durationMs)}", color = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            lines.getOrNull(index - 1)?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.clickable { index-- }) }
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(16.dp), modifier = Modifier.padding(vertical = 14.dp)) { Text(lines.getOrNull(index).orEmpty(), Modifier.padding(22.dp), style = MaterialTheme.typography.titleLarge) }
            stamps[index]?.let { Text("지정 ${formatLyricsTime(it)}", color = MaterialTheme.colorScheme.primary) }
            lines.getOrNull(index + 1)?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.clickable { index++ }) }
        }
        Row { IconButton({ viewModel.player.seekTo((playback.positionMs - 3_000).coerceAtLeast(0)) }) { Icon(Icons.Default.Replay5, "3초 뒤로") }; FilledIconButton(viewModel.player::toggle) { Icon(if (playback.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "재생/일시정지") }; IconButton({ viewModel.player.seekTo(playback.positionMs + 3_000) }) { Icon(Icons.Default.Forward5, "3초 앞으로") } }
        Row(verticalAlignment = Alignment.CenterVertically) { OutlinedButton({ index = (index - 1).coerceAtLeast(0) }) { Text("이전 줄") }; Button({ if (lines.isNotEmpty()) { stamps = stamps + (index to playback.positionMs); dirty = true; index = (index + 1).coerceAtMost(lines.lastIndex) } }, Modifier.padding(horizontal = 8.dp)) { Text(if (stamps.containsKey(index)) "현재 줄 다시 지정" else "현재 줄 싱크") }; OutlinedButton({ index = (index + 1).coerceAtMost(lines.lastIndex.coerceAtLeast(0)) }) { Text("다음 줄") } }
        Text("전체 싱크 미세 조정", Modifier.padding(top = 10.dp), style = MaterialTheme.typography.labelMedium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) { listOf(-1000L, -500L, -100L, 100L, 500L, 1000L).forEach { delta -> SuggestionChip({ offset(delta) }, { Text(if (delta > 0) "+$delta" else "$delta") }) } }
        Button({ val result = lines.mapIndexed { i, text -> SyncedLyricLine("$trackId:$i", stamps[i] ?: 0, text) }; viewModel.saveLyrics(trackId, lines.joinToString("\n"), result, if (staged != null) LyricsSource.USER_SEARCH else LyricsSource.USER_MANUAL); viewModel.stageLyrics(null); back() }, enabled = lines.isNotEmpty() && stamps.isNotEmpty(), modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { Text("싱크 가사 저장") }
        TextButton(::leave) { Text("취소") }
    }
    if (confirmBack) AlertDialog({ confirmBack = false }, { TextButton({ confirmBack = false; back() }) { Text("나가기") } }, dismissButton = { TextButton({ confirmBack = false }) { Text("계속 편집") } }, title = { Text("변경사항을 저장하지 않고 나가시겠습니까?") })
}

@Composable private fun LyricsError(message: String, retry: () -> Unit) = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(message, color = MaterialTheme.colorScheme.error); Button(retry, Modifier.padding(top = 8.dp)) { Text("다시 시도") } } }
private fun formatLyricsTime(ms: Long): String { val seconds = (ms / 1000).coerceAtLeast(0); return "%d:%02d".format(seconds / 60, seconds % 60) }
