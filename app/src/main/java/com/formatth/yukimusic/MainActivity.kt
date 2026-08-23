package com.formatth.yukimusic

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import coil.compose.AsyncImage
import com.formatth.yukimusic.data.HomeSection
import com.formatth.yukimusic.data.LyricsResult
import com.formatth.yukimusic.data.MusicApi
import com.formatth.yukimusic.data.MusicSearchItem
import com.formatth.yukimusic.player.PlaybackService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

private data class LyricLine(val timeMs: Long?, val text: String)

class MainActivity : ComponentActivity() {
    private var mediaController: MediaController? = null
    private var controllerFuture: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
    private var isPlaying by mutableStateOf(false)
    private var currentTitle by mutableStateOf<String?>(null)
    private var currentArtist by mutableStateOf<String?>(null)
    private var currentArtwork by mutableStateOf<String?>(null)
    private var currentVideoId by mutableStateOf<String?>(null)
    private var playbackError by mutableStateOf<String?>(null)
    private var loadingVideoId by mutableStateOf<String?>(null)
    private var showPlayer by mutableStateOf(false)
    private var positionMs by mutableLongStateOf(0L)
    private var durationMs by mutableLongStateOf(0L)
    private var queueItems by mutableStateOf<List<MusicSearchItem>>(emptyList())
    private var lyrics by mutableStateOf<LyricsResult?>(null)
    private var relatedSections by mutableStateOf<List<HomeSection>>(emptyList())
    private var lyricsBrowseId: String? = null
    private var relatedBrowseId: String? = null
    private var listenerAttached = false

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlayingNow: Boolean) {
            isPlaying = isPlayingNow
            syncPlayerState()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            syncPlayerState()
            val id = mediaItem?.mediaId ?: return
            val item = queueItems.firstOrNull { it.videoId == id }
            if (item != null) {
                currentTitle = item.title
                currentArtist = item.subtitle
                currentArtwork = item.thumbnail
                currentVideoId = id
                playbackError = null
                lyrics = null
                lifecycleScope.launch {
                    loadMetadataForCurrent()
                    ensureNextQueued()
                }
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            syncPlayerState()
            if (playbackState == Player.STATE_READY) {
                lifecycleScope.launch { loadLyricsForCurrent() }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        }
        setContent {
            YukiMusicTheme {
                YukiMusicApp(
                    isPlaying = isPlaying,
                    currentTitle = currentTitle,
                    currentArtist = currentArtist,
                    currentArtwork = currentArtwork,
                    currentVideoId = currentVideoId,
                    playbackError = playbackError,
                    loadingVideoId = loadingVideoId,
                    showPlayer = showPlayer,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    queueItems = queueItems,
                    lyrics = lyrics,
                    relatedSections = relatedSections,
                    onPlayTrack = ::playTrack,
                    onOpenPlayer = { showPlayer = true },
                    onClosePlayer = { showPlayer = false },
                    onPlayPause = ::togglePlayback,
                    onPrevious = ::previousTrack,
                    onNext = ::nextTrack,
                    onSeek = { value -> mediaController?.seekTo(value) },
                    onSelectQueue = ::playTrack,
                    onRefreshProgress = ::refreshProgress
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (mediaController != null) {
            syncPlayerState()
            return
        }
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, token).buildAsync().also { future ->
            future.addListener({
                try {
                    mediaController = future.get()
                    if (!listenerAttached) {
                        mediaController?.addListener(playerListener)
                        listenerAttached = true
                    }
                    syncPlayerState()
                } catch (_: Exception) {
                    mediaController = null
                }
            }, mainExecutor)
        }
    }

    override fun onStop() {
        // Keep the controller connection alive while the Activity is stopped.
        // PlaybackService owns the actual player and continues background playback.
        super.onStop()
    }

    override fun onDestroy() {
        mediaController?.removeListener(playerListener)
        mediaController?.release()
        mediaController = null
        controllerFuture?.cancel(false)
        controllerFuture = null
        super.onDestroy()
    }

    private fun syncPlayerState() {
        val controller = mediaController ?: return
        isPlaying = controller.isPlaying
        currentTitle = controller.mediaMetadata.title?.toString() ?: currentTitle
        currentArtist = controller.mediaMetadata.artist?.toString() ?: currentArtist
        currentArtwork = controller.mediaMetadata.artworkUri?.toString() ?: currentArtwork
        currentVideoId = controller.currentMediaItem?.mediaId ?: currentVideoId
        positionMs = controller.currentPosition.coerceAtLeast(0L)
        durationMs = controller.duration.takeIf { it > 0 } ?: durationMs
    }

    private fun playTrack(item: MusicSearchItem) {
        val videoId = item.videoId ?: return
        val controller = mediaController ?: run {
            playbackError = "Player service is not ready"
            return
        }

        if (currentVideoId == videoId && controller.currentMediaItem != null) {
            showPlayer = true
            if (!controller.isPlaying) controller.play()
            syncPlayerState()
            return
        }

        playbackError = null
        currentTitle = item.title
        currentArtist = item.subtitle.ifBlank { "YouTube Music" }
        currentArtwork = item.thumbnail
        currentVideoId = videoId
        showPlayer = true
        loadingVideoId = videoId
        lyrics = null
        relatedSections = emptyList()

        lifecycleScope.launch {
            try {
                val audioUrl = MusicApi.resolvePlaybackUrl(videoId)
                val mediaItem = MediaItem.Builder()
                    .setUri(audioUrl)
                    .setMediaId(videoId)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(item.title)
                            .setArtist(item.subtitle.ifBlank { "YouTube Music" })
                            .setArtworkUri(item.thumbnail?.let(Uri::parse))
                            .build()
                    )
                    .build()
                controller.setMediaItem(mediaItem)
                controller.prepare()
                controller.play()
                isPlaying = true
                syncPlayerState()
                loadMetadataForCurrent()
                ensureNextQueued()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                playbackError = e.message ?: "Unable to start playback"
                isPlaying = false
            } finally {
                loadingVideoId = null
            }
        }
    }

    private suspend fun loadMetadataForCurrent() {
        val id = currentVideoId ?: return
        try {
            val response = MusicApi.getNext(id)
            if (response.queue.isNotEmpty()) queueItems = response.queue
            lyricsBrowseId = response.lyricsBrowseId
            relatedBrowseId = response.relatedBrowseId
            if (!relatedBrowseId.isNullOrBlank()) {
                relatedSections = MusicApi.getRelated(relatedBrowseId!!)
            }
            loadLyricsForCurrent()
        } catch (_: Exception) {
            // Queue, lyrics and related content never interrupt playback.
        }
    }

    private suspend fun loadLyricsForCurrent() {
        if (lyrics != null) return
        val title = currentTitle ?: return
        try {
            val durationSeconds = ((mediaController?.duration ?: durationMs).coerceAtLeast(0L) / 1000L)
            lyrics = MusicApi.getLyrics(title, currentArtist ?: "", durationSeconds, lyricsBrowseId)
        } catch (_: Exception) {
            // Lyrics are optional.
        }
    }

    private suspend fun ensureNextQueued() {
        val controller = mediaController ?: return
        val currentId = controller.currentMediaItem?.mediaId ?: currentVideoId ?: return
        if (queueItems.isEmpty()) return
        val currentIndex = queueItems.indexOfFirst { it.videoId == currentId }
        val next = queueItems.getOrNull(if (currentIndex >= 0) currentIndex + 1 else 1) ?: return
        val nextId = next.videoId ?: return
        val alreadyAdded = (0 until controller.mediaItemCount).any { controller.getMediaItemAt(it).mediaId == nextId }
        if (alreadyAdded) return
        try {
            val nextUrl = MusicApi.resolvePlaybackUrl(nextId)
            val nextMediaItem = MediaItem.Builder()
                .setUri(nextUrl)
                .setMediaId(nextId)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(next.title)
                        .setArtist(next.subtitle.ifBlank { "YouTube Music" })
                        .setArtworkUri(next.thumbnail?.let(Uri::parse))
                        .build()
                )
                .build()
            controller.addMediaItem(nextMediaItem)
        } catch (_: Exception) {
            // Current track keeps playing if preloading fails.
        }
    }

    private fun togglePlayback() {
        val controller = mediaController ?: return
        if (controller.isPlaying) controller.pause() else controller.play()
        syncPlayerState()
    }

    private fun previousTrack() {
        val controller = mediaController ?: return
        if (controller.currentPosition > 5_000L) controller.seekTo(0L) else controller.seekToPreviousMediaItem()
        syncPlayerState()
    }

    private fun nextTrack() {
        val controller = mediaController ?: return
        if (controller.hasNextMediaItem()) controller.seekToNextMediaItem()
        else lifecycleScope.launch {
            ensureNextQueued()
            delay(250)
            if (controller.hasNextMediaItem()) controller.seekToNextMediaItem()
        }
    }

    private fun refreshProgress() {
        syncPlayerState()
    }
}

