@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.hendo.hendomusic.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.hendo.hendomusic.MainUiState
import com.hendo.hendomusic.MainViewModel
import com.hendo.hendomusic.artwork.ArtworkSearchState
import com.hendo.hendomusic.data.TrackEntity
import com.hendo.hendomusic.data.displayArtworkUri
import com.hendo.hendomusic.library.MetadataUpdate
import com.hendo.hendomusic.network.ArtworkResult

@Composable
fun MetadataEditorScreen(
    trackId: String,
    ui: MainUiState,
    viewModel: MainViewModel,
    requestWrite: (TrackEntity, MetadataUpdate, (String) -> Unit) -> Unit,
    requestArtworkWrite: (TrackEntity, () -> Unit, (String) -> Unit) -> Unit,
    back: () -> Unit,
) {
    val track = ui.tracks.firstOrNull { it.id == trackId }
    if (track == null) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("곡을 찾을 수 없습니다") }; return }
    var title by remember(trackId) { mutableStateOf(track.title) }
    var artist by remember(trackId) { mutableStateOf(track.artist) }
    var album by remember(trackId) { mutableStateOf(track.album) }
    var albumArtist by remember(trackId) { mutableStateOf(track.albumArtist.orEmpty()) }
    var message by remember { mutableStateOf<String?>(null) }
    var artworkMenu by remember { mutableStateOf(false) }
    var artworkSearch by remember { mutableStateOf(false) }
    var cropSource by remember { mutableStateOf<Uri?>(null) }
    var artworkSelectionMode by remember { mutableStateOf("picker") }
    var confirmBack by remember { mutableStateOf(false) }
    val dirty = title != track.title || artist != track.artist || album != track.album || albumArtist != track.albumArtist.orEmpty()
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { cropSource = it }
    fun handleBack() { if (dirty) confirmBack = true else back() }
    BackHandler(onBack = ::handleBack)

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("곡 정보 수정") },
            navigationIcon = { IconButton(::handleBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") } },
            actions = { TextButton({
                requestWrite(track, MetadataUpdate(title.trim(), artist.trim(), album.trim(), albumArtist.trim().ifBlank { null })) {
                    message = it; if (it == "저장했습니다") back()
                }
            }, enabled = title.isNotBlank()) { Text("저장") } },
        )
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.align(Alignment.CenterHorizontally).padding(12.dp).size(190.dp).clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surfaceVariant).clickable { artworkMenu = true }, contentAlignment = Alignment.Center) {
                val art = track.displayArtworkUri()
                if (art != null) AsyncImage(art, "앨범 커버", Modifier.fillMaxSize(), contentScale = ContentScale.Crop) else Icon(Icons.Default.AddPhotoAlternate, "앨범 커버 선택", Modifier.size(58.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Text("커버를 눌러 갤러리 선택·검색·제거", Modifier.align(Alignment.CenterHorizontally), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth().hendoClipboardToolbar({ title }) { title += it }, label = { Text("곡 제목") }, singleLine = true)
            OutlinedTextField(artist, { artist = it }, Modifier.fillMaxWidth().hendoClipboardToolbar({ artist }) { artist += it }, label = { Text("아티스트") }, singleLine = true)
            OutlinedTextField(album, { album = it }, Modifier.fillMaxWidth().hendoClipboardToolbar({ album }) { album += it }, label = { Text("앨범") }, singleLine = true)
            OutlinedTextField(albumArtist, { albumArtist = it }, Modifier.fillMaxWidth().hendoClipboardToolbar({ albumArtist }) { albumArtist += it }, label = { Text("앨범 아티스트") }, singleLine = true)
            Text(if (track.fileName.endsWith(".mp3", true) || track.fileName.endsWith(".m4a", true) || track.fileName.endsWith(".flac", true)) "MP3/M4A/FLAC는 파일 안의 태그를 직접 수정한 뒤 다른 음악 앱에도 반영합니다. Android 쓰기 승인이 필요합니다." else "현재 파일 자체 태그 수정은 MP3/M4A/FLAC만 지원합니다. 지원하지 않는 형식은 잘못된 부분 변경을 하지 않으며 저장하지 않습니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            message?.let { Text(it, color = if (it.startsWith("저장 실패")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) }
        }
    }

    if (artworkMenu) ModalBottomSheet(onDismissRequest = { artworkMenu = false }) {
        ListItem({ Text("갤러리에서 선택") }, leadingContent = { Icon(Icons.Default.PhotoLibrary, null) }, modifier = Modifier.clickable { artworkSelectionMode = "picker"; artworkMenu = false; photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) })
        ListItem({ Text("이미지 검색") }, leadingContent = { Icon(Icons.Default.ImageSearch, null) }, modifier = Modifier.clickable { artworkSelectionMode = "search"; artworkMenu = false; artworkSearch = true; viewModel.resetArtworkSearch() })
        ListItem({ Text("현재 이미지 제거") }, leadingContent = { Icon(Icons.Default.HideImage, null) }, modifier = Modifier.clickable { viewModel.setArtwork(track.id, null); artworkMenu = false })
        ListItem({ Text("취소") }, leadingContent = { Icon(Icons.Default.Close, null) }, modifier = Modifier.clickable { artworkMenu = false })
        Spacer(Modifier.height(24.dp))
    }
    if (artworkSearch) ArtworkSearchDialog(track, viewModel, { artworkSearch = false }) { result ->
        result.largeUrl?.let { url -> viewModel.prepareRemoteArtwork(url) { prepared -> prepared.onSuccess { cropSource = it; artworkSearch = false }.onFailure { message = "이미지 다운로드 실패: ${it.localizedMessage}" } } }
    }
    cropSource?.let { source -> CropArtworkDialog(source, track.id, viewModel, { cropSource = null }, {
        cropSource = null
        if (artworkSelectionMode == "search") artworkSearch = true else photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }, apply = { zoom, offsetX, offsetY, completed ->
        requestArtworkWrite(track, {
            viewModel.applyCroppedArtwork(track.id, source, zoom, offsetX, offsetY, completed)
        }) { reason -> completed(Result.failure(IllegalStateException(reason))) }
    }) { uri -> cropSource = null; message = "새 커버를 파일과 앱에 적용했습니다: ${uri.lastPathSegment}" } }
    if (confirmBack) AlertDialog(
        onDismissRequest = { confirmBack = false }, title = { Text("변경사항을 저장하지 않고 나가시겠습니까?") },
        confirmButton = { TextButton({ confirmBack = false; back() }) { Text("나가기") } }, dismissButton = { TextButton({ confirmBack = false }) { Text("계속 편집") } },
    )
}

