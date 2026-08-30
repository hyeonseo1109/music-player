@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.hendo.hendomusic.ui

import android.Manifest
import android.os.Build
import android.provider.Settings
import androidx.media3.common.Player
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import coil3.compose.AsyncImage
import com.hendo.hendomusic.MainViewModel
import com.hendo.hendomusic.MainUiState
import com.hendo.hendomusic.data.*
import com.hendo.hendomusic.library.MetadataUpdate
import com.hendo.hendomusic.lyrics.SyncedLyricLine
import com.hendo.hendomusic.lyrics.LrcCodec
import com.hendo.hendomusic.playback.PlaybackState
import kotlin.math.roundToLong
import kotlin.math.abs
import kotlinx.coroutines.launch

@Composable
fun LuminaraApp(
    viewModel: MainViewModel,
    requestMediaPermission: () -> Unit,
    requestOnboardingPermission: (String, (Boolean) -> Unit) -> Unit,
    requestOverlay: () -> Unit,
    chooseTree: () -> Unit,
    chooseLrc: () -> Unit,
    choosePlaylist: () -> Unit,
    exportPlaylist: (UserAlbumEntity) -> Unit,
    onFloatingChanged: (Boolean) -> Unit,
    requestDelete: (TrackEntity) -> Unit,
    requestMetadataWrite: (TrackEntity, MetadataUpdate, (String) -> Unit) -> Unit,
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val playback by viewModel.player.state.collectAsStateWithLifecycle()
    val settingsLoaded by viewModel.settingsLoaded.collectAsStateWithLifecycle()
    // Do not render the default onboarding state while DataStore is resolving saved permissions.
    if (!settingsLoaded) {
        PurpleAtmosphere { }
        return
    }
    if (!ui.settings.onboardingDone) {
        OnboardingScreen(requestOnboardingPermission) { viewModel.completeOnboarding() }
        return
    }
    val nav = rememberNavController()
    val currentRoute = nav.currentBackStackEntryAsState().value?.destination?.route
    val showChrome = currentRoute == null || currentRoute in setOf("library", "albums", "settings")
    PurpleAtmosphere {
    Scaffold(
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onBackground,
        bottomBar = {
            if (showChrome) Column {
                if (playback.current != null) MiniPlayer(playback, { nav.navigate("player") }, viewModel.player::toggle, viewModel.player::next)
                NavigationBar(containerColor = Color(0xE606030C), tonalElevation = 0.dp) {
                    NavItem(nav, currentRoute, "library", "전체 곡", Icons.Default.LibraryMusic)
                    NavItem(nav, currentRoute, "albums", "내 앨범", Icons.Default.Album)
                    NavItem(nav, currentRoute, "settings", "설정", Icons.Default.Settings)
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = "library", modifier = Modifier.padding(padding)) {
            composable("library") { LibraryScreen(ui, viewModel, nav, requestDelete) }
            composable("albums") { AlbumOrganizerScreen(ui, viewModel, { id -> nav.navigate("album/$id") }) { kind -> nav.navigate("special/$kind") } }
            composable("album/{albumId}") { back ->
                val id = back.arguments?.getString("albumId")?.toLongOrNull()
                val album = ui.albums.firstOrNull { it.id == id }
                val tracks by remember(id) { if (id == null) kotlinx.coroutines.flow.flowOf(emptyList()) else viewModel.observeAlbumTracks(id) }.collectAsStateWithLifecycle(emptyList())
                val localTracks = remember { mutableStateListOf<TrackEntity>() }
                var draggedTrackId by remember { mutableStateOf<String?>(null) }
                var dragOffset by remember { mutableFloatStateOf(0f) }
                val haptics = LocalHapticFeedback.current
                LaunchedEffect(tracks, draggedTrackId) { if (draggedTrackId == null && localTracks.map { it.id } != tracks.map { it.id }) { localTracks.clear(); localTracks.addAll(tracks) } }
                Column(Modifier.fillMaxSize()) {
                    TopAppBar({ Text(album?.name ?: "내 앨범") }, navigationIcon = { IconButton({ nav.popBackStack() }) { Icon(Icons.Default.ArrowBack, "뒤로") } })
                    if (tracks.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("이 앨범에 담긴 곡이 없습니다.") }
                    else LazyColumn {
                        itemsIndexed(localTracks, key = { _, track -> track.id }) { _, track ->
                            val dragging = draggedTrackId == track.id
                            Row(Modifier.fillMaxWidth().zIndex(if (dragging) 1f else 0f).graphicsLayer { translationY = if (dragging) dragOffset else 0f }.pointerInput(track.id, localTracks.size) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { draggedTrackId = track.id; dragOffset = 0f; haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
                                    onDragCancel = { draggedTrackId = null; dragOffset = 0f },
                                    onDragEnd = { draggedTrackId = null; dragOffset = 0f; id?.let { viewModel.reorderAlbumTracks(it, localTracks.map { t -> t.id }) } },
                                    onDrag = { change, amount ->
                                        change.consume(); dragOffset += amount.y
                                        if (abs(dragOffset) > 48.dp.toPx()) {
                                            val from = localTracks.indexOfFirst { it.id == track.id }
                                            val to = (from + if (dragOffset > 0) 1 else -1).coerceIn(localTracks.indices)
                                            if (from != to) { localTracks.add(to, localTracks.removeAt(from)); dragOffset = 0f; haptics.performHapticFeedback(HapticFeedbackType.LongPress) }
                                        }
                                    },
                                )
                            }, verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.DragHandle, "길게 눌러 순서 변경", Modifier.padding(start = 8.dp))
                                MusicRow(track, false, { viewModel.play(track, localTracks) }, {}, {})
                            }
                        }
                    }
                }
            }
            composable("special/{kind}") { back ->
                val kind = back.arguments?.getString("kind")
                val name = if (kind == "favorites") "좋아요한 곡" else "많이 들은 곡"
                val specialTracks = if (kind == "favorites") ui.tracks.filter { it.isFavorite } else ui.tracks.filter { it.playCount > 0 }.sortedByDescending { it.playCount }
                SpecialAlbumTracksScreen(name, specialTracks, viewModel) { nav.popBackStack() }
            }
            composable("settings") { SettingsScreen(ui, viewModel, requestMediaPermission, requestOverlay, chooseTree, choosePlaylist, exportPlaylist, onFloatingChanged) }
            composable("player") { NowPlayingScreen(playback, viewModel, nav, ui) }
            composable("nowLyrics/{trackId}") { back ->
                NowPlayingLyricsScreen(back.arguments?.getString("trackId").orEmpty(), playback.positionMs, viewModel) { nav.popBackStack() }
            }
            composable("queue") { ReorderableQueueScreen(playback, viewModel) { nav.popBackStack() } }
            composable("lyricsSearch/{trackId}") { back ->
                val trackId = back.arguments?.getString("trackId").orEmpty()
                ui.tracks.find { it.id == trackId }?.let { track ->
                    LyricsSearchScreen(track, viewModel, { nav.popBackStack() }) { nav.navigate("lyrics/$trackId") }
                }
            }
            composable("lyrics/{trackId}") { back ->
                val trackId = back.arguments?.getString("trackId").orEmpty()
                LyricsEditorScreen(trackId, viewModel, chooseLrc, { nav.popBackStack() }) { nav.navigate("sync/$trackId") }
            }
            composable("sync/{trackId}") { back ->
                LyricsSyncScreen(back.arguments?.getString("trackId").orEmpty(), playback, viewModel) { nav.popBackStack() }
            }
            composable("metadata/{trackId}") { back ->
                MetadataEditorScreen(back.arguments?.getString("trackId").orEmpty(), ui, viewModel, requestMetadataWrite) { nav.popBackStack() }
            }
        }
    } }
}

