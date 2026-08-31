@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.hendo.hendomusic.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
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

data class AlbumMenuTarget(val id: Long, val name: String, val folder: Boolean)

@Composable
fun AlbumOrganizerScreen(ui: MainUiState, viewModel: MainViewModel, openAlbum: (Long) -> Unit, openFolder: (Long) -> Unit, openSpecial: (String) -> Unit) {
    var createAlbum by remember { mutableStateOf(false) }
    var createFolder by remember { mutableStateOf(false) }
    var menuTarget by remember { mutableStateOf<AlbumMenuTarget?>(null) }
    var renameTarget by remember { mutableStateOf<AlbumMenuTarget?>(null) }
    var deleteTarget by remember { mutableStateOf<AlbumMenuTarget?>(null) }
    var pendingPair by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    var gridMode by rememberSaveable { mutableStateOf(true) }
    val rootAlbums = remember { mutableStateListOf<UserAlbumEntity>() }
    val orderedFolders = remember { mutableStateListOf<AlbumFolderEntity>() }
    var draggedId by remember { mutableStateOf<Long?>(null) }
    var dragX by remember { mutableFloatStateOf(0f) }
    var dragY by remember { mutableFloatStateOf(0f) }
    var dragTotalX by remember { mutableFloatStateOf(0f) }
    var dragTotalY by remember { mutableFloatStateOf(0f) }
    var dragPointer by remember { mutableStateOf<Offset?>(null) }
    val folderBounds = remember { mutableStateMapOf<Long, Rect>() }
    val albumBounds = remember { mutableStateMapOf<Long, Rect>() }
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val playlistImport by viewModel.playlistImport.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val artworkPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        val target = menuTarget ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        if (target.folder) viewModel.setFolderArtwork(target.id, uri.toString()) else viewModel.setAlbumArtwork(target.id, uri.toString())
        menuTarget = null
    }

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
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) { SpecialAlbumCard("좋아요한 곡", ui.tracks.count { it.isFavorite }, Icons.Default.Favorite) { openSpecial("favorites") } }
            if (ui.settings.trackListening) item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) { SpecialAlbumCard("많이 들은 곡", ui.tracks.count { it.playCount > 0 }, Icons.Default.AutoGraph) { openSpecial("most-played") } }
            items(orderedFolders, key = { "folder:${it.id}" }) { folder ->
                FolderTile(
                    folder,
                    ui.albums.filter { it.folderId == folder.id },
                    viewModel,
                    Modifier.onGloballyPositioned { folderBounds[folder.id] = it.boundsInRoot() }.pointerInput(folder.id, orderedFolders.size) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
                            onDrag = { change, amount ->
                                change.consume()
                                val from = orderedFolders.indexOfFirst { it.id == folder.id }
                                val step = if (kotlin.math.abs(amount.y) >= kotlin.math.abs(amount.x)) if (amount.y > 0) 1 else -1 else 0
                                val to = (from + step).coerceIn(orderedFolders.indices)
                                if (from >= 0 && from != to) orderedFolders.add(to, orderedFolders.removeAt(from))
                            },
                            onDragEnd = { viewModel.reorderFolders(orderedFolders.map { it.id }) },
                            onDragCancel = {},
                        )
                    },
                    { openFolder(folder.id) },
                ) { menuTarget = AlbumMenuTarget(folder.id, folder.name, true) }
            }
            items(rootAlbums, key = { "album:${it.id}" }) { album ->
                val dragging = draggedId == album.id
                AlbumTile(album, viewModel, Modifier
                    .zIndex(if (dragging) 2f else 0f)
                    .graphicsLayer { translationX = if (dragging) dragX else 0f; translationY = if (dragging) dragY else 0f; scaleX = if (dragging) 1.04f else 1f; scaleY = if (dragging) 1.04f else 1f }
                    .onGloballyPositioned { albumBounds[album.id] = it.boundsInRoot() }
                    .pointerInput(album.id, rootAlbums.size) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { point -> draggedId = album.id; dragX = 0f; dragY = 0f; dragTotalX = 0f; dragTotalY = 0f; dragPointer = (albumBounds[album.id]?.topLeft ?: Offset.Zero) + point; haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
                        onDrag = { change, amount ->
                            change.consume(); dragX += amount.x; dragY += amount.y; dragTotalX += amount.x; dragTotalY += amount.y
                            dragPointer = (dragPointer ?: change.position) + amount
                            // When the finger is in an empty slot, reflow the neighbouring
                            // cards immediately. A direct hit on an album remains a folder
                            // creation drop, so reordering never steals that gesture.
                            val hitAlbum = rootAlbums.firstOrNull { it.id != album.id && albumBounds[it.id]?.contains(dragPointer ?: Offset.Unspecified) == true }
                            val hitFolder = orderedFolders.firstOrNull { folderBounds[it.id]?.contains(dragPointer ?: Offset.Unspecified) == true }
                            if (hitAlbum == null && hitFolder == null) {
                                val from = rootAlbums.indexOfFirst { it.id == album.id }
                                val horizontal = (dragX / with(density) { 132.dp.toPx() }).toInt()
                                val vertical = (dragY / with(density) { 160.dp.toPx() }).toInt() * 2
                                val to = (from + horizontal + vertical).coerceIn(rootAlbums.indices)
                                if (from >= 0 && to != from) {
                                    rootAlbums.add(to, rootAlbums.removeAt(from))
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    dragX = 0f; dragY = 0f
                                }
                            }
                        },
                        onDragCancel = { draggedId = null; dragX = 0f; dragY = 0f; dragTotalX = 0f; dragTotalY = 0f; dragPointer = null },
                        onDragEnd = {
                            val from = rootAlbums.indexOfFirst { it.id == album.id }
                            val columnShift = (dragTotalX / with(density) { 132.dp.toPx() }).roundToInt()
                            val rowShift = (dragTotalY / with(density) { 160.dp.toPx() }).roundToInt()
                            val target = (from + columnShift + rowShift * 2).coerceIn(rootAlbums.indices)
                            val targetAlbum = rootAlbums.getOrNull(target)
                            // Folder tiles are above the root album grid. Only an intentional
                            // upward drop can target them; ordinary reorder drags must never be
                            // captured just because any folder happens to exist.
                            val folderTarget = orderedFolders.firstOrNull { folderBounds[it.id]?.contains(dragPointer ?: Offset.Unspecified) == true }
                            val albumTarget = rootAlbums.firstOrNull { it.id != album.id && albumBounds[it.id]?.contains(dragPointer ?: Offset.Unspecified) == true }
                            if (folderTarget != null) {
                                // Grid mode supports the same album → folder drop as list mode.
                                viewModel.moveAlbumToFolder(album.id, folderTarget.id)
                            } else if (albumTarget != null) {
                                // Dropping an album onto another tile creates a named folder, matching list mode.
                                pendingPair = album.id to albumTarget.id
                            } else if (from >= 0 && target != from) {
                                rootAlbums.add(target, rootAlbums.removeAt(from)); viewModel.reorderAlbums(rootAlbums.map { it.id })
                            }
                            draggedId = null; dragX = 0f; dragY = 0f; dragTotalX = 0f; dragTotalY = 0f; dragPointer = null
                        },
                    )
                }, { openAlbum(album.id) }) { menuTarget = AlbumMenuTarget(album.id, album.name, false) }
            }
        } else LazyColumn(contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { SpecialAlbumCard("좋아요한 곡", ui.tracks.count { it.isFavorite }, Icons.Default.Favorite) { openSpecial("favorites") }; if (ui.settings.trackListening) SpecialAlbumCard("많이 들은 곡", ui.tracks.count { it.playCount > 0 }, Icons.Default.AutoGraph) { openSpecial("most-played") } }
            items(orderedFolders, key = { "folder:${it.id}" }) { folder ->
                val members = ui.albums.filter { it.folderId == folder.id }
                FolderCard(folder, members, viewModel, Modifier.onGloballyPositioned { folderBounds[folder.id] = it.boundsInRoot() }.pointerInput(folder.id, orderedFolders.size) { detectDragGesturesAfterLongPress(onDragStart = { haptics.performHapticFeedback(HapticFeedbackType.LongPress) }, onDrag = { change, amount -> change.consume(); val from = orderedFolders.indexOfFirst { it.id == folder.id }; val to = (from + if (amount.y > 0) 1 else -1).coerceIn(orderedFolders.indices); if (from != to) orderedFolders.add(to, orderedFolders.removeAt(from)) }, onDragEnd = { viewModel.reorderFolders(orderedFolders.map { it.id }) }, onDragCancel = {}) }, { openFolder(folder.id) }) { menuTarget = AlbumMenuTarget(folder.id, folder.name, true) }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f))
            }
            items(rootAlbums, key = { "album:${it.id}" }) { album ->
                val dragging = draggedId == album.id
                val scale by animateFloatAsState(if (dragging) 1.03f else 1f, animationSpec = tween(30), label = "albumDrag")
                AlbumCard(
                    album,
                    Modifier
                        .zIndex(if (dragging) 2f else 0f)
                        .graphicsLayer { scaleX = scale; scaleY = scale; translationX = if (dragging) dragX else 0f; translationY = if (dragging) dragY else 0f },
                    Modifier.onGloballyPositioned { albumBounds[album.id] = it.boundsInRoot() }.pointerInput(album.id, rootAlbums.size, ui.folders.size) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { point -> draggedId = album.id; dragX = 0f; dragY = 0f; dragPointer = (albumBounds[album.id]?.topLeft ?: Offset.Zero) + point; haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
                                onDragCancel = { draggedId = null; dragX = 0f; dragY = 0f; dragPointer = null },
                                onDragEnd = {
                                    val from = rootAlbums.indexOfFirst { it.id == album.id }
                                    val rowPx = with(density) { 82.dp.toPx() }
                                    val targetRoot = (from + (dragY / rowPx).roundToInt()).coerceIn(rootAlbums.indices)
                                    // A root reorder must never silently become a folder move.
                                    // Only an intentional drag into the header/folder zone can
                                    // enter a folder; ordinary vertical movement stays at root.
                                    val folderTarget = ui.folders.firstOrNull { folderBounds[it.id]?.contains(dragPointer ?: Offset.Unspecified) == true }
                                    val albumTarget = rootAlbums.firstOrNull { it.id != album.id && albumBounds[it.id]?.contains(dragPointer ?: Offset.Unspecified) == true }
                                    if (folderTarget != null) {
                                        viewModel.moveAlbumToFolder(album.id, folderTarget.id)
                                    } else if (albumTarget != null) {
                                        pendingPair = album.id to albumTarget.id
                                    } else if (from >= 0 && targetRoot != from) {
                                        rootAlbums.add(targetRoot, rootAlbums.removeAt(from))
                                        viewModel.reorderAlbums(rootAlbums.map { it.id })
                                    }
                                    draggedId = null; dragX = 0f; dragY = 0f; dragPointer = null
                                },
                                onDrag = { change, amount ->
                                    change.consume(); dragX += amount.x; dragY += amount.y
                                    dragPointer = (dragPointer ?: change.position) + amount
                                    val hitAlbum = rootAlbums.firstOrNull { it.id != album.id && albumBounds[it.id]?.contains(dragPointer ?: Offset.Unspecified) == true }
                                    val hitFolder = orderedFolders.firstOrNull { folderBounds[it.id]?.contains(dragPointer ?: Offset.Unspecified) == true }
                                    if (hitAlbum == null && hitFolder == null && kotlin.math.abs(dragY) >= with(density) { 76.dp.toPx() }) {
                                        val from = rootAlbums.indexOfFirst { it.id == album.id }
                                        val to = (from + if (dragY > 0) 1 else -1).coerceIn(rootAlbums.indices)
                                        if (from >= 0 && to != from) {
                                            rootAlbums.add(to, rootAlbums.removeAt(from))
                                            dragY = 0f
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        }
                                    }
                                },
                            )
                    },
                    viewModel,
                    { openAlbum(album.id) }, { menuTarget = AlbumMenuTarget(album.id, album.name, false) },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f))
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
    menuTarget?.let { target -> AlbumMenuSheet(target, { menuTarget = null }, { renameTarget = target; menuTarget = null }, { artworkPicker.launch("image/*") }, {
        if (target.folder) viewModel.setFolderArtwork(target.id, null) else viewModel.setAlbumArtwork(target.id, null); menuTarget = null
    }, {
        if (target.folder) viewModel.setFolderArtwork(target.id, "") else viewModel.setAlbumArtwork(target.id, ""); menuTarget = null
    }, { deleteTarget = target; menuTarget = null }) }
    renameTarget?.let { target -> AlbumNameDialog(if (target.folder) "폴더 이름 변경" else "앨범 이름 변경", target.name) { name -> if (name != null) { if (target.folder) viewModel.renameFolder(target.id, name) else viewModel.renameAlbum(target.id, name) }; renameTarget = null } }
    deleteTarget?.let { target -> AlertDialog(onDismissRequest = { deleteTarget = null }, title = { Text("${if (target.folder) "폴더" else "앨범"}을 삭제할까요?") }, text = { Text(if (target.folder) "폴더 안 앨범은 유지되고 내 앨범 최상위로 이동합니다." else "앨범과 앨범 안 곡 목록만 삭제합니다. 음원 파일은 삭제하지 않습니다.") }, confirmButton = { TextButton({ if (target.folder) viewModel.dissolveFolder(target.id) else viewModel.deleteAlbum(target.id); deleteTarget = null }) { Text("삭제") } }, dismissButton = { TextButton({ deleteTarget = null }) { Text("취소") } }) }
}

