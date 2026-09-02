@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.hendo.hendomusic.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.media3.common.MediaItem
import com.hendo.hendomusic.MainViewModel
import com.hendo.hendomusic.playback.PlaybackState
import kotlin.math.abs

@Composable
fun ReorderableQueueScreen(state: PlaybackState, viewModel: MainViewModel, back: () -> Unit) {
    val localQueue = remember { mutableStateListOf<MediaItem>() }
    var draggedId by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val listState = rememberLazyListState()
    val haptics = LocalHapticFeedback.current

    LaunchedEffect(state.queue, draggedId) {
        if (draggedId == null && localQueue.map { it.mediaId } != state.queue.map { it.mediaId }) {
            localQueue.clear(); localQueue.addAll(state.queue)
        }
    }
    LaunchedEffect(state.current?.mediaId, localQueue.size) {
        val currentIndex = localQueue.indexOfFirst { it.mediaId == state.current?.mediaId }
        if (currentIndex >= 0) listState.scrollToItem(currentIndex)
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("현재 재생목록") },
            navigationIcon = { IconButton(back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") } },
            actions = { if (state.hasPreviousQueue) TextButton(viewModel.player::restorePreviousQueue) { Text("이전 불러오기") }; TextButton(viewModel.player::clear) { Text("비우기") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, scrolledContainerColor = Color.Transparent),
        )
        Text(
            "현재 ${state.current?.let { current -> localQueue.indexOfFirst { it.mediaId == current.mediaId } + 1 } ?: 0} / ${localQueue.size}곡",
            Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
            itemsIndexed(localQueue, key = { _, item -> item.mediaId }) { index, item ->
                val dragging = draggedId == item.mediaId
                val scale by animateFloatAsState(if (dragging) 1.025f else 1f, animationSpec = tween(30), label = "queueScale")
                val elevation by animateDpAsState(if (dragging) 10.dp else 0.dp, animationSpec = tween(30), label = "queueElevation")
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Color.Transparent,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 2.dp)
                        .purpleGlass(16)
                        .zIndex(if (dragging) 1f else 0f)
                        .shadow(elevation, RoundedCornerShape(14.dp))
                        .graphicsLayer { scaleX = scale; scaleY = scale; translationY = if (dragging) dragOffset else 0f },
                ) {
                    Row(Modifier.heightIn(min = 72.dp).clickable { viewModel.player.playQueueItem(item.mediaId) }, verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.DragHandle,
                            "드래그하여 순서 변경",
                            Modifier
                                .size(52.dp)
                                .padding(14.dp)
                                .pointerInput(item.mediaId, localQueue.size) {
                                    detectDragGestures(
                                        onDragStart = {
                                            draggedId = item.mediaId; dragOffset = 0f
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        },
                                        onDragCancel = { draggedId = null; dragOffset = 0f },
                                        onDragEnd = { draggedId = null; dragOffset = 0f },
                                        onDrag = { change, amount ->
                                            change.consume(); dragOffset += amount.y
                                            val rowHeight = 76.dp.toPx()
                                            if (abs(dragOffset) >= rowHeight * .55f) {
                                                val current = localQueue.indexOfFirst { it.mediaId == item.mediaId }
                                                val target = (current + if (dragOffset > 0) 1 else -1).coerceIn(localQueue.indices)
                                                if (current != target) {
                                                    localQueue.add(target, localQueue.removeAt(current))
                                                    viewModel.player.move(current, target)
                                                    dragOffset -= if (dragOffset > 0) rowHeight else -rowHeight
                                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                                }
                                            }
                                        },
                                    )
                                },
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.MusicNote, null, tint = MaterialTheme.colorScheme.primary)
                        }
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(item.mediaMetadata.title?.toString().orEmpty(), maxLines = 1)
                            Text(item.mediaMetadata.artist?.toString().orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                        if (item.mediaId == state.current?.mediaId) Icon(Icons.Default.GraphicEq, "재생 중", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 14.dp))
                    }
                }
            }
        }
    }
}