@Composable private fun OnboardingScreen(requestPermission: (String, (Boolean) -> Unit) -> Unit, done: () -> Unit) {
    val context = LocalContext.current
    val audioGranted = hasAudioPermission(context)
    val notificationGranted = hasNotificationPermission(context)
    // Overlay is a feature-specific special access, not an app-entry requirement. It is asked
    // only when the user enables floating lyrics in Settings, never every time the app launches.
    // Rejected runtime audio/notification permissions deliberately keep onboarding incomplete.
    LaunchedEffect(audioGranted, notificationGranted) {
        if (audioGranted && notificationGranted) done()
    }
    val initialStep = remember {
        when {
            !audioGranted -> 0
            !notificationGranted -> 1
            else -> 0
        }
    }
    var step by remember { mutableIntStateOf(initialStep) }
    var permissionDenied by remember { mutableStateOf(false) }
    val items = listOf(
        Triple(Icons.Default.LibraryMusic, "음악 및 오디오", "휴대폰의 로컬 음악 라이브러리를 읽습니다."),
        Triple(Icons.Default.Notifications, "알림 및 미디어 컨트롤", "백그라운드 재생과 잠금화면 제어에 필요합니다."),
    )
    PurpleAtmosphere { Box(Modifier.fillMaxSize().padding(28.dp)) {
        Column(Modifier.align(Alignment.Center)) {
            Icon(items[step].first, null, Modifier.size(72.dp), Color(0xFFB69CFF))
            Spacer(Modifier.height(28.dp)); Text("HENDOMUSIC", style = MaterialTheme.typography.labelLarge, color = Color(0xFFB69CFF))
            Text(items[step].second, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(Modifier.height(12.dp)); Text(items[step].third, color = Color(0xFFCEC3DD))
            Spacer(Modifier.height(32.dp))
            Button(onClick = {
                when (step) {
                    0 -> requestPermission(if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE) { granted ->
                        permissionDenied = !granted
                        if (granted) step++
                    }
                    1 -> if (Build.VERSION.SDK_INT >= 33) requestPermission(Manifest.permission.POST_NOTIFICATIONS) { granted ->
                        permissionDenied = !granted
                        if (granted) step++
                    } else step++
                    else -> done()
                }
            }, Modifier.fillMaxWidth().height(54.dp)) { Text("권한 요청 후 다음") }
            if (permissionDenied) Text("권한을 허용해야 이 단계를 진행할 수 있습니다.", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 10.dp))
        }
    } }
}

@Composable private fun RowScope.NavItem(nav: NavHostController, current: String?, route: String, label: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    NavigationBarItem(selected = current == route, onClick = { nav.navigate(route) { launchSingleTop = true; popUpTo("library") } }, icon = { Icon(icon, null) }, label = { Text(label) })
}