@Composable private fun SpecialAlbumCard(name: String, count: Int, icon: androidx.compose.ui.graphics.vector.ImageVector, open: () -> Unit) {
    ListItem(
        headlineContent = { Text(name) }, supportingContent = { Text("${count}곡 · 고정 시스템 앨범") },
        leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) },
        modifier = Modifier.clickable(onClick = open),
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
    )
}

@Composable private fun AlbumCard(album: UserAlbumEntity, modifier: Modifier = Modifier, dragModifier: Modifier = Modifier, viewModel: MainViewModel, open: () -> Unit, more: () -> Unit) {
    val tracks by viewModel.observeAlbumTracks(album.id).collectAsStateWithLifecycle(emptyList())
    Surface(modifier.fillMaxWidth().then(dragModifier).clickable(onClick = open), shape = RoundedCornerShape(18.dp), color = androidx.compose.ui.graphics.Color.Transparent) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            AlbumArtworkThumbnail(album, viewModel, Modifier.size(58.dp))
            Column(Modifier.weight(1f).padding(start = 10.dp)) { Text(album.name, style = MaterialTheme.typography.titleMedium); Text("${tracks.size}곡", style = MaterialTheme.typography.bodySmall) }
            IconButton(more) { Icon(Icons.Default.MoreVert, "앨범 더보기") }
        }
    }
}

