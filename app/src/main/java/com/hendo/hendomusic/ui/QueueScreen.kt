@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.hendo.hendomusic.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import coil3.compose.AsyncImage
import com.hendo.hendomusic.MainViewModel
import com.hendo.hendomusic.data.TrackEntity
import com.hendo.hendomusic.data.displayArtworkUri
import com.hendo.hendomusic.playback.PlaybackService
import com.hendo.hendomusic.playback.PlaybackState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

private data class RemovedQueueItem(val item: MediaItem, val index: Int)

@Composable
fun ReorderableQueueScreen(
    state: PlaybackState,
    viewModel: MainViewModel,
    back: () -> Unit,
    addToAlbum: (TrackEntity) -> Unit,
    editTrack: (TrackEntity) -> Unit,
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val tracksById = remember(ui.tracks) { ui.tracks.associateBy { it.id } }
    val localQueue = remember { mutableStateListOf<MediaItem>() }
    var draggedId by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var pendingOrder by remember { mutableStateOf<List<String>?>(null) }
    var menuItem by remember { mutableStateOf<MediaItem?>(null) }
    var removed by remember { mutableStateOf<RemovedQueueItem?>(null) }
    var undoJob by remember { mutableStateOf<Job?>(null) }
    val listState = rememberLazyListState()
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    fun finishDrag() {
        val order = localQueue.map { it.mediaId }
        if (order != state.queue.map { it.mediaId }) {
            pendingOrder = order
            viewModel.player.reorderQueue(order)
        }
        draggedId = null
        dragOffset = 0f
    }
    fun removeItem(item: MediaItem) {
        val index = localQueue.indexOfFirst { it.mediaId == item.mediaId }
        if (index < 0) return
        undoJob?.cancel()
        localQueue.removeAt(index)
        pendingOrder = localQueue.map { it.mediaId }
        removed = RemovedQueueItem(item, index)
        viewModel.player.removeQueueItem(item.mediaId)
        undoJob = scope.launch { delay(2_000L); removed = null }
    }
    fun undoRemoval() {
        val value = removed ?: return
        undoJob?.cancel()
        localQueue.add(value.index.coerceIn(0, localQueue.size), value.item)
        val order = localQueue.map { it.mediaId }
        pendingOrder = order
        viewModel.player.restoreQueueItem(value.item, order)
        removed = null
    }

    LaunchedEffect(state.queue, draggedId, pendingOrder) {
        if (draggedId != null) return@LaunchedEffect
        val serviceOrder = state.queue.map { it.mediaId }
        pendingOrder?.let { expected ->
            if (serviceOrder == expected) pendingOrder = null else return@LaunchedEffect
        }
        if (localQueue.map { it.mediaId } != serviceOrder) {
            localQueue.clear(); localQueue.addAll(state.queue)
        }
    }
    LaunchedEffect(state.current?.mediaId) {
        val currentIndex = localQueue.indexOfFirst { it.mediaId == state.current?.mediaId }
        if (currentIndex >= 0 && !listState.isScrollInProgress) listState.scrollToItem(currentIndex)
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("현재 재생목록") },
            navigationIcon = { IconButton(back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") } },
            actions = {
                if (state.hasPreviousQueue) TextButton(viewModel.player::restorePreviousQueue) { Text("이전 불러오기") }
                TextButton(viewModel.player::clear) { Text("비우기") }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, scrolledContainerColor = Color.Transparent),
        )
        Text(
            "현재 ${state.current?.let { current -> localQueue.indexOfFirst { it.mediaId == current.mediaId } + 1 } ?: 0} / ${localQueue.size}곡",
            Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(Modifier.weight(1f)) {
            LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 72.dp)) {
                itemsIndexed(localQueue, key = { _, item -> item.mediaId }) { _, item ->
                    val dragging = draggedId == item.mediaId
                    val scale by animateFloatAsState(if (dragging) 1.025f else 1f, tween(30), label = "queueScale")
                    val elevation by animateDpAsState(if (dragging) 10.dp else 0.dp, tween(30), label = "queueElevation")
                    val dismissState = rememberSwipeToDismissBoxState(
                        confirmValueChange = { target ->
                            if (target == SwipeToDismissBoxValue.EndToStart) {
                                removeItem(item)
                                true
                            } else false
                        },
                    )
                    SwipeToDismissBox(
                        state = dismissState,
                        enableDismissFromStartToEnd = false,
                        backgroundContent = {
                            Box(
                                Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 2.dp)
                                    .clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.errorContainer),
                            )
                        },
                    ) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            // The dismiss background must stay hidden until the row moves.
                            // A transparent foreground exposed the red delete layer (and its
                            // trash icon) even while the row was idle.
                            color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp)
                                .purpleGlass(16).zIndex(if (dragging) 1f else 0f)
                                .shadow(elevation, RoundedCornerShape(14.dp))
                                .graphicsLayer { scaleX = scale; scaleY = scale; translationY = if (dragging) dragOffset else 0f },
                        ) {
                            Row(Modifier.heightIn(min = 72.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.DragHandle,
                                    "드래그하여 순서 변경",
                                    Modifier.size(48.dp).padding(12.dp).pointerInput(item.mediaId, localQueue.size) {
                                        detectDragGestures(
                                            onDragStart = { draggedId = item.mediaId; dragOffset = 0f; haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
                                            onDragCancel = ::finishDrag,
                                            onDragEnd = ::finishDrag,
                                            onDrag = { change, amount ->
                                                change.consume(); dragOffset += amount.y
                                                val rowHeight = 76.dp.toPx()
                                                if (abs(dragOffset) >= rowHeight * .55f) {
                                                    val current = localQueue.indexOfFirst { it.mediaId == item.mediaId }
                                                    val target = (current + if (dragOffset > 0) 1 else -1).coerceIn(localQueue.indices)
                                                    if (current != target) {
                                                        localQueue.add(target, localQueue.removeAt(current))
                                                        dragOffset -= if (dragOffset > 0) rowHeight else -rowHeight
                                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    }
                                                }
                                            },
                                        )
                                    },
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Row(
                                    Modifier.weight(1f).fillMaxHeight()
                                        .clickable { viewModel.player.playQueueItem(item.mediaId) },
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    QueueArtwork(item, tracksById[queueTrackId(item)])
                                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                        Text(item.mediaMetadata.title?.toString().orEmpty(), maxLines = 1)
                                        Text(item.mediaMetadata.artist?.toString().orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                    }
                                }
                                if (item.mediaId == state.current?.mediaId) Icon(Icons.Default.GraphicEq, "재생 중", tint = MaterialTheme.colorScheme.primary)
                                IconButton({ menuItem = item }) { Icon(Icons.Default.MoreVert, "더보기") }
                            }
                        }
                    }
                }
            }
            if (removed != null) {
                Surface(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.inverseSurface,
                ) {
                    Row(Modifier.padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("현재 재생목록에서 제외했습니다", Modifier.weight(1f), color = MaterialTheme.colorScheme.inverseOnSurface)
                        TextButton(::undoRemoval) { Text("취소") }
                    }
                }
            }
        }
    }

    menuItem?.let { item ->
        val track = tracksById[queueTrackId(item)]
        ModalBottomSheet(onDismissRequest = { menuItem = null }) {
            Text(item.mediaMetadata.title?.toString().orEmpty(), Modifier.padding(horizontal = 20.dp, vertical = 10.dp), style = MaterialTheme.typography.titleMedium)
            if (track != null) {
                ListItem({ Text("내 앨범에 추가") }, leadingContent = { Icon(Icons.Default.PlaylistAdd, null) }, modifier = Modifier.clickable { menuItem = null; addToAlbum(track) })
                ListItem({ Text("곡 정보 수정") }, leadingContent = { Icon(Icons.Default.Edit, null) }, modifier = Modifier.clickable { menuItem = null; editTrack(track) })
            }
            ListItem({ Text("현재 재생목록에서 제외") }, leadingContent = { Icon(Icons.Default.RemoveCircleOutline, null) }, modifier = Modifier.clickable { menuItem = null; removeItem(item) })
            Spacer(Modifier.navigationBarsPadding())
        }
    }
}

private fun queueTrackId(item: MediaItem): String = item.mediaMetadata.extras?.getString(PlaybackService.KEY_TRACK_ID).orEmpty()

@Composable
private fun QueueArtwork(item: MediaItem, track: TrackEntity?) {
    val uri = track?.displayArtworkUri() ?: item.mediaMetadata.artworkUri?.toString()
    var failed by remember(uri) { mutableStateOf(false) }
    Box(
        Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF64756B)),
        contentAlignment = Alignment.Center,
    ) {
        if (uri != null && !failed) {
            AsyncImage(
                model = uri,
                contentDescription = "앨범 커버",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                onError = { failed = true },
            )
        } else Icon(Icons.Default.MusicNote, null, tint = Color.White, modifier = Modifier.size(20.dp))
    }
}
