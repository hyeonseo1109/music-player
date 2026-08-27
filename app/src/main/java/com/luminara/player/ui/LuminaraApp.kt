@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.luminara.player.ui

import android.Manifest
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import coil3.compose.AsyncImage
import com.luminara.player.MainViewModel
import com.luminara.player.data.*
import com.luminara.player.library.MetadataUpdate
import com.luminara.player.lyrics.SyncedLyricLine
import com.luminara.player.lyrics.LrcCodec
import com.luminara.player.playback.PlaybackState
import kotlin.math.roundToLong

@Composable
fun LuminaraApp(
    viewModel: MainViewModel,
    requestMediaPermission: () -> Unit,
    requestOnboardingPermission: (String, (Boolean) -> Unit) -> Unit,
    requestOverlay: () -> Unit,
    chooseTree: () -> Unit,
    onFloatingChanged: (Boolean) -> Unit,
    requestDelete: (TrackEntity) -> Unit,
    requestMetadataWrite: (TrackEntity, MetadataUpdate, (String) -> Unit) -> Unit,
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val playback by viewModel.player.state.collectAsStateWithLifecycle()
    if (!ui.settings.onboardingDone) {
        OnboardingScreen(requestOnboardingPermission, requestOverlay) { viewModel.completeOnboarding() }
        return
    }
    val nav = rememberNavController()
    val currentRoute = nav.currentBackStackEntryAsState().value?.destination?.route
    val showChrome = currentRoute == null || currentRoute in setOf("library", "albums", "settings")
    PurpleAtmosphere { Scaffold(
        containerColor = Color.Transparent,
        bottomBar = {
            if (showChrome) Column {
                if (playback.current != null) MiniPlayer(playback, { nav.navigate("player") }, viewModel.player::toggle, viewModel.player::next)
                NavigationBar(containerColor = Color(0xE606030C), tonalElevation = 0.dp) {
                    NavItem(nav, "library", "전체 곡", Icons.Default.LibraryMusic)
                    NavItem(nav, "albums", "내 앨범", Icons.Default.Album)
                    NavItem(nav, "settings", "설정", Icons.Default.Settings)
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = "library", modifier = Modifier.padding(padding)) {
            composable("library") { LibraryScreen(ui, viewModel, nav, requestDelete) }
            composable("albums") { AlbumOrganizerScreen(ui, viewModel) }
            composable("settings") { SettingsScreen(ui, viewModel, requestMediaPermission, requestOverlay, chooseTree, onFloatingChanged) }
            composable("player") { NowPlayingScreen(playback, viewModel, nav, ui.tracks) }
            composable("queue") { ReorderableQueueScreen(playback, viewModel) { nav.popBackStack() } }
            composable("lyricsSearch/{trackId}") { back ->
                val trackId = back.arguments?.getString("trackId").orEmpty()
                ui.tracks.find { it.id == trackId }?.let { track ->
                    LyricsSearchScreen(track, viewModel, { nav.popBackStack() }) { nav.navigate("lyrics/$trackId") }
                }
            }
            composable("lyrics/{trackId}") { back ->
                val trackId = back.arguments?.getString("trackId").orEmpty()
                LyricsEditorScreen(trackId, viewModel, { nav.popBackStack() }) { nav.navigate("sync/$trackId") }
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

@Composable private fun OnboardingScreen(requestPermission: (String, (Boolean) -> Unit) -> Unit, requestOverlay: () -> Unit, done: () -> Unit) {
    val context = LocalContext.current
    val initialStep = remember {
        when {
            !hasAudioPermission(context) -> 0
            Build.VERSION.SDK_INT >= 33 && !hasNotificationPermission(context) -> 1
            else -> 2
        }
    }
    var step by remember { mutableIntStateOf(initialStep) }
    var permissionDenied by remember { mutableStateOf(false) }
    val items = listOf(
        Triple(Icons.Default.LibraryMusic, "음악 및 오디오", "휴대폰의 로컬 음악 라이브러리를 읽습니다."),
        Triple(Icons.Default.Notifications, "알림 및 미디어 컨트롤", "백그라운드 재생과 잠금화면 제어에 필요합니다."),
        Triple(Icons.Default.PictureInPicture, "다른 앱 위에 표시", "다른 앱을 사용하는 동안 싱크 가사를 띄웁니다."),
    )
    PurpleAtmosphere { Box(Modifier.fillMaxSize().padding(28.dp)) {
        Column(Modifier.align(Alignment.Center)) {
            Icon(items[step].first, null, Modifier.size(72.dp), Color(0xFFB69CFF))
            Spacer(Modifier.height(28.dp)); Text("LUMINARA", style = MaterialTheme.typography.labelLarge, color = Color(0xFFB69CFF))
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
                    2 -> if (!Settings.canDrawOverlays(context)) requestOverlay() else done()
                }
            }, Modifier.fillMaxWidth().height(54.dp)) { Text(if (step == 2) "설정하고 시작" else "권한 요청 후 다음") }
            if (permissionDenied) Text("권한을 허용해야 이 단계를 진행할 수 있습니다.", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 10.dp))
            if (step == 2) TextButton(onClick = done, Modifier.align(Alignment.CenterHorizontally)) { Text("나중에 설정") }
        }
    } }
}

@Composable private fun RowScope.NavItem(nav: NavHostController, route: String, label: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    val current = nav.currentDestination?.route
    NavigationBarItem(selected = current == route, onClick = { nav.navigate(route) { launchSingleTop = true; popUpTo("library") } }, icon = { Icon(icon, null) }, label = { Text(label) })
}

@Composable private fun LibraryScreen(ui: com.luminara.player.MainUiState, vm: MainViewModel, nav: NavHostController, requestDelete: (TrackEntity) -> Unit) {
    var sortOpen by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<TrackEntity?>(null) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("내 음악", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconButton(onClick = { vm.scan() }) { Icon(Icons.Default.Refresh, "다시 검색") }
        }
        OutlinedTextField(value = ui.query, onValueChange = vm::setQuery, leadingIcon = { Icon(Icons.Default.Search, null) }, placeholder = { Text("곡, 가수, 앨범 또는 초성 검색") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), shape = RoundedCornerShape(18.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("${ui.visibleTracks.size}곡", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Box { TextButton(onClick = { sortOpen = true }) { Icon(Icons.Default.Sort, null); Text(sortLabel(ui.settings.sort)) }
                DropdownMenu(sortOpen, { sortOpen = false }) { listOf("TITLE" to "곡명 순", "RECENT" to "최근 추가", "PLAYED" to "최근 들은 순", "COUNT" to "많이 들은 순").forEach { (k, v) -> DropdownMenuItem({ Text(v) }, { vm.setSort(k); sortOpen = false }) } }
            }
        }
        if (ui.scanning) LinearProgressIndicator(Modifier.fillMaxWidth())
        ui.scanMessage?.let { Text(it, Modifier.padding(horizontal = 18.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall) }
        if (!ui.scanning && ui.tracks.isEmpty()) EmptyLibrary(vm::scan)
        else LazyColumn(contentPadding = PaddingValues(bottom = 12.dp)) { items(ui.visibleTracks, key = { it.id }) { track -> MusicRow(track, { vm.player.play(track, ui.tracks) }, { selected = track }) } }
    }
    selected?.let { TrackMenu(it, ui, vm, nav, requestDelete) { selected = null } }
}

private fun sortLabel(sort: String) = when(sort) { "RECENT" -> "최근 추가"; "PLAYED" -> "최근 들은 순"; "COUNT" -> "많이 들은 순"; else -> "곡명 순" }

@Composable private fun EmptyLibrary(scan: () -> Unit) = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Default.GraphicEq, null, Modifier.size(64.dp), MaterialTheme.colorScheme.primary); Text("음악이 아직 없습니다", style = MaterialTheme.typography.titleLarge); Text("권한을 허용한 뒤 라이브러리를 검색하세요."); Button(scan) { Text("음악 검색") } }
}

@Composable private fun MusicRow(track: TrackEntity, play: () -> Unit, menu: () -> Unit) {
    Row(Modifier.fillMaxWidth().pointerInput(track.id) { detectTapGestures(onTap = { play() }, onLongPress = { menu() }) }.padding(horizontal = 16.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Artwork(track.customArtworkUri ?: track.albumArtUri, 54)
        Column(Modifier.weight(1f).padding(start = 12.dp)) { Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold); Text(track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        IconButton(menu) { Icon(Icons.Default.MoreVert, "곡 메뉴") }
    }
}

@Composable private fun Artwork(uri: String?, size: Int) {
    Box(Modifier.size(size.dp).clip(RoundedCornerShape(14.dp)).background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.surfaceVariant))).border(1.dp, Color(0x669B4DFF), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
        if (uri != null) AsyncImage(uri, null, Modifier.fillMaxSize()) else Icon(Icons.Default.MusicNote, null, tint = MaterialTheme.colorScheme.primary)
    }
}

@Composable private fun TrackMenu(track: TrackEntity, ui: com.luminara.player.MainUiState, vm: MainViewModel, nav: NavHostController, requestDelete: (TrackEntity) -> Unit, close: () -> Unit) {
    var albumPicker by remember { mutableStateOf(false) }
    var deleteConfirm by remember { mutableStateOf(false) }
    ModalBottomSheet(close) { Column(Modifier.padding(bottom = 28.dp)) {
        ListItem(headlineContent = { Text(track.title, fontWeight = FontWeight.Bold) }, supportingContent = { Text(track.artist) }, leadingContent = { Artwork(track.albumArtUri, 48) })
        MenuLine(Icons.Default.PlayArrow, "듣기") { vm.player.play(track, ui.tracks); close() }
        MenuLine(Icons.Default.SkipNext, "다음 곡으로 재생") { vm.player.playNext(track); close() }
        MenuLine(Icons.Default.PlaylistAdd, "현재 재생목록에 추가") { vm.player.append(track); close() }
        MenuLine(Icons.Default.Album, "내 앨범에 추가") { albumPicker = true }
        MenuLine(Icons.Default.Edit, "곡 정보 수정하기") { close(); nav.navigate("metadata/${track.id}") }
        MenuLine(Icons.Default.Lyrics, "가사 검색") { close(); nav.navigate("lyricsSearch/${track.id}") }
        MenuLine(Icons.Default.EditNote, "가사 직접 입력") { close(); vm.stageLyrics(null); nav.navigate("lyrics/${track.id}") }
        MenuLine(Icons.Default.DeleteOutline, "삭제") { deleteConfirm = true }
    } }
    if (albumPicker) AlertDialog(onDismissRequest = { albumPicker = false }, title = { Text("내 앨범에 추가") }, text = { Column { ui.albums.forEach { album -> TextButton({ vm.addToAlbum(album.id, track.id); albumPicker = false; close() }) { Text(album.name) } }; if (ui.albums.isEmpty()) Text("먼저 내 앨범 화면에서 앨범을 만드세요.") } }, confirmButton = {})
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

@Composable private fun NowPlayingScreen(state: PlaybackState, vm: MainViewModel, nav: NavHostController, tracks: List<TrackEntity>) {
    val item = state.current
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { IconButton({ nav.popBackStack() }) { Icon(Icons.Default.KeyboardArrowDown, null) }; Text("지금 재생 중", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium); IconButton({ nav.navigate("queue") }) { Icon(Icons.Default.QueueMusic, null) } }
        Spacer(Modifier.height(24.dp)); Artwork(item?.mediaMetadata?.artworkUri?.toString(), 292); Spacer(Modifier.height(28.dp))
        Text(item?.mediaMetadata?.title?.toString() ?: "재생 중인 곡 없음", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(item?.mediaMetadata?.artist?.toString().orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(20.dp)); Slider(value = state.positionMs.toFloat().coerceAtMost(state.durationMs.toFloat().coerceAtLeast(1f)), onValueChange = { vm.player.seekTo(it.roundToLong()) }, valueRange = 0f..state.durationMs.toFloat().coerceAtLeast(1f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(formatTime(state.positionMs)); Text(formatTime(state.durationMs)) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly) {
            IconButton(vm.player::toggleShuffle) { Icon(Icons.Default.Shuffle, null, tint = if(state.shuffle) MaterialTheme.colorScheme.primary else LocalContentColor.current) }
            IconButton(vm.player::previous) { Icon(Icons.Default.SkipPrevious, null, Modifier.size(36.dp)) }
            FilledIconButton(vm.player::toggle, Modifier.size(70.dp)) { Icon(if(state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null, Modifier.size(36.dp)) }
            IconButton(vm.player::next) { Icon(Icons.Default.SkipNext, null, Modifier.size(36.dp)) }
            IconButton(vm.player::cycleRepeat) { Icon(if(state.repeatMode == 1) Icons.Default.Repeat else if(state.repeatMode == 2) Icons.Default.RepeatOne else Icons.Default.Repeat, null, tint = if(state.repeatMode != 0) MaterialTheme.colorScheme.primary else LocalContentColor.current) }
        }
        val track = tracks.find { it.id == item?.mediaMetadata?.extras?.getString("track_id") }
        Row { IconButton({ track?.let { vm.toggleFavorite(it.id) } }) { Icon(if(track?.isFavorite == true) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null) }; IconButton({ track?.let { nav.navigate("lyricsSearch/${it.id}") } }) { Icon(Icons.Default.Lyrics, "가사 검색") }; IconButton({ nav.navigate("queue") }) { Icon(Icons.Default.QueueMusic, null) } }
        track?.let { NowPlayingLyricsPanel(it.id, state.positionMs, vm) }
    }
}

@Composable private fun NowPlayingLyricsPanel(trackId: String, positionMs: Long, vm: MainViewModel) {
    var plain by remember(trackId) { mutableStateOf("") }
    var synced by remember(trackId) { mutableStateOf<List<SyncedLyricLine>>(emptyList()) }
    LaunchedEffect(trackId) { val value = vm.lyrics(trackId); plain = value.first; synced = value.second }
    if (plain.isBlank()) return
    val active = if (synced.isNotEmpty()) LrcCodec.activeIndex(synced, positionMs).coerceAtLeast(0) else -1
    Surface(Modifier.fillMaxWidth().heightIn(max = 220.dp).purpleGlass(18), color = Color.Transparent, shape = RoundedCornerShape(18.dp)) {
        if (active >= 0) Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            synced.getOrNull(active - 1)?.let { Text(it.text, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
            Text(synced[active].text, Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            synced.getOrNull(active + 1)?.let { Text(it.text, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
        } else Text(plain, Modifier.padding(16.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable private fun QueueScreen(state: PlaybackState, vm: MainViewModel, nav: NavHostController) {
    Column(Modifier.fillMaxSize()) { TopAppBar({ Text("현재 재생목록") }, navigationIcon = { IconButton({ nav.popBackStack() }) { Icon(Icons.Default.ArrowBack, null) } }, actions = { TextButton(vm.player::clear) { Text("비우기") } }); Text("현재 ${state.current?.let { state.queue.indexOf(it) + 1 } ?: 0} / ${state.queue.size}곡", Modifier.padding(16.dp)); LazyColumn { items(state.queue, key = { it.mediaId }) { item -> ListItem(headlineContent = { Text(item.mediaMetadata.title?.toString().orEmpty(), maxLines = 1) }, supportingContent = { Text(item.mediaMetadata.artist?.toString().orEmpty()) }, leadingContent = { Icon(Icons.Default.DragHandle, null) }, trailingContent = { Icon(Icons.Default.MoreVert, null) }) } } }
}

@Composable private fun AlbumsScreen(ui: com.luminara.player.MainUiState, vm: MainViewModel) {
    var createAlbum by remember { mutableStateOf(false) }; var createFolder by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) { TopAppBar({ Text("내 앨범", fontWeight = FontWeight.Bold) }, actions = { IconButton({ createFolder = true }) { Icon(Icons.Default.CreateNewFolder, null) }; IconButton({ createAlbum = true }) { Icon(Icons.Default.Add, null) } }); LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { item { SpecialAlbum("좋아요한 곡", ui.tracks.count { it.isFavorite }, Icons.Default.Favorite); SpecialAlbum("많이 들은 곡", ui.tracks.count { it.playCount > 0 }, Icons.Default.AutoGraph) }; items(ui.folders) { folder -> ListItem(headlineContent = { Text(folder.name) }, supportingContent = { Text("앨범 폴더") }, leadingContent = { Icon(Icons.Default.Folder, null) }) }; items(ui.albums) { album -> ListItem(headlineContent = { Text(album.name) }, supportingContent = { Text("사용자 앨범") }, leadingContent = { Icon(Icons.Default.Album, null) }, trailingContent = { Icon(Icons.Default.MoreVert, null) }, modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) } } }
    if(createAlbum) NameDialog("새 앨범") { it?.let(vm::createAlbum); createAlbum = false }
    if(createFolder) NameDialog("새 앨범 폴더") { it?.let(vm::createFolder); createFolder = false }
}

@Composable private fun SpecialAlbum(name: String, count: Int, icon: androidx.compose.ui.graphics.vector.ImageVector) = ListItem(headlineContent = { Text(name, fontWeight = FontWeight.SemiBold) }, supportingContent = { Text("${count}곡 · 자동 앨범") }, leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) })
@Composable private fun NameDialog(title: String, done: (String?) -> Unit) { var value by remember { mutableStateOf("") }; AlertDialog({ done(null) }, { TextButton({ if(value.isNotBlank()) done(value.trim()) }) { Text("만들기") } }, dismissButton = { TextButton({ done(null) }) { Text("취소") } }, title = { Text(title) }, text = { OutlinedTextField(value, { value = it }, singleLine = true) }) }

@Composable private fun SettingsScreen(ui: com.luminara.player.MainUiState, vm: MainViewModel, requestMedia: () -> Unit, requestOverlay: () -> Unit, chooseTree: () -> Unit, floatingChanged: (Boolean) -> Unit) {
    val context = LocalContext.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { TopAppBar({ Text("설정", fontWeight = FontWeight.Bold) }) }
        item { Section("라이브러리"); Choice("전체 음악 검색", ui.settings.scanMode == ScanMode.MEDIA_STORE) { vm.setScanMode(ScanMode.MEDIA_STORE) }; Choice("선택 폴더만 검색", ui.settings.scanMode == ScanMode.SELECTED_FOLDERS) { vm.setScanMode(ScanMode.SELECTED_FOLDERS) }; SettingLine(Icons.Default.FolderOpen, "음악 폴더 추가", "${ui.settings.treeUris.size}개 폴더 등록", chooseTree); SettingLine(Icons.Default.Refresh, "음악 라이브러리 다시 검색", ui.scanMessage.orEmpty(), vm::scan) }
        item { Section("플로팅 가사"); SwitchLine("다른 앱 위에 가사 표시", ui.settings.floatingLyrics) { if(it && !Settings.canDrawOverlays(context)) requestOverlay(); floatingChanged(it) }; Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) { Text("표시 줄 수", Modifier.weight(1f)); (1..3).forEach { FilterChip(it == ui.settings.floatingLines, { vm.setFloatingLines(it) }, { Text("${it}줄") }, Modifier.padding(start = 6.dp)) } } }
        item { Section("테마"); Row(Modifier.padding(horizontal = 16.dp)) { ThemeMode.entries.forEach { mode -> FilterChip(mode == ui.settings.theme, { vm.setTheme(mode) }, { Text(when(mode){ThemeMode.DARK->"다크";ThemeMode.LIGHT->"라이트";ThemeMode.SYSTEM->"시스템"}) }, Modifier.padding(4.dp)) } } }
        item { Section("화면"); SwitchLine("앱을 보는 동안 화면 켜기", ui.settings.keepScreenOn, vm::setKeepScreenOn) }
        item { Section("권한"); SettingLine(Icons.Default.AudioFile, "음악 및 알림 권한", if(hasAudioPermission(context)) "허용됨" else "권한 필요", requestMedia); SettingLine(Icons.Default.PictureInPicture, "다른 앱 위에 표시", if(Settings.canDrawOverlays(context)) "허용됨" else "권한 필요", requestOverlay) }
        item { Section("데이터"); SettingLine(Icons.Default.FileUpload, "LRC·앱 데이터 백업/복원", "가사 화면의 LRC 가져오기/내보내기와 Android 자동 백업 지원") {} }
    }
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