@Composable private fun AlbumTile(album: UserAlbumEntity, viewModel: MainViewModel, dragModifier: Modifier = Modifier, open: () -> Unit, more: () -> Unit) {
    val tracks by viewModel.observeAlbumTracks(album.id).collectAsStateWithLifecycle(emptyList())
    Surface(Modifier.fillMaxWidth().then(dragModifier).clickable(onClick = open), shape = RoundedCornerShape(18.dp), color = androidx.compose.ui.graphics.Color.Transparent) {
        Column(Modifier.padding(10.dp)) {
            AlbumArtworkThumbnail(album, viewModel, Modifier.fillMaxWidth().padding(5.dp).aspectRatio(1f))
            Row(verticalAlignment = Alignment.CenterVertically) { Text(album.name, Modifier.weight(1f), maxLines = 1, style = MaterialTheme.typography.titleSmall); IconButton(more, Modifier.size(36.dp)) { Icon(Icons.Default.MoreVert, "앨범 더보기") } }; Text("${tracks.size}곡", style = MaterialTheme.typography.bodySmall)
        }
    }
}
@Composable fun AlbumArtworkThumbnail(album: UserAlbumEntity, viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val tracks by viewModel.observeAlbumTracks(album.id).collectAsStateWithLifecycle(emptyList())
    val artwork = album.artworkUri ?: tracks.firstOrNull()?.displayArtworkUri()
    if (artwork.isNullOrBlank()) {
        Box(modifier.clip(RoundedCornerShape(12.dp)).background(androidx.compose.ui.graphics.Color(0xFF62626A)), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.MusicNote, "앨범 커버 없음", tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(34.dp))
        }
    } else AsyncImage(artwork, null, modifier.clip(RoundedCornerShape(12.dp)), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
}