@Composable private fun LibraryScreen(ui: com.hendo.hendomusic.MainUiState, vm: MainViewModel, nav: NavHostController, requestDelete: (TrackEntity) -> Unit) {
    var sortOpen by remember { mutableStateOf(false) }
    var menuTrack by remember { mutableStateOf<TrackEntity?>(null) }
    var selectedAlbumPicker by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var deleteSelectedConfirm by remember { mutableStateOf(false) }
    val selectedTracks = ui.visibleTracks.filter { it.id in selectedIds }
    fun toggleSelection(track: TrackEntity) {
        selectedIds = if (track.id in selectedIds) selectedIds - track.id else selectedIds + track.id
    }
    fun playSelectedTracks() {
        selectedTracks.firstOrNull()?.let { vm.play(it, selectedTracks) }
        selectedIds = emptySet()
    }
    // The search results are debounced. Keep the IME's composition and cursor locally
    // instead of feeding the delayed value back into a Korean text field.
    var searchField by remember { mutableStateOf(TextFieldValue(ui.query, TextRange(ui.query.length))) }
    LaunchedEffect(ui.query) {
        if (ui.query != searchField.text) searchField = TextFieldValue(ui.query, TextRange(ui.query.length))
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("내 음악", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconButton(onClick = { vm.scan() }) { Icon(Icons.Default.Refresh, "다시 검색") }
        }
        OutlinedTextField(value = searchField, onValueChange = { value -> searchField = value; vm.setQuery(value.text) }, leadingIcon = { Icon(Icons.Default.Search, null) }, placeholder = { Text("곡, 가수, 앨범 또는 초성 검색") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), shape = RoundedCornerShape(18.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            if (selectedTracks.isNotEmpty()) {
                Text("${selectedTracks.size}곡 선택됨", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = ::playSelectedTracks) { Icon(Icons.Default.PlayArrow, null); Text("선택 재생") }
                    TextButton(onClick = { selectedTracks.forEach(vm.player::append); selectedIds = emptySet() }) { Icon(Icons.Default.PlaylistAdd, null); Text("재생목록 추가") }
                    IconButton(onClick = { selectedAlbumPicker = true }) { Icon(Icons.Default.Add, "내 앨범에 추가") }
                    IconButton(onClick = { deleteSelectedConfirm = true }) { Icon(Icons.Default.DeleteOutline, "선택한 곡 삭제") }
                    IconButton(onClick = { selectedIds = emptySet() }) { Icon(Icons.Default.Close, "선택 해제") }
                }
            } else {
                Text("${ui.visibleTracks.size}곡", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Box { TextButton(onClick = { sortOpen = true }) { Icon(Icons.Default.Sort, null); Text(sortLabel(ui.settings.sort)) }
                    DropdownMenu(sortOpen, { sortOpen = false }) { listOf("TITLE" to "이름 순", "RECENT" to "최근 추가", "PLAYED" to "최근 들은 순", "COUNT" to "많이 들은 순").forEach { (k, v) -> DropdownMenuItem({ Text(v) }, { vm.setSort(k); sortOpen = false }) } }
                }
            }
        }
        if (ui.scanning) LinearProgressIndicator(Modifier.fillMaxWidth())
        ui.scanMessage?.let { Text(it, Modifier.padding(horizontal = 18.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall) }
        if (!ui.scanning && ui.tracks.isEmpty()) EmptyLibrary(vm::scan)
        else Box(Modifier.weight(1f)) {
            val listState = rememberLazyListState()
            LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 12.dp)) { items(ui.visibleTracks, key = { it.id }) { track ->
            MusicRow(
                track = track,
                selected = track.id in selectedIds,
                play = { if (selectedIds.isEmpty()) vm.play(track, ui.visibleTracks) else toggleSelection(track) },
                select = { toggleSelection(track) },
                menu = { menuTrack = track },
            )
            } }
            if (ui.settings.sort == "TITLE") NameIndexRail(ui.visibleTracks, listState)
        }
    }
    menuTrack?.let { TrackMenu(it, ui, vm, nav, requestDelete, selectedTracks.ifEmpty { ui.visibleTracks }, afterPlay = { if (selectedTracks.isNotEmpty()) selectedIds = emptySet() }) { menuTrack = null } }
    if (selectedAlbumPicker) AlertDialog(onDismissRequest = { selectedAlbumPicker = false }, title = { Text("선택한 곡을 내 앨범에 추가") }, text = { Column { if (ui.albums.isEmpty()) Text("아직 내 앨범이 없습니다.") else ui.albums.forEach { album -> TextButton({ selectedTracks.forEach { vm.addToAlbum(album.id, it.id) }; selectedIds = emptySet(); selectedAlbumPicker = false }) { Text(album.name) } } } }, confirmButton = { TextButton({ vm.createAlbum("새 앨범"); selectedAlbumPicker = false }) { Icon(Icons.Default.Add, null); Text("앨범 추가") } }, dismissButton = { TextButton({ selectedAlbumPicker = false }) { Text("취소") } })
    if (deleteSelectedConfirm) AlertDialog(onDismissRequest = { deleteSelectedConfirm = false }, title = { Text("선택한 ${selectedTracks.size}곡을 삭제할까요?") }, text = { Text("기기 음악 파일도 삭제됩니다.") }, confirmButton = { TextButton({ vm.deleteSelectedTracks(selectedTracks.map { it.id }); selectedIds = emptySet(); deleteSelectedConfirm = false }) { Text("삭제") } }, dismissButton = { TextButton({ deleteSelectedConfirm = false }) { Text("취소") } })
}

@Composable private fun BoxScope.NameIndexRail(tracks: List<TrackEntity>, state: androidx.compose.foundation.lazy.LazyListState) {
    val scope = rememberCoroutineScope()
    val letters = listOf("#", "1", "ㄱ", "ㄴ", "ㄷ", "ㄹ", "ㅁ", "ㅂ", "ㅅ", "ㅇ", "ㅈ", "ㅊ", "ㅋ", "ㅌ", "ㅍ", "ㅎ", "A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M", "N", "O", "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z", "🌐")
    var selected by remember { mutableStateOf<String?>(null) }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(selected) { if (selected != null) { visible = true; kotlinx.coroutines.delay(3_000); if (selected != null) { selected = null; visible = false } } }
    fun jump(letter: String) { selected = letter; val target = if (letter == "1") "#" else letter; val i = tracks.indexOfFirst { indexLabel(it.title) == target }; if (i >= 0) scope.launch { state.scrollToItem(i) } }
    Box(Modifier.align(Alignment.CenterEnd).padding(end = 5.dp).pointerInput(Unit) { detectTapGestures(onPress = { visible = true; tryAwaitRelease() }) }) {
        selected?.let { Text(it, Modifier.align(Alignment.CenterStart).offset(x = (-58).dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(16.dp)).padding(12.dp), style = MaterialTheme.typography.headlineSmall) }
        if (visible || state.isScrollInProgress) Column(Modifier.background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .78f), RoundedCornerShape(20.dp)).padding(vertical = 6.dp).pointerInput(letters) { detectDragGestures(onDragStart = { offset -> jump(letters[(offset.y / size.height * letters.size).toInt().coerceIn(0, letters.lastIndex)]) }, onDrag = { change, _ -> change.consume(); jump(letters[(change.position.y / size.height * letters.size).toInt().coerceIn(0, letters.lastIndex)]) }, onDragEnd = { selected = null }, onDragCancel = { selected = null }) }) {
            letters.forEach { letter -> Text(letter, Modifier.clickable { jump(letter) }.padding(horizontal = 8.dp, vertical = 1.dp), style = MaterialTheme.typography.labelSmall) }
        }
    }
}
private fun indexLabel(title: String): String { val c = title.trim().firstOrNull() ?: return "#"; return when { c.isDigit() || !c.isLetter() -> "#"; c in 'A'..'Z' || c in 'a'..'z' -> c.uppercaseChar().toString(); c in '가'..'깋' -> "ㄱ"; c in '나'..'닣' -> "ㄴ"; c in '다'..'딯' -> "ㄷ"; c in '라'..'맇' -> "ㄹ"; c in '마'..'밓' -> "ㅁ"; c in '바'..'빟' -> "ㅂ"; c in '사'..'앃' -> "ㅅ"; c in '아'..'잏' -> "ㅇ"; c in '자'..'짛' -> "ㅈ"; c in '차'..'칳' -> "ㅊ"; c in '카'..'킿' -> "ㅋ"; c in '타'..'팋' -> "ㅌ"; c in '파'..'핗' -> "ㅍ"; c in '하'..'힣' -> "ㅎ"; else -> "🌐" } }

