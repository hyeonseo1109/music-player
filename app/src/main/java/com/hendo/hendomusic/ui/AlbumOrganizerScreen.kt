@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.hendo.hendomusic.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.hendo.hendomusic.MainUiState
import com.hendo.hendomusic.MainViewModel
import com.hendo.hendomusic.data.AlbumFolderEntity
import com.hendo.hendomusic.data.UserAlbumEntity
import com.hendo.hendomusic.data.displayArtworkUri
import com.hendo.hendomusic.PlaylistImportState
import kotlin.math.roundToInt

@Composable
fun AlbumOrganizerScreen(ui: MainUiState, viewModel: MainViewModel, openAlbum: (Long) -> Unit) {
    var createAlbum by remember { mutableStateOf(false) }
    var createFolder by remember { mutableStateOf(false) }
    var folderSheet by remember { mutableStateOf<AlbumFolderEntity?>(null) }
    var pendingPair by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    var specialTracks by remember { mutableStateOf<Pair<String, List<com.hendo.hendomusic.data.TrackEntity>>?>(null) }
    var gridMode by rememberSaveable { mutableStateOf(true) }
    val rootAlbums = remember { mutableStateListOf<UserAlbumEntity>() }
    val orderedFolders = remember { mutableStateListOf<AlbumFolderEntity>() }
    var draggedId by remember { mutableStateOf<Long?>(null) }
    var dragX by remember { mutableFloatStateOf(0f) }
    var dragY by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val playlistImport by viewModel.playlistImport.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(playlistImport) {
        val message = when (val state = playlistImport) {
            is PlaylistImportState.Success -> state.message
            is PlaylistImportState.Error -> state.message
            PlaylistImportState.Idle -> null
        }
        message?.let { snackbar.showSnackbar(it); viewModel.resetPlaylistImport() }
    }

    LaunchedEffect(ui.albums, draggedId) {
        if (draggedId == null) {
            rootAlbums.clear(); rootAlbums.addAll(ui.albums.filter { it.folderId == null }.sortedBy { it.sortOrder })
        }
    }
    LaunchedEffect(ui.folders) { orderedFolders.clear(); orderedFolders.addAll(ui.folders.sortedBy { it.sortOrder }) }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("내 앨범") },
            actions = {
                IconButton({ createFolder = true }) { Icon(Icons.Default.CreateNewFolder, "빈 폴더 만들기") }
                IconButton({ gridMode = !gridMode }) { Icon(if (gridMode) Icons.Default.ViewList else Icons.Default.GridView, if (gridMode) "목록형 보기" else "앨범형 보기") }
                IconButton({ createAlbum = true }) { Icon(Icons.Default.Add, "앨범 만들기") }
            },
        )
        Text(
            if (gridMode) "앨범형 보기 · 앨범을 길게 눌러 순서를 옮기거나 다른 앨범과 묶을 수 있습니다" else "목록형 보기 · 앨범을 길게 눌러 순서를 옮기거나 다른 앨범과 묶을 수 있습니다",
            Modifier.padding(horizontal = 18.dp, vertical = 6.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(Modifier.weight(1f)) {
        if (gridMode) LazyVerticalGrid(columns = GridCells.Adaptive(132.dp), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) { SpecialAlbumCard("좋아요한 곡", ui.tracks.count { it.isFavorite }, Icons.Default.Favorite) { specialTracks = "좋아요한 곡" to ui.tracks.filter { it.isFavorite } } }
            if (ui.settings.trackListening) item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) { SpecialAlbumCard("많이 들은 곡", ui.tracks.count { it.playCount > 0 }, Icons.Default.AutoGraph) { specialTracks = "많이 들은 곡" to ui.tracks.filter { it.playCount > 0 }.sortedByDescending { it.playCount } } }
            items(orderedFolders, key = { "folder:${it.id}" }) { folder -> FolderTile(folder, ui.albums.filter { it.folderId == folder.id }, viewModel) { folderSheet = folder } }
            items(rootAlbums, key = { "album:${it.id}" }) { album -> AlbumTile(album, viewModel, { openAlbum(album.id) }) }
        } else LazyColumn(contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { SpecialAlbumCard("좋아요한 곡", ui.tracks.count { it.isFavorite }, Icons.Default.Favorite) { specialTracks = "좋아요한 곡" to ui.tracks.filter { it.isFavorite } }; if (ui.settings.trackListening) SpecialAlbumCard("많이 들은 곡", ui.tracks.count { it.playCount > 0 }, Icons.Default.AutoGraph) { specialTracks = "많이 들은 곡" to ui.tracks.filter { it.playCount > 0 }.sortedByDescending { it.playCount } } }
            items(orderedFolders, key = { "folder:${it.id}" }) { folder ->
                val members = ui.albums.filter { it.folderId == folder.id }
                FolderCard(folder, members, Modifier.pointerInput(folder.id, orderedFolders.size) { detectDragGesturesAfterLongPress(onDragStart = { haptics.performHapticFeedback(HapticFeedbackType.LongPress) }, onDrag = { change, amount -> change.consume(); val from = orderedFolders.indexOfFirst { it.id == folder.id }; val to = (from + if (amount.y > 0) 1 else -1).coerceIn(orderedFolders.indices); if (from != to) orderedFolders.add(to, orderedFolders.removeAt(from)) }, onDragEnd = { viewModel.reorderFolders(orderedFolders.map { it.id }) }, onDragCancel = {}) }, { folderSheet = folder })
            }
            items(rootAlbums, key = { "album:${it.id}" }) { album ->
                val dragging = draggedId == album.id
                val scale by animateFloatAsState(if (dragging) 1.03f else 1f, animationSpec = tween(110), label = "albumDrag")
                AlbumCard(
                    album,
                    Modifier
                        .zIndex(if (dragging) 2f else 0f)
                        .graphicsLayer { scaleX = scale; scaleY = scale; translationX = if (dragging) dragX else 0f; translationY = if (dragging) dragY else 0f },
                    Modifier.pointerInput(album.id, rootAlbums.size, ui.folders.size) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { draggedId = album.id; dragX = 0f; dragY = 0f; haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
                                onDragCancel = { draggedId = null; dragX = 0f; dragY = 0f },
                                onDragEnd = {
                                    val from = rootAlbums.indexOfFirst { it.id == album.id }
                                    val rowPx = with(density) { 82.dp.toPx() }
                                    val targetRoot = (from + (dragY / rowPx).roundToInt()).coerceIn(rootAlbums.indices)
                                    if (dragX > with(density) { 72.dp.toPx() }) {
                                        val folderTarget = ui.folders.getOrNull((dragY / rowPx).roundToInt().coerceAtLeast(0))
                                        if (folderTarget != null) viewModel.moveAlbumToFolder(album.id, folderTarget.id)
                                        else rootAlbums.getOrNull(targetRoot)?.takeIf { it.id != album.id }?.let { pendingPair = album.id to it.id }
                                    } else if (from >= 0 && targetRoot != from) {
                                        rootAlbums.add(targetRoot, rootAlbums.removeAt(from))
                                        viewModel.reorderAlbums(rootAlbums.map { it.id })
                                    }
                                    draggedId = null; dragX = 0f; dragY = 0f
                                },
                                onDrag = { change, amount -> change.consume(); dragX += amount.x; dragY += amount.y },
                            )
                    },
                    viewModel,
                    { openAlbum(album.id) },
                )
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
        }
    }

    if (createAlbum) AlbumNameDialog("새 앨범") { name -> name?.let(viewModel::createAlbum); createAlbum = false }
    if (createFolder) AlbumNameDialog("새 앨범 폴더") { name -> name?.let(viewModel::createFolder); createFolder = false }
    pendingPair?.let { pair ->
        AlbumNameDialog("새 폴더 이름", "새 폴더") { name -> name?.let { viewModel.createFolderFromAlbums(it, pair.first, pair.second) }; pendingPair = null }
    }
    folderSheet?.let { folder ->
        FolderSheet(folder, ui.albums.filter { it.folderId == folder.id }, viewModel, openAlbum) { folderSheet = null }
    }
    specialTracks?.let { (name, tracks) ->
        AlertDialog(onDismissRequest = { specialTracks = null }, title = { Text(name) }, text = { if (tracks.isEmpty()) Text("아직 곡이 없습니다.") else LazyColumn(Modifier.heightIn(max = 380.dp)) { items(tracks, key = { it.id }) { track -> ListItem({ Text(track.title) }, supportingContent = { Text(track.artist) }, modifier = Modifier.clickable { viewModel.play(track, tracks); specialTracks = null }) } } }, confirmButton = { TextButton({ specialTracks = null }) { Text("닫기") } })
    }
}