@Composable private fun FolderTile(folder: AlbumFolderEntity, members: List<UserAlbumEntity>, viewModel: MainViewModel, dragModifier: Modifier = Modifier, click: () -> Unit, more: () -> Unit) {
    Surface(Modifier.fillMaxWidth().then(dragModifier).clickable(onClick = click), shape = RoundedCornerShape(18.dp), color = androidx.compose.ui.graphics.Color.Transparent) {
        Column(Modifier.padding(12.dp)) { FolderArtworkGrid(folder, members, viewModel, Modifier.fillMaxWidth().padding(5.dp).aspectRatio(1f)); Spacer(Modifier.height(8.dp)); Row(verticalAlignment = Alignment.CenterVertically) { Text(folder.name, Modifier.weight(1f), maxLines = 1); IconButton(more, Modifier.size(36.dp)) { Icon(Icons.Default.MoreVert, "폴더 더보기") } }; Text("${members.size}개 앨범", style = MaterialTheme.typography.bodySmall) }
    }
}
@Composable private fun FolderArtworkGrid(folder: AlbumFolderEntity, members: List<UserAlbumEntity>, viewModel: MainViewModel, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primaryContainer)) {
        if (!folder.artworkUri.isNullOrBlank()) AsyncImage(folder.artworkUri, null, Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
        else if (folder.artworkUri == "") Icon(Icons.Default.MusicNote, null, Modifier.align(Alignment.Center).size(54.dp), tint = androidx.compose.ui.graphics.Color.White)
        else if (members.isEmpty()) Icon(Icons.Default.Folder, null, Modifier.align(Alignment.Center).size(54.dp), tint = MaterialTheme.colorScheme.primary)
        else {
            val size = (maxWidth - 18.dp) / 2
            members.take(4).forEachIndexed { index, album -> FolderAlbumThumb(album, viewModel, index, size) }
        }
    }
}
@Composable private fun BoxScope.FolderAlbumThumb(album: UserAlbumEntity, viewModel: MainViewModel, index: Int, size: Dp) {
    val tracks by viewModel.observeAlbumTracks(album.id).collectAsStateWithLifecycle(emptyList())
    val alignment = listOf(Alignment.TopStart, Alignment.TopEnd, Alignment.BottomStart, Alignment.BottomEnd)[index]
    AlbumArtworkThumbnail(album, viewModel, Modifier.align(alignment).padding(6.dp).size(size))
}