@Composable private fun ArtworkSearchDialog(track: TrackEntity, viewModel: MainViewModel, close: () -> Unit, select: (ArtworkResult) -> Unit) {
    val state by viewModel.artworkSearch.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf(listOf(track.artist, track.title, track.album).filter { it.isNotBlank() }.joinToString(" ")) }
    var preview by remember { mutableStateOf<ArtworkResult?>(null) }
    Dialog(onDismissRequest = close) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) { IconButton(close) { Icon(Icons.Default.Close, "닫기") }; Text("앨범 커버 검색", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
                Row(verticalAlignment = Alignment.CenterVertically) { OutlinedTextField(query, { query = it }, Modifier.weight(1f).hendoClipboardToolbar({ query }) { query += it }, singleLine = true, label = { Text("검색어") }); IconButton({ viewModel.searchArtwork(query) }, enabled = query.isNotBlank()) { Icon(Icons.Default.Search, "검색") } }
                Spacer(Modifier.height(10.dp))
                when (val value = state) {
                    ArtworkSearchState.Idle -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Button({ viewModel.searchArtwork(query) }) { Text("자동 검색어로 검색") } }
                    ArtworkSearchState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    is ArtworkSearchState.Error -> ErrorState(value.message) { viewModel.searchArtwork(query) }
                    is ArtworkSearchState.Success -> if (value.results.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("검색 결과가 없습니다") } else LazyVerticalGrid(GridCells.Fixed(3), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(value.results) { result -> AsyncImage(result.artworkUrl100, result.collectionName, Modifier.aspectRatio(1f).clip(RoundedCornerShape(10.dp)).clickable { preview = result }, contentScale = ContentScale.Crop) }
                    }
                }
            }
        }
    }
    preview?.let { result -> AlertDialog(
        onDismissRequest = { preview = null }, title = { Text("이 이미지를 선택할까요?") },
        text = { Column { AsyncImage(result.largeUrl, null, Modifier.fillMaxWidth().aspectRatio(1f), contentScale = ContentScale.Crop); Text(result.collectionName.orEmpty(), Modifier.padding(top = 8.dp)) } },
        confirmButton = { TextButton({ preview = null; select(result) }) { Text("Crop 후 적용") } }, dismissButton = { TextButton({ preview = null }) { Text("취소") } },
    ) }
}

@Composable private fun CropArtworkDialog(source: Uri, trackId: String, viewModel: MainViewModel, close: () -> Unit, reselect: () -> Unit, apply: (Float, Float, Float, (Result<Uri>) -> Unit) -> Unit, applied: (Uri) -> Unit) {
    var zoom by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var applying by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Dialog(onDismissRequest = { if (!applying) close() }) {
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("1:1 커버 자르기", style = MaterialTheme.typography.titleLarge)
                Text("이미지를 드래그하고 확대 비율을 조절하세요", style = MaterialTheme.typography.bodySmall)
                Box(Modifier.padding(vertical = 14.dp).fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant).pointerInput(source) {
                    detectDragGestures { change, amount -> change.consume(); offsetX = (offsetX + amount.x / size.width * 2).coerceIn(-1f, 1f); offsetY = (offsetY + amount.y / size.height * 2).coerceIn(-1f, 1f) }
                }) {
                    AsyncImage(source, null, Modifier.fillMaxSize().graphicsLayer { scaleX = zoom; scaleY = zoom; translationX = offsetX * size.width / 3; translationY = offsetY * size.height / 3 }, contentScale = ContentScale.Crop)
                }
                Text("확대 ${"%.1f".format(zoom)}×")
                Slider(zoom, { zoom = it }, valueRange = 1f..3f)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(close, enabled = !applying) { Text("취소") }
                    OutlinedButton(reselect, enabled = !applying) { Text("다시 선택") }
                    Button({ applying = true; apply(zoom, offsetX, offsetY) { result -> applying = false; result.onSuccess(applied).onFailure { error = it.localizedMessage ?: "커버 적용에 실패했습니다" } } }, enabled = !applying, modifier = Modifier.padding(start = 8.dp)) { if (applying) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("적용") }
                }
            }
        }
    }
}

@Composable private fun ErrorState(message: String, retry: () -> Unit) = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(message, color = MaterialTheme.colorScheme.error); Button(retry, Modifier.padding(top = 10.dp)) { Text("다시 시도") } } }