private fun sortLabel(sort: String) = when(sort) { "RECENT" -> "최근 추가"; "PLAYED" -> "최근 들은 순"; "COUNT" -> "많이 들은 순"; else -> "이름 순" }

@Composable private fun EmptyLibrary(scan: () -> Unit) = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Default.GraphicEq, null, Modifier.size(64.dp), MaterialTheme.colorScheme.primary); Text("음악이 아직 없습니다", style = MaterialTheme.typography.titleLarge); Text("권한을 허용한 뒤 라이브러리를 검색하세요."); Button(scan) { Text("음악 검색") } }
}

@Composable private fun SpecialAlbumTracksScreen(name: String, tracks: List<TrackEntity>, viewModel: MainViewModel, back: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(name) },
            navigationIcon = { IconButton(back) { Icon(Icons.Default.ArrowBack, "뒤로") } },
        )
        if (tracks.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("아직 곡이 없습니다.") }
        } else {
            LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                items(tracks, key = { it.id }) { track ->
                    MusicRow(track, false, { viewModel.play(track, tracks) }, {}, {})
                }
            }
        }
    }
}

@Composable private fun MusicRow(track: TrackEntity, selected: Boolean, play: () -> Unit, select: () -> Unit, menu: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = .52f) else Color.Transparent).pointerInput(track.id, selected) { detectTapGestures(onTap = { play() }, onLongPress = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); select() }) }.padding(horizontal = 16.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        if (selected) Icon(Icons.Default.CheckCircle, "선택됨", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 8.dp))
        Artwork(track.displayArtworkUri(), 54)
        Column(Modifier.weight(1f).padding(start = 12.dp)) { Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold); Text(track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        IconButton(menu) { Icon(Icons.Default.MoreVert, "곡 메뉴") }
    }
}

@Composable private fun Artwork(uri: String?, size: Int) {
    Box(Modifier.size(size.dp).clip(RoundedCornerShape(14.dp)).background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.surfaceVariant))).border(1.dp, Color(0x669B4DFF), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
        if (uri != null) AsyncImage(uri, null, Modifier.fillMaxSize()) else Icon(Icons.Default.MusicNote, null, tint = MaterialTheme.colorScheme.primary)
    }
}

@Composable private fun TrackMenu(track: TrackEntity, ui: com.hendo.hendomusic.MainUiState, vm: MainViewModel, nav: NavHostController, requestDelete: (TrackEntity) -> Unit, playQueue: List<TrackEntity>, afterPlay: () -> Unit, close: () -> Unit) {
    var albumPicker by remember { mutableStateOf(false) }
    var deleteConfirm by remember { mutableStateOf(false) }
    ModalBottomSheet(close) { Column(Modifier.padding(bottom = 28.dp)) {
        ListItem(headlineContent = { Text(track.title, fontWeight = FontWeight.Bold) }, supportingContent = { Text(track.artist) }, leadingContent = { Artwork(track.displayArtworkUri(), 48) })
        MenuLine(Icons.Default.PlayArrow, "듣기") { vm.play(track, playQueue); afterPlay(); close() }
        MenuLine(Icons.Default.SkipNext, "다음 곡으로 재생") { vm.player.playNext(track); close() }
        MenuLine(Icons.Default.PlaylistAdd, "현재 재생목록에 추가") { vm.player.append(track); close() }
        MenuLine(Icons.Default.Album, "내 앨범에 추가") { albumPicker = true }
        MenuLine(Icons.Default.Edit, "곡 정보 수정하기") { close(); nav.navigate("metadata/${track.id}") }
        MenuLine(Icons.Default.Lyrics, "가사 검색") { close(); nav.navigate("lyricsSearch/${track.id}") }
        MenuLine(Icons.Default.EditNote, "가사 직접 입력") { close(); vm.stageLyrics(null); nav.navigate("lyrics/${track.id}") }
        MenuLine(Icons.Default.DeleteOutline, "삭제") { deleteConfirm = true }
    } }
    if (albumPicker) AlertDialog(onDismissRequest = { albumPicker = false }, title = { Text("내 앨범에 추가") }, text = { Column { if (ui.albums.isEmpty()) Text("아직 내 앨범이 없습니다. 오른쪽 위에서 바로 새 앨범을 만들 수 있어요.") else ui.albums.forEach { album -> TextButton({ vm.addToAlbum(album.id, track.id); albumPicker = false; close() }) { Text(album.name) } } } }, confirmButton = { TextButton({ vm.createAlbum("새 앨범"); albumPicker = false }) { Icon(Icons.Default.Add, null); Text("앨범 추가") } }, dismissButton = { TextButton({ albumPicker = false }) { Text("취소") } })
    if (deleteConfirm) AlertDialog(
        onDismissRequest = { deleteConfirm = false }, title = { Text("이 음악 파일을 기기에서 삭제하시겠습니까?") },
        text = { Text("파일 자체가 삭제되며 다른 음악 앱에서도 사라질 수 있습니다.") },
        confirmButton = { TextButton({ deleteConfirm = false; close(); requestDelete(track) }) { Text("삭제", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton({ deleteConfirm = false }) { Text("취소") } },
    )
}

@Composable private fun MenuLine(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, click: () -> Unit) = ListItem({ Text(text) }, leadingContent = { Icon(icon, null) }, modifier = Modifier.clickable(onClick = click))

@Composable private fun MetadataDialog(track: TrackEntity, vm: MainViewModel, close: () -> Unit) {
    var title by remember { mutableStateOf(track.title) }; var artist by remember { mutableStateOf(track.artist) }; var album by remember { mutableStateOf(track.album) }; var albumArtist by remember { mutableStateOf(track.albumArtist.orEmpty()) }; var message by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = close, title = { Text("곡 정보 수정") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedTextField(title, { title = it }, label = { Text("곡 제목") }); OutlinedTextField(artist, { artist = it }, label = { Text("아티스트") }); OutlinedTextField(album, { album = it }, label = { Text("앨범") }); OutlinedTextField(albumArtist, { albumArtist = it }, label = { Text("앨범 아티스트") }); Text("Android의 Scoped Storage 승인 흐름을 사용하며, 공급자가 태그 쓰기를 막으면 저장되지 않습니다.", style = MaterialTheme.typography.bodySmall); message?.let { Text(it) } } }, confirmButton = { TextButton({ vm.updateMetadata(track, title, artist, album, albumArtist.ifBlank { null }) { message = it } }) { Text("저장") } }, dismissButton = { TextButton(close) { Text("취소") } })
}