@Composable private fun FolderCard(folder: AlbumFolderEntity, members: List<UserAlbumEntity>, viewModel: MainViewModel, modifier: Modifier = Modifier, click: () -> Unit, more: () -> Unit) {
    Surface(modifier.fillMaxWidth().clickable(onClick = click), shape = RoundedCornerShape(20.dp), color = androidx.compose.ui.graphics.Color.Transparent) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            FolderArtworkGrid(folder, members, viewModel = viewModel, modifier = Modifier.size(58.dp))
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(folder.name, style = MaterialTheme.typography.titleMedium)
                Text("${members.size}개 앨범 · ${members.take(3).joinToString(" · ") { it.name }}", maxLines = 1, style = MaterialTheme.typography.bodySmall)
            }
            IconButton(more) { Icon(Icons.Default.MoreVert, "폴더 더보기") }
        }
    }
}

@Composable fun AlbumMenuSheet(target: AlbumMenuTarget, close: () -> Unit, rename: () -> Unit, chooseArtwork: () -> Unit, resetArtwork: () -> Unit, clearArtwork: () -> Unit, delete: () -> Unit) {
    ModalBottomSheet(onDismissRequest = close) {
        Text(target.name, Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.titleLarge)
        ListItem({ Text("이름 변경") }, leadingContent = { Icon(Icons.Default.Edit, null) }, modifier = Modifier.clickable { rename() })
        ListItem({ Text("갤러리에서 썸네일 선택") }, supportingContent = { Text("직접 고른 이미지를 사용합니다") }, leadingContent = { Icon(Icons.Default.PhotoLibrary, null) }, modifier = Modifier.clickable { chooseArtwork() })
        ListItem({ Text("썸네일 초기화") }, supportingContent = { Text(if (target.folder) "앨범 커버 콜라주로 돌아갑니다" else "첫 번째 곡 커버로 돌아갑니다") }, leadingContent = { Icon(Icons.Default.Refresh, null) }, modifier = Modifier.clickable { resetArtwork() })
        ListItem({ Text("썸네일 삭제") }, supportingContent = { Text("회색 배경의 흰 음표로 표시합니다") }, leadingContent = { Icon(Icons.Default.HideImage, null) }, modifier = Modifier.clickable { clearArtwork() })
        ListItem({ Text(if (target.folder) "폴더 삭제" else "앨범 삭제", color = MaterialTheme.colorScheme.error) }, leadingContent = { Icon(Icons.Default.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) }, modifier = Modifier.clickable { delete() })
        Spacer(Modifier.height(24.dp))
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