@Composable
private fun YukiMusicTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = if (dark) {
        darkColorScheme(
            background = Color(0xFF0B0B0D),
            surface = Color(0xFF151518),
            surfaceVariant = Color(0xFF202024),
            onBackground = Color(0xFFF5F5F7),
            onSurface = Color(0xFFF5F5F7),
            onSurfaceVariant = Color(0xFFC5C5CC)
        )
    } else {
        lightColorScheme(
            background = Color(0xFFF7F7F9),
            surface = Color.White,
            surfaceVariant = Color(0xFFE9E9EF),
            onBackground = Color(0xFF17171A),
            onSurface = Color(0xFF17171A),
            onSurfaceVariant = Color(0xFF5D5D66)
        )
    }
    MaterialTheme(colorScheme = colors, content = content)
}

@Composable
private fun YukiMusicApp(
    isPlaying: Boolean,
    currentTitle: String?,
    currentArtist: String?,
    currentArtwork: String?,
    currentVideoId: String?,
    playbackError: String?,
    loadingVideoId: String?,
    showPlayer: Boolean,
    positionMs: Long,
    durationMs: Long,
    queueItems: List<MusicSearchItem>,
    lyrics: LyricsResult?,
    relatedSections: List<HomeSection>,
    onPlayTrack: (MusicSearchItem) -> Unit,
    onOpenPlayer: () -> Unit,
    onClosePlayer: () -> Unit,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onSelectQueue: (MusicSearchItem) -> Unit,
    onRefreshProgress: () -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<MusicSearchItem>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var homeSections by remember { mutableStateOf<List<HomeSection>>(emptyList()) }
    var homeLoading by remember { mutableStateOf(false) }
    var homeError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(isPlaying, showPlayer) {
        if (!isPlaying && !showPlayer) return@LaunchedEffect
        while (true) {
            onRefreshProgress()
            delay(500)
        }
    }

    LaunchedEffect(selectedTab) {
        if (selectedTab != 0 || homeSections.isNotEmpty() || homeLoading) return@LaunchedEffect
        homeLoading = true
        homeError = null
        try {
            homeSections = MusicApi.getHome()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            homeError = e.message ?: "Home failed"
        } finally {
            homeLoading = false
        }
    }

    LaunchedEffect(query, selectedTab) {
        if (selectedTab != 1 || query.trim().length < 2) {
            results = emptyList()
            loading = false
            error = null
            return@LaunchedEffect
        }
        delay(350)
        loading = true
        error = null
        try {
            results = MusicApi.searchSongs(query)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            results = emptyList()
            error = e.message ?: "Search failed"
        } finally {
            loading = false
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (!showPlayer) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    listOf(
                        "Home" to Icons.Default.Home,
                        "Search" to Icons.Default.Search,
                        "Library" to Icons.Default.LibraryMusic
                    ).forEachIndexed { index, pair ->
                        NavigationBarItem(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            icon = { Icon(pair.second, contentDescription = pair.first) },
                            label = { Text(pair.first) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        if (showPlayer) {
            FullPlayer(
                title = currentTitle,
                artist = currentArtist,
                artwork = currentArtwork,
                isPlaying = isPlaying,
                positionMs = positionMs,
                durationMs = durationMs,
                error = playbackError,
                queueItems = queueItems,
                lyrics = lyrics,
                relatedSections = relatedSections,
                onClose = onClosePlayer,
                onPlayPause = onPlayPause,
                onPrevious = onPrevious,
                onNext = onNext,
                onSeek = onSeek,
                onSelectQueue = onSelectQueue,
                onPlayRelated = onPlayTrack
            )
        } else {
            Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp, vertical = 14.dp)) {
                Text("Yuki Music", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(
                    when (selectedTab) {
                        1 -> "Search YouTube Music"
                        2 -> "Your library"
                        else -> "Listen to what you love"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(14.dp))
                if (selectedTab == 0) HomeScreen(homeSections, homeLoading, homeError, onPlayTrack)
                else if (selectedTab == 1) SearchScreen(query, { query = it }, results, loading, error, loadingVideoId, onPlayTrack)
                else LibraryPlaceholder()
                Spacer(Modifier.height(10.dp))
                MiniPlayer(currentTitle, currentArtist, currentArtwork, isPlaying, playbackError, onPlayPause, onOpenPlayer)
            }
        }
    }
}

@Composable
private fun ColumnScope.HomeScreen(sections: List<HomeSection>, loading: Boolean, error: String?, onPlayTrack: (MusicSearchItem) -> Unit) {
    when {
        loading -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        error != null -> Column(Modifier.fillMaxWidth().weight(1f).padding(12.dp)) {
            Text("Couldn't load Home", color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(6.dp))
            Text(error, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        sections.isEmpty() -> Text("No Home content available.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        else -> LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            items(sections) { section ->
                Column {
                    Text(section.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(10.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) { items(section.items) { item -> HomeMusicCard(item, onPlayTrack) } }
                }
            }
        }
    }
}

@Composable
private fun HomeMusicCard(item: MusicSearchItem, onPlayTrack: (MusicSearchItem) -> Unit) {
    Column(Modifier.width(148.dp).clickable(enabled = item.videoId != null) { onPlayTrack(item) }) {
        Box(Modifier.fillMaxWidth().height(148.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
            AsyncImage(model = item.thumbnail, contentDescription = item.title, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            if (item.videoId != null) {
                Surface(Modifier.align(Alignment.BottomEnd).padding(8.dp), shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primaryContainer) {
                    IconButton(onClick = { onPlayTrack(item) }) { Icon(Icons.Default.PlayArrow, contentDescription = "Play ${item.title}") }
                }
            }
        }
        Spacer(Modifier.height(7.dp))
        Text(item.title, fontWeight = FontWeight.SemiBold, maxLines = 2)
        if (item.subtitle.isNotBlank()) Text(item.subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 1)
    }
}

@Composable
private fun ColumnScope.SearchScreen(query: String, onQueryChange: (String) -> Unit, results: List<MusicSearchItem>, loading: Boolean, error: String?, loadingVideoId: String?, onPlayTrack: (MusicSearchItem) -> Unit) {
    OutlinedTextField(value = query, onValueChange = onQueryChange, modifier = Modifier.fillMaxWidth(), singleLine = true, leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) }, placeholder = { Text("Search songs, artists, albums...") }, shape = RoundedCornerShape(16.dp))
    Spacer(Modifier.height(14.dp))
    when {
        loading -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        error != null -> Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp))
        query.trim().length < 2 -> Text("Type at least 2 characters to search.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(12.dp))
        results.isEmpty() -> Text("No results found.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(12.dp))
        else -> LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) { items(results) { item -> SearchResultCard(item, loadingVideoId, onPlayTrack) } }
    }
}

@Composable
private fun SearchResultCard(item: MusicSearchItem, loadingVideoId: String?, onPlayTrack: (MusicSearchItem) -> Unit) {
    Surface(Modifier.fillMaxWidth().clickable(enabled = item.videoId != null) { onPlayTrack(item) }, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(model = item.thumbnail, contentDescription = item.title, modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(item.title, fontWeight = FontWeight.SemiBold, maxLines = 2)
                if (item.subtitle.isNotBlank()) Text(item.subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 2)
            }
            if (item.videoId != null) {
                if (loadingVideoId == item.videoId) CircularProgressIndicator(Modifier.padding(8.dp))
                else IconButton(onClick = { onPlayTrack(item) }) { Icon(Icons.Default.PlayArrow, contentDescription = "Play ${item.title}") }
            }
        }
    }
}

@Composable
private fun ColumnScope.LibraryPlaceholder() {
    Column(Modifier.fillMaxWidth().weight(1f).padding(12.dp)) {
        Text("Your library", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("Favorites, playlists and history are coming next.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MiniPlayer(title: String?, artist: String?, artwork: String?, isPlaying: Boolean, error: String?, onPlayPause: () -> Unit, onOpenPlayer: () -> Unit) {
    Surface(Modifier.fillMaxWidth().clickable(enabled = title != null) { onOpenPlayer() }, shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (artwork != null) { AsyncImage(model = artwork, contentDescription = title, modifier = Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Crop); Spacer(Modifier.width(10.dp)) }
            Column(Modifier.weight(1f)) {
                Text(title ?: "No track selected", fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(if (error != null) error else (artist ?: "Yuki Music • background player"), color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 2)
            }
            if (title != null) IconButton(onClick = onPlayPause) { Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = if (isPlaying) "Pause" else "Play") }
        }
    }
}

@Composable
private fun FullPlayer(
    title: String?, artist: String?, artwork: String?, isPlaying: Boolean, positionMs: Long, durationMs: Long, error: String?,
    queueItems: List<MusicSearchItem>, lyrics: LyricsResult?, relatedSections: List<HomeSection>,
    onClose: () -> Unit, onPlayPause: () -> Unit, onPrevious: () -> Unit, onNext: () -> Unit, onSeek: (Long) -> Unit,
    onSelectQueue: (MusicSearchItem) -> Unit, onPlayRelated: (MusicSearchItem) -> Unit
) {
    var tab by remember { mutableIntStateOf(0) }
    val max = durationMs.coerceAtLeast(1L).toFloat()
    val value = positionMs.coerceIn(0L, durationMs.coerceAtLeast(1L)).toFloat()
    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") }
            Text("NOW PLAYING", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            IconButton(onClick = {}) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Song", "Lyrics", "Queue", "Related").forEachIndexed { index, label ->
                Surface(Modifier.clickable { tab = index }, shape = RoundedCornerShape(50), color = if (tab == index) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant) {
                    Text(label, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        when (tab) {
            0 -> SongPanel(title, artist, artwork, isPlaying, value, max, error, onPlayPause, onPrevious, onNext, onSeek)
            1 -> LyricsPanel(lyrics, positionMs)
            2 -> QueuePanel(queueItems, title, onSelectQueue)
            else -> RelatedPanel(relatedSections, onPlayRelated)
        }
    }
}

@Composable
private fun SongPanel(title: String?, artist: String?, artwork: String?, isPlaying: Boolean, value: Float, max: Float, error: String?, onPlayPause: () -> Unit, onPrevious: () -> Unit, onNext: () -> Unit, onSeek: (Long) -> Unit) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(22.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            if (artwork != null) AsyncImage(model = artwork, contentDescription = title, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Icon(Icons.Default.MusicNote, contentDescription = null, modifier = Modifier.size(72.dp))
            if (error != null) Surface(Modifier.align(Alignment.BottomCenter).padding(12.dp), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.errorContainer) { Text(error, modifier = Modifier.padding(10.dp), color = MaterialTheme.colorScheme.onErrorContainer) }
        }
        Spacer(Modifier.height(12.dp))
        Column(Modifier.fillMaxWidth()) {
            Text(title ?: "No track selected", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 2)
            Text(artist ?: "YouTube Music", color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        Slider(value = value, onValueChange = { onSeek(it.toLong()) }, valueRange = 0f..max)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(formatTime(value.toLong())); Text(formatTime(max.toLong())) }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = {}) { Icon(Icons.Default.FavoriteBorder, contentDescription = "Favorite") }
            IconButton(onClick = onPrevious) { Icon(Icons.Default.SkipPrevious, contentDescription = "Previous", modifier = Modifier.size(34.dp)) }
            Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primaryContainer) {
                IconButton(onClick = onPlayPause, modifier = Modifier.size(64.dp)) { Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(34.dp)) }
            }
            IconButton(onClick = onNext) { Icon(Icons.Default.SkipNext, contentDescription = "Next", modifier = Modifier.size(34.dp)) }
            IconButton(onClick = {}) { Icon(Icons.Default.Add, contentDescription = "Add to playlist") }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun LyricsPanel(lyrics: LyricsResult?, positionMs: Long) {
    if (lyrics == null || (lyrics.synced.isNullOrBlank() && lyrics.plain.isNullOrBlank())) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Lyrics not available yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        return
    }
    val lines = remember(lyrics) { parseLyrics(lyrics) }
    val active = remember(positionMs, lines) { lines.indexOfLast { it.timeMs != null && it.timeMs <= positionMs } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Lyrics", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        if (!lyrics.source.isNullOrBlank()) Text("Source: ${lyrics.source}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        lines.forEachIndexed { index, line ->
            Text(line.text.ifBlank { "♪" }, style = if (index == active) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium, fontWeight = if (index == active) FontWeight.Bold else FontWeight.Normal, color = if (index == active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun QueuePanel(queueItems: List<MusicSearchItem>, currentTitle: String?, onSelectQueue: (MusicSearchItem) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.QueueMusic, contentDescription = null); Spacer(Modifier.width(8.dp)); Text("Queue", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.height(8.dp))
        if (queueItems.isEmpty()) Text("Queue is still loading...", color = MaterialTheme.colorScheme.onSurfaceVariant)
        else LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(queueItems) { item ->
                Surface(Modifier.fillMaxWidth().clickable { onSelectQueue(item) }, shape = RoundedCornerShape(12.dp), color = if (item.title == currentTitle) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface) {
                    Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        AsyncImage(model = item.thumbnail, contentDescription = item.title, modifier = Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(item.title, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            Text(item.subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RelatedPanel(sections: List<HomeSection>, onPlay: (MusicSearchItem) -> Unit) {
    if (sections.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Related music is loading...", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        items(sections) { section ->
            Column {
                Text(section.title.ifBlank { "Related" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) { items(section.items) { item -> HomeMusicCard(item, onPlay) } }
            }
        }
    }
}

private fun parseLyrics(result: LyricsResult): List<LyricLine> {
    val synced = result.synced
    if (synced.isNullOrBlank()) return result.plain.orEmpty().lines().filter { it.isNotBlank() }.map { LyricLine(null, it.trim()) }
    val regex = Regex("\\[(\\d{1,2}):(\\d{2})(?:\\.(\\d{1,3}))?\\](.*)")
    return synced.lines().flatMap { raw ->
        val match = regex.matchEntire(raw.trim()) ?: return@flatMap listOf(LyricLine(null, raw.trim()))
        val min = match.groupValues[1].toLong()
        val sec = match.groupValues[2].toLong()
        val frac = match.groupValues[3].padEnd(3, '0').take(3).toLongOrNull() ?: 0L
        listOf(LyricLine((min * 60 + sec) * 1000 + frac, match.groupValues[4].trim()))
    }.filter { it.text.isNotBlank() }
}

private fun formatTime(ms: Long): String {
    val total = (ms.coerceAtLeast(0L) / 1000L).toInt()
    return String.format(Locale.US, "%d:%02d", total / 60, total % 60)
}