@Composable private fun MiniPlayer(state: PlaybackState, open: () -> Unit, toggle: () -> Unit, next: () -> Unit) {
    val item = state.current ?: return
    Surface(color = Color.Transparent, modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 5.dp).purpleGlass(18).clickable(onClick = open)) { Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) { Artwork(item.mediaMetadata.artworkUri?.toString(), 46); Column(Modifier.weight(1f).padding(horizontal = 10.dp)) { Text(item.mediaMetadata.title?.toString().orEmpty(), maxLines = 1); Text(item.mediaMetadata.artist?.toString().orEmpty(), maxLines = 1, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }; FilledIconButton(toggle, Modifier.size(42.dp)) { Icon(if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null) }; IconButton(next) { Icon(Icons.Default.SkipNext, null) } } }
}

@Composable private fun NowPlayingScreen(state: PlaybackState, vm: MainViewModel, nav: NavHostController, ui: MainUiState) {
    val tracks = ui.tracks
    val item = state.current
    var moreOpen by remember { mutableStateOf(false) }
    var lyricsMode by remember { mutableStateOf(false) }
    var repeatOptions by remember { mutableStateOf(false) }
    var addAlbumOpen by remember { mutableStateOf(false) }
    var pendingLoopStart by remember(item?.mediaId) { mutableStateOf<Long?>(null) }
    val configuration = LocalConfiguration.current
    val compactLandscape = configuration.screenWidthDp > configuration.screenHeightDp
    val sectionGap = if (compactLandscape) 10.dp else 20.dp
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = if (compactLandscape) 12.dp else 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth().padding(bottom = if (lyricsMode) sectionGap else 0.dp), verticalAlignment = Alignment.CenterVertically) { IconButton({ nav.popBackStack() }) { Icon(Icons.Default.KeyboardArrowDown, null) }; if (lyricsMode) { Row(Modifier.weight(1f).clickable { lyricsMode = false }, verticalAlignment = Alignment.CenterVertically) { Artwork(item?.mediaMetadata?.artworkUri?.toString(), 56); Column(Modifier.padding(start = 12.dp)) { Text(item?.mediaMetadata?.title?.toString().orEmpty(), maxLines = 1, fontWeight = FontWeight.Bold); Text(item?.mediaMetadata?.artist?.toString().orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) } } } else Text("지금 재생 중", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium); Box { IconButton({ moreOpen = true }) { Icon(Icons.Default.MoreVert, "더보기") }; DropdownMenu(moreOpen, { moreOpen = false }) { val id = item?.mediaMetadata?.extras?.getString("track_id"); DropdownMenuItem({ Text("곡 정보·앨범 커버 변경") }, { moreOpen = false; id?.let { nav.navigate("metadata/$it") } }); DropdownMenuItem({ Text("가사 검색") }, { moreOpen = false; id?.let { nav.navigate("lyricsSearch/$it") } }); DropdownMenuItem({ Text("가사 직접 입력/수정") }, { moreOpen = false; id?.let { nav.navigate("lyrics/$it") } }); DropdownMenuItem({ Text("가사 싱크 편집") }, { moreOpen = false; id?.let { nav.navigate("sync/$it") } }) } } }
        val track = tracks.find { it.id == item?.mediaMetadata?.extras?.getString("track_id") }
        if (!lyricsMode) {
            Column(
                Modifier.weight(1f).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(sectionGap))
                Box(Modifier.clickable { lyricsMode = true }) { Artwork(item?.mediaMetadata?.artworkUri?.toString(), if (compactLandscape) 120 else 200) }
                Spacer(Modifier.height(sectionGap))
                Text(item?.mediaMetadata?.title?.toString() ?: "재생 중인 곡 없음", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(item?.mediaMetadata?.artist?.toString().orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                // Cover mode keeps the active/next lyrics below the metadata.
                Spacer(Modifier.height(sectionGap))
                if (ui.settings.coverLyricsPreview) track?.let { NowPlayingLyricsPanel(it.id, state.positionMs, vm, compact = compactLandscape, onClick = { lyricsMode = true }) }
            }
        } else {
            track?.let { NowPlayingLyricsPanel(it.id, state.positionMs, vm, expanded = true, modifier = Modifier.weight(1f).fillMaxWidth()) }
        }
        // The playback controls are outside the weighted content region, so they remain pinned
        // to the bottom even when no lyrics have been registered.
        Row { IconButton({ track?.let { vm.toggleFavorite(it.id) } }) { Icon(if(track?.isFavorite == true) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "좋아요") }; IconButton({ addAlbumOpen = true }) { Icon(Icons.Default.Add, "내 앨범에 추가") }; IconButton({ nav.navigate("queue") }) { Icon(Icons.Default.QueueMusic, "현재 재생목록") } }
        Spacer(Modifier.height(sectionGap)); Slider(value = state.positionMs.toFloat().coerceAtMost(state.durationMs.toFloat().coerceAtLeast(1f)), onValueChange = { vm.player.seekTo(it.roundToLong()) }, valueRange = 0f..state.durationMs.toFloat().coerceAtLeast(1f), modifier = Modifier.height(28.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(formatTime(state.positionMs)); Text(formatTime(state.durationMs)) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly) {
            IconButton(vm.player::toggleShuffle) { Icon(Icons.Default.Shuffle, null, tint = if(state.shuffle) MaterialTheme.colorScheme.primary else LocalContentColor.current) }
            IconButton(vm.player::previous) { Icon(Icons.Default.SkipPrevious, null, Modifier.size(36.dp)) }
            FilledIconButton(vm.player::toggle, Modifier.size(70.dp)) { Icon(if(state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null, Modifier.size(36.dp)) }
            IconButton(vm.player::next) { Icon(Icons.Default.SkipNext, null, Modifier.size(36.dp)) }
            IconButton({ repeatOptions = !repeatOptions }) { Icon(if(state.repeatMode == 1) Icons.Default.Repeat else if(state.repeatMode == 2) Icons.Default.RepeatOne else Icons.Default.Repeat, "반복 설정", tint = if(state.repeatMode != 0) MaterialTheme.colorScheme.primary else LocalContentColor.current) }
        }
        val loop = state.loopRange
        if (repeatOptions) Surface(Modifier.fillMaxWidth().padding(top = 10.dp), color = if (loop != null) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (loop == null) "A-B 구간 반복" else "반복 A ${formatTime(loop.startMs)}  ·  B ${formatTime(loop.endMs)}", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(Player.REPEAT_MODE_OFF to "반복 안 함", Player.REPEAT_MODE_ONE to "한 곡 반복", Player.REPEAT_MODE_ALL to "전체 반복").forEach { (mode, label) ->
                        FilterChip(state.repeatMode == mode, { vm.player.setRepeatMode(mode) }, { Text(label) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton({ pendingLoopStart = state.positionMs }) { Text(if (pendingLoopStart == null) "시작 지점 설정" else "A ${formatTime(pendingLoopStart!!)}") }
                    Button({ pendingLoopStart?.let { vm.player.setLoop(it, state.positionMs); pendingLoopStart = null } }, enabled = pendingLoopStart != null) { Text("끝 지점 설정") }
                    if (loop != null) TextButton(vm.player::clearLoop) { Text("반복 해제") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(3_000L to "이전 3초", 5_000L to "이전 5초", 10_000L to "이전 10초").forEach { (duration, label) ->
                        TextButton({ vm.player.loopPrevious(duration) }) { Text(label) }
                    }
                }
            }
        }
        if (addAlbumOpen && track != null) AlertDialog(onDismissRequest = { addAlbumOpen = false }, title = { Text("내 앨범에 추가") }, text = { Column { if (ui.albums.isEmpty()) Text("아직 내 앨범이 없습니다.") else ui.albums.forEach { album -> TextButton({ vm.addToAlbum(album.id, track.id); addAlbumOpen = false }) { Text(album.name) } } } }, confirmButton = { TextButton({ vm.createAlbum("새 앨범"); addAlbumOpen = false }) { Icon(Icons.Default.Add, null); Text("앨범 추가") } }, dismissButton = { TextButton({ addAlbumOpen = false }) { Text("취소") } })
    } }
}

@Composable private fun NowPlayingLyricsScreen(trackId: String, positionMs: Long, vm: MainViewModel, back: () -> Unit) {
    val lyrics by remember(trackId) { vm.observeLyrics(trackId) }
        .collectAsStateWithLifecycle(initialValue = "" to emptyList())
    Scaffold(topBar = { TopAppBar({ Text("가사") }, navigationIcon = { IconButton(back) { Icon(Icons.Default.ArrowBack, "뒤로") } }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (lyrics.first.isBlank()) "등록된 가사가 없습니다." else if (lyrics.second.isEmpty()) lyrics.first else lyrics.second.joinToString("\n") { if (it.startTimeMs <= positionMs) "♪ ${it.text}" else it.text }, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable private fun NowPlayingLyricsPanel(trackId: String, positionMs: Long, vm: MainViewModel, expanded: Boolean = false, compact: Boolean = false, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val lyrics by remember(trackId) { vm.observeLyrics(trackId) }
        .collectAsStateWithLifecycle(initialValue = "" to emptyList())
    val plain = lyrics.first
    val synced = lyrics.second
    if (plain.isBlank()) {
        Surface(modifier.fillMaxWidth().then(if (expanded) Modifier.fillMaxHeight() else Modifier.heightIn(min = if (compact) 60.dp else 80.dp, max = if (compact) 82.dp else 110.dp)).padding(top = 8.dp).purpleGlass(18), color = Color.Transparent, shape = RoundedCornerShape(18.dp)) {
            Text("가사가 등록되지 않았습니다.", Modifier.padding(18.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val active = if (synced.isNotEmpty()) LrcCodec.activeIndex(synced, positionMs).coerceAtLeast(0) else -1
    Surface(modifier.fillMaxWidth().then(if (expanded) Modifier.fillMaxHeight() else Modifier.heightIn(min = if (compact) 72.dp else 96.dp, max = if (compact) 108.dp else 150.dp)).purpleGlass(18).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier), color = Color.Transparent, shape = RoundedCornerShape(18.dp)) {
        if (active >= 0 && !expanded) Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val stamp = synced[active].startTimeMs
            val block = synced.drop(active).takeWhile { it.startTimeMs == stamp }
            block.forEach { line -> Text(line.text, Modifier.fillMaxWidth().padding(bottom = 4.dp), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            synced.getOrNull(active + block.size)?.let { Text(it.text, Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge) }
        } else if (synced.isNotEmpty()) {
            val listState = rememberLazyListState()
            var userPinnedScroll by remember(trackId) { mutableStateOf(false) }
            var programmaticScroll by remember(trackId) { mutableStateOf(false) }
            // Entering lyrics mode starts in follow-playback mode. A direct user scroll opts out
            // until this panel is dismissed; returning from cover mode creates a fresh panel and
            // therefore resumes following the active lyric.
            LaunchedEffect(trackId) {
                programmaticScroll = true
                listState.scrollToItem((active - 3).coerceAtLeast(0))
                programmaticScroll = false
            }
            LaunchedEffect(listState.isScrollInProgress) {
                if (listState.isScrollInProgress && !programmaticScroll) userPinnedScroll = true
            }
            LaunchedEffect(active, userPinnedScroll) {
                if (!userPinnedScroll && active >= 0) {
                    programmaticScroll = true
                    listState.animateScrollToItem((active - 3).coerceAtLeast(0))
                    programmaticScroll = false
                }
            }
            LazyColumn(modifier = Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(vertical = 56.dp, horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                itemsIndexed(synced, key = { _, line -> line.id }) { index, line ->
                    Text(line.text, Modifier.fillMaxWidth().clickable { vm.player.seekTo(line.startTimeMs) }, color = if (index == active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, style = if (index == active) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge, fontWeight = if (index == active) FontWeight.Bold else FontWeight.Normal)
                }
            }
        } else Text(plain, Modifier.padding(16.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable private fun QueueScreen(state: PlaybackState, vm: MainViewModel, nav: NavHostController) {
    Column(Modifier.fillMaxSize()) { TopAppBar({ Text("현재 재생목록") }, navigationIcon = { IconButton({ nav.popBackStack() }) { Icon(Icons.Default.ArrowBack, null) } }, actions = { TextButton(vm.player::clear) { Text("비우기") } }); Text("현재 ${state.current?.let { state.queue.indexOf(it) + 1 } ?: 0} / ${state.queue.size}곡", Modifier.padding(16.dp)); LazyColumn { items(state.queue, key = { it.mediaId }) { item -> ListItem(headlineContent = { Text(item.mediaMetadata.title?.toString().orEmpty(), maxLines = 1) }, supportingContent = { Text(item.mediaMetadata.artist?.toString().orEmpty()) }, leadingContent = { Icon(Icons.Default.DragHandle, null) }, trailingContent = { Icon(Icons.Default.MoreVert, null) }) } } }
}

@Composable private fun AlbumsScreen(ui: com.hendo.hendomusic.MainUiState, vm: MainViewModel) {
    var createAlbum by remember { mutableStateOf(false) }; var createFolder by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) { TopAppBar({ Text("내 앨범", fontWeight = FontWeight.Bold) }, actions = { IconButton({ createFolder = true }) { Icon(Icons.Default.CreateNewFolder, null) }; IconButton({ createAlbum = true }) { Icon(Icons.Default.Add, null) } }); LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { item { SpecialAlbum("좋아요한 곡", ui.tracks.count { it.isFavorite }, Icons.Default.Favorite); SpecialAlbum("많이 들은 곡", ui.tracks.count { it.playCount > 0 }, Icons.Default.AutoGraph) }; items(ui.folders) { folder -> ListItem(headlineContent = { Text(folder.name) }, supportingContent = { Text("앨범 폴더") }, leadingContent = { Icon(Icons.Default.Folder, null) }) }; items(ui.albums) { album -> ListItem(headlineContent = { Text(album.name) }, supportingContent = { Text("사용자 앨범") }, leadingContent = { Icon(Icons.Default.Album, null) }, trailingContent = { Icon(Icons.Default.MoreVert, null) }, modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) } } }
    if(createAlbum) NameDialog("새 앨범") { it?.let(vm::createAlbum); createAlbum = false }
    if(createFolder) NameDialog("새 앨범 폴더") { it?.let(vm::createFolder); createFolder = false }
}

@Composable private fun SpecialAlbum(name: String, count: Int, icon: androidx.compose.ui.graphics.vector.ImageVector) = ListItem(headlineContent = { Text(name, fontWeight = FontWeight.SemiBold) }, supportingContent = { Text("${count}곡 · 자동 앨범") }, leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) })
@Composable private fun NameDialog(title: String, done: (String?) -> Unit) { var value by remember { mutableStateOf("") }; AlertDialog({ done(null) }, { TextButton({ if(value.isNotBlank()) done(value.trim()) }) { Text("만들기") } }, dismissButton = { TextButton({ done(null) }) { Text("취소") } }, title = { Text(title) }, text = { OutlinedTextField(value, { value = it }, singleLine = true) }) }

@Composable private fun SettingsScreen(ui: com.hendo.hendomusic.MainUiState, vm: MainViewModel, requestMedia: () -> Unit, requestOverlay: () -> Unit, chooseTree: () -> Unit, choosePlaylist: () -> Unit, exportPlaylist: (UserAlbumEntity) -> Unit, floatingChanged: (Boolean) -> Unit) {
    val context = LocalContext.current
    var exportSheet by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { TopAppBar({ Text("설정", fontWeight = FontWeight.Bold) }) }
        item { Section("라이브러리"); Choice("전체 음악 검색", ui.settings.scanMode == ScanMode.MEDIA_STORE) { vm.setScanMode(ScanMode.MEDIA_STORE) }; Choice("선택 폴더만 검색", ui.settings.scanMode == ScanMode.SELECTED_FOLDERS) { vm.setScanMode(ScanMode.SELECTED_FOLDERS) }; SettingLine(Icons.Default.FolderOpen, "음악 폴더 추가", "${ui.settings.treeUris.size}개 폴더 등록", chooseTree); SettingLine(Icons.Default.Refresh, "음악 라이브러리 다시 검색", ui.scanMessage.orEmpty(), vm::scan) }
        item { Section("플로팅 가사"); SwitchLine("다른 앱 위에 가사 표시", ui.settings.floatingLyrics) { if(it && !Settings.canDrawOverlays(context)) requestOverlay(); floatingChanged(it) }; Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) { Text("표시 줄 수", Modifier.weight(1f)); (1..3).forEach { FilterChip(it == ui.settings.floatingLines, { vm.setFloatingLines(it) }, { Text("${it}줄") }, Modifier.padding(start = 6.dp)) } } }
        item { Section("테마"); Row(Modifier.padding(horizontal = 16.dp)) { ThemeMode.entries.forEach { mode -> FilterChip(mode == ui.settings.theme, { vm.setTheme(mode) }, { Text(when(mode){ThemeMode.DARK->"다크";ThemeMode.LIGHT->"라이트";ThemeMode.SYSTEM->"시스템"}) }, Modifier.padding(4.dp)) } } }
        item { Section("재생"); SwitchLine("앨범커버 모드 가사 미리보기", ui.settings.coverLyricsPreview, vm::setCoverLyricsPreview); SwitchLine("많이 들은 곡 기록", ui.settings.trackListening, vm::setTrackListening) }
        item { Section("화면"); SwitchLine("앱을 보는 동안 화면 켜기", ui.settings.keepScreenOn, vm::setKeepScreenOn) }
        item { Section("권한"); SettingLine(Icons.Default.AudioFile, "음악 및 알림 권한", if(hasAudioPermission(context)) "허용됨" else "권한 필요", requestMedia); SettingLine(Icons.Default.PictureInPicture, "다른 앱 위에 표시", if(Settings.canDrawOverlays(context)) "허용됨" else "권한 필요", requestOverlay) }
        item { Section("내 앨범 데이터"); SettingLine(Icons.Default.Sync, "삼성뮤직 공개 재생목록 동기화", "Android에서 공개한 재생목록을 내 앨범으로 가져옵니다", vm::importPublicPlaylists); SettingLine(Icons.Default.FileUpload, "내 앨범 가져오기", "Samsung SMPL / M3U / M3U8 / PLS 파일 선택", choosePlaylist); SettingLine(Icons.Default.FileDownload, "내 앨범 내보내기", "선택한 내 앨범을 M3U로 저장") { exportSheet = true } }
        item { Section("데이터"); SettingLine(Icons.Default.FileUpload, "LRC·앱 데이터 백업/복원", "가사 화면의 LRC 가져오기/내보내기와 Android 자동 백업 지원") {} }
    }
    if (exportSheet) AlertDialog(onDismissRequest = { exportSheet = false }, title = { Text("내보낼 앨범") }, text = { if (ui.albums.isEmpty()) Text("내보낼 사용자 앨범이 없습니다.") else LazyColumn(Modifier.heightIn(max = 360.dp)) { items(ui.albums, key = { it.id }) { album -> ListItem({ Text(album.name) }, modifier = Modifier.clickable { exportPlaylist(album); exportSheet = false }) } } }, confirmButton = { TextButton({ exportSheet = false }) { Text("닫기") } })
}

@Composable private fun Section(text: String) { Text(text, Modifier.padding(start = 20.dp, top = 22.dp, bottom = 6.dp), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold) }
@Composable private fun Choice(text: String, selected: Boolean, click: () -> Unit) = Row(Modifier.fillMaxWidth().clickable(onClick = click).padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) { RadioButton(selected, click); Text(text, Modifier.padding(start = 8.dp)) }
@Composable private fun SwitchLine(text: String, value: Boolean, changed: (Boolean) -> Unit) = Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Text(text, Modifier.weight(1f)); Switch(value, changed) }
@Composable private fun SettingLine(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, click: () -> Unit) = ListItem(headlineContent = { Text(title) }, supportingContent = { if(subtitle.isNotBlank()) Text(subtitle) }, leadingContent = { Icon(icon, null) }, modifier = Modifier.clickable(onClick = click))

@Composable private fun LyricsEditor(trackId: String, vm: MainViewModel, nav: NavHostController) {
    var text by remember { mutableStateOf("") }; var synced by remember { mutableStateOf<List<SyncedLyricLine>>(emptyList()) }
    Column(Modifier.fillMaxSize()) { TopAppBar({ Text("가사 편집") }, navigationIcon = { IconButton({ nav.popBackStack() }) { Icon(Icons.Default.ArrowBack, null) } }, actions = { TextButton({ vm.saveLyrics(trackId, text, synced); nav.popBackStack() }) { Text("저장") } }); OutlinedTextField(text, { text = it }, placeholder = { Text("가사를 붙여넣거나 직접 입력하세요. 줄바꿈은 그대로 보존됩니다.") }, modifier = Modifier.fillMaxWidth().weight(1f).padding(16.dp)); Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedButton({ synced = vm.importLrc(text) }) { Text("LRC 해석") }; Button({ nav.navigate("sync/$trackId") }, enabled = text.isNotBlank()) { Text("싱크 등록") } } }
}

@Composable private fun SyncEditor(trackId: String, playback: PlaybackState, vm: MainViewModel, nav: NavHostController) {
    var raw by remember { mutableStateOf("") }; val lines = remember(raw) { raw.lines().filter { it.isNotBlank() } }; var index by remember { mutableIntStateOf(0) }; var stamps by remember { mutableStateOf<Map<Int, Long>>(emptyMap()) }
    Column(Modifier.fillMaxSize().padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { IconButton({ nav.popBackStack() }) { Icon(Icons.Default.ArrowBack, null) }; Text("가사 싱크 등록", style = MaterialTheme.typography.titleLarge) }; Text("${formatTime(playback.positionMs)} / ${formatTime(playback.durationMs)}", color = MaterialTheme.colorScheme.primary); if(lines.isEmpty()) OutlinedTextField(raw, { raw = it }, label = { Text("줄 단위 가사") }, modifier = Modifier.fillMaxWidth().weight(1f)) else Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) { lines.getOrNull(index-1)?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }; Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(16.dp), modifier = Modifier.padding(vertical = 14.dp)) { Text(lines.getOrNull(index).orEmpty(), Modifier.padding(20.dp), style = MaterialTheme.typography.titleLarge) }; lines.getOrNull(index+1)?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) } }; Row { IconButton({ vm.player.seekTo((playback.positionMs-3000).coerceAtLeast(0)) }) { Icon(Icons.Default.Replay5, null) }; FilledIconButton(vm.player::toggle) { Icon(if(playback.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null) }; IconButton({ vm.player.seekTo(playback.positionMs+3000) }) { Icon(Icons.Default.Forward5, null) } }; Row { OutlinedButton({ index = (index-1).coerceAtLeast(0) }) { Text("이전 줄") }; Button({ if(lines.isNotEmpty()) { stamps = stamps + (index to playback.positionMs); index = (index+1).coerceAtMost(lines.lastIndex) } }, Modifier.padding(horizontal = 8.dp)) { Text("현재 줄 싱크") }; OutlinedButton({ index = (index+1).coerceAtMost(lines.lastIndex.coerceAtLeast(0)) }) { Text("다음 줄") } }; Button({ val result = lines.mapIndexed { i, line -> SyncedLyricLine("$trackId:$i", stamps[i] ?: 0, line) }; vm.saveLyrics(trackId, lines.joinToString("\n"), result); nav.popBackStack() }, enabled = stamps.isNotEmpty(), modifier = Modifier.fillMaxWidth().padding(top = 14.dp)) { Text("싱크 가사 저장") } }
}

private fun formatTime(ms: Long): String { val sec = (ms / 1000).coerceAtLeast(0); return "%d:%02d".format(sec / 60, sec % 60) }
private fun hasAudioPermission(context: android.content.Context): Boolean { val p = if(Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE; return androidx.core.content.ContextCompat.checkSelfPermission(context, p) == android.content.pm.PackageManager.PERMISSION_GRANTED }
private fun hasNotificationPermission(context: android.content.Context): Boolean = Build.VERSION.SDK_INT < 33 || androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