@Composable private fun SpecialAlbumCard(name: String, count: Int, icon: androidx.compose.ui.graphics.vector.ImageVector, open: () -> Unit) {
    ListItem(
        headlineContent = { Text(name) }, supportingContent = { Text("${count}곡 · 고정 시스템 앨범") },
        leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) },
        modifier = Modifier.clickable(onClick = open),
    )
}

@Composable private fun AlbumCard(album: UserAlbumEntity, modifier: Modifier = Modifier, dragModifier: Modifier = Modifier, viewModel: MainViewModel, open: () -> Unit) {
    val tracks by viewModel.observeAlbumTracks(album.id).collectAsStateWithLifecycle(emptyList())
    Surface(modifier.fillMaxWidth().purpleGlass(18).clickable(onClick = open), shape = RoundedCornerShape(18.dp), color = androidx.compose.ui.graphics.Color.Transparent) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(tracks.firstOrNull()?.displayArtworkUri(), null, Modifier.size(48.dp).padding(end = 10.dp))
            Icon(Icons.Default.DragHandle, "이 앨범을 길게 눌러 이동", dragModifier, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f).padding(start = 10.dp)) { Text(album.name, style = MaterialTheme.typography.titleMedium); Text("${tracks.size}곡", style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable private fun AlbumTile(album: UserAlbumEntity, viewModel: MainViewModel, open: () -> Unit) {
    val tracks by viewModel.observeAlbumTracks(album.id).collectAsStateWithLifecycle(emptyList())
    Surface(Modifier.fillMaxWidth().purpleGlass(18).clickable(onClick = open), shape = RoundedCornerShape(18.dp), color = androidx.compose.ui.graphics.Color.Transparent) {
        Column(Modifier.padding(10.dp)) {
            AsyncImage(tracks.firstOrNull()?.displayArtworkUri(), null, Modifier.fillMaxWidth().aspectRatio(1f), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
            Spacer(Modifier.height(8.dp)); Text(album.name, maxLines = 1, style = MaterialTheme.typography.titleSmall); Text("${tracks.size}곡", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable private fun FolderTile(folder: AlbumFolderEntity, members: List<UserAlbumEntity>, viewModel: MainViewModel, click: () -> Unit) {
    Surface(Modifier.fillMaxWidth().purpleGlass(18).clickable(onClick = click), shape = RoundedCornerShape(18.dp), color = androidx.compose.ui.graphics.Color.Transparent) {
        Column(Modifier.padding(12.dp)) { Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primaryContainer)) {
            if (members.isEmpty()) Icon(Icons.Default.Folder, null, Modifier.align(Alignment.Center).size(54.dp), tint = MaterialTheme.colorScheme.primary)
            else members.take(4).forEachIndexed { index, album -> FolderAlbumThumb(album, viewModel, index) }
        }; Spacer(Modifier.height(8.dp)); Text(folder.name, maxLines = 1); Text("${members.size}개 앨범", style = MaterialTheme.typography.bodySmall) }
    }
}
@Composable private fun BoxScope.FolderAlbumThumb(album: UserAlbumEntity, viewModel: MainViewModel, index: Int) {
    val tracks by viewModel.observeAlbumTracks(album.id).collectAsStateWithLifecycle(emptyList())
    val alignment = listOf(Alignment.TopStart, Alignment.TopEnd, Alignment.BottomStart, Alignment.BottomEnd)[index]
    AsyncImage(tracks.firstOrNull()?.displayArtworkUri(), null, Modifier.align(alignment).size(50.dp).padding(2.dp), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
}

@Composable private fun FolderCard(folder: AlbumFolderEntity, members: List<UserAlbumEntity>, modifier: Modifier = Modifier, click: () -> Unit) {
    Surface(modifier.fillMaxWidth().purpleGlass(20).clickable(onClick = click), shape = RoundedCornerShape(20.dp), color = androidx.compose.ui.graphics.Color.Transparent) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Folder, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(folder.name, style = MaterialTheme.typography.titleMedium)
                Text("${members.size}개 앨범 · ${members.take(3).joinToString(" · ") { it.name }}", maxLines = 1, style = MaterialTheme.typography.bodySmall)
            }
            Icon(Icons.Default.ChevronRight, null)
        }
    }
}

@Composable private fun FolderSheet(folder: AlbumFolderEntity, members: List<UserAlbumEntity>, viewModel: MainViewModel, openAlbum: (Long) -> Unit, close: () -> Unit) {
    var rename by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = close) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(folder.name, Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
            IconButton({ rename = true }) { Icon(Icons.Default.Edit, "폴더명 수정") }
            IconButton({ confirmDelete = true }) { Icon(Icons.Default.DeleteOutline, "폴더 삭제") }
        }
        if (members.isEmpty()) Text("빈 폴더입니다", Modifier.padding(24.dp))
        members.forEach { album ->
            ListItem(
                headlineContent = { Text(album.name) }, leadingContent = { Icon(Icons.Default.Album, null) },
                trailingContent = { TextButton({ viewModel.moveAlbumToRoot(album.id) }) { Text("root로 이동") } },
                modifier = Modifier.clickable { close(); openAlbum(album.id) },
            )
        }
        Spacer(Modifier.height(28.dp))
    }
    if (rename) AlbumNameDialog("폴더명 수정", folder.name) { it?.let { name -> viewModel.renameFolder(folder.id, name) }; rename = false }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("폴더를 삭제할까요?") },
        text = { Text("안의 앨범은 삭제되지 않고 root로 이동합니다.") },
        confirmButton = { TextButton({ viewModel.dissolveFolder(folder.id); confirmDelete = false; close() }) { Text("폴더 삭제") } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text("취소") } },
    )
}

@Composable private fun AlbumNameDialog(title: String, initial: String = "", done: (String?) -> Unit) {
    var value by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = { done(null) }, title = { Text(title) },
        text = { OutlinedTextField(value, { value = it }, singleLine = true) },
        confirmButton = { TextButton({ if (value.isNotBlank()) done(value.trim()) }) { Text("확인") } },
        dismissButton = { TextButton({ done(null) }) { Text("취소") } },
    )
}
