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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import coil.compose.AsyncImage
import com.formatth.yukimusic.data.HomeSection
import com.formatth.yukimusic.data.MusicApi
import com.formatth.yukimusic.data.MusicSearchItem
import com.formatth.yukimusic.player.PlaybackService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var mediaController: MediaController? = null
    private var controllerFuture: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
    private var isPlaying by mutableStateOf(false)
    private var currentTitle by mutableStateOf<String?>(null)
    private var currentArtist by mutableStateOf<String?>(null)
    private var currentArtwork by mutableStateOf<String?>(null)
    private var playbackError by mutableStateOf<String?>(null)
    private var loadingVideoId by mutableStateOf<String?>(null)

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
                    playbackError = playbackError,
                    loadingVideoId = loadingVideoId,
                    onPlayTrack = ::playTrack,
                    onPlayPause = ::togglePlayback
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, token).buildAsync().also { future ->
            future.addListener({
                try {
                    mediaController = future.get()
                    syncPlayerState()
                } catch (_: Exception) {
                    mediaController = null
                }
            }, mainExecutor)
        }
    }

    override fun onStop() {
        mediaController?.release()
        mediaController = null
        controllerFuture = null
        super.onStop()
    }

    private fun syncPlayerState() {
        val controller = mediaController ?: return
        isPlaying = controller.isPlaying
        currentTitle = controller.mediaMetadata.title?.toString()
        currentArtist = controller.mediaMetadata.artist?.toString()
        currentArtwork = controller.mediaMetadata.artworkUri?.toString()
    }

    private fun playTrack(item: MusicSearchItem) {
        val videoId = item.videoId ?: return
        if (loadingVideoId != null) return
        playbackError = null
        loadingVideoId = videoId

        lifecycleScope.launch {
            try {
                val audioUrl = MusicApi.resolvePlaybackUrl(videoId)
                val controller = mediaController ?: throw IllegalStateException("Player service is not ready")
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
                currentTitle = item.title
                currentArtist = item.subtitle.ifBlank { "YouTube Music" }
                currentArtwork = item.thumbnail
                isPlaying = true
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                playbackError = e.message ?: "Unable to start playback"
                isPlaying = false
            } finally {
                loadingVideoId = null
            }
        }
    }

    private fun togglePlayback() {
        val controller = mediaController ?: return
        if (controller.isPlaying) controller.pause()
        else if (controller.currentMediaItem != null) controller.play()
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
    playbackError: String?,
    loadingVideoId: String?,
    onPlayTrack: (MusicSearchItem) -> Unit,
    onPlayPause: () -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<MusicSearchItem>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var homeSections by remember { mutableStateOf<List<HomeSection>>(emptyList()) }
    var homeLoading by remember { mutableStateOf(false) }
    var homeError by remember { mutableStateOf<String?>(null) }

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
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
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
            MiniPlayer(currentTitle, currentArtist, currentArtwork, isPlaying, playbackError, onPlayPause)
        }
    }
}

@Composable
private fun ColumnScope.HomeScreen(
    sections: List<HomeSection>,
    loading: Boolean,
    error: String?,
    onPlayTrack: (MusicSearchItem) -> Unit
) {
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
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(section.items) { item -> HomeMusicCard(item, onPlayTrack) }
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeMusicCard(item: MusicSearchItem, onPlayTrack: (MusicSearchItem) -> Unit) {
    Column(Modifier.width(148.dp)) {
        Box(
            Modifier.fillMaxWidth().height(148.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            AsyncImage(model = item.thumbnail, contentDescription = item.title, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            if (item.videoId != null) {
                Surface(
                    modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    IconButton(onClick = { onPlayTrack(item) }) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Play ${item.title}")
                    }
                }
            }
        }
        Spacer(Modifier.height(7.dp))
        Text(item.title, fontWeight = FontWeight.SemiBold, maxLines = 2)
        if (item.subtitle.isNotBlank()) Text(item.subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 1)
    }
}

@Composable
private fun ColumnScope.SearchScreen(
    query: String,
    onQueryChange: (String) -> Unit,
    results: List<MusicSearchItem>,
    loading: Boolean,
    error: String?,
    loadingVideoId: String?,
    onPlayTrack: (MusicSearchItem) -> Unit
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        placeholder = { Text("Search songs, artists, albums...") },
        shape = RoundedCornerShape(16.dp)
    )
    Spacer(Modifier.height(14.dp))
    when {
        loading -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        error != null -> Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp))
        query.trim().length < 2 -> Text("Type at least 2 characters to search.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(12.dp))
        results.isEmpty() -> Text("No results found.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(12.dp))
        else -> LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(results) { item -> SearchResultCard(item, loadingVideoId, onPlayTrack) }
        }
    }
}

@Composable
private fun SearchResultCard(item: MusicSearchItem, loadingVideoId: String?, onPlayTrack: (MusicSearchItem) -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(model = item.thumbnail, contentDescription = item.title, modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(item.title, fontWeight = FontWeight.SemiBold, maxLines = 2)
                if (item.subtitle.isNotBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Text(item.subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                }
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
private fun MiniPlayer(
    title: String?,
    artist: String?,
    artwork: String?,
    isPlaying: Boolean,
    error: String?,
    onPlayPause: () -> Unit
) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (artwork != null) {
                AsyncImage(model = artwork, contentDescription = title, modifier = Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Crop)
                Spacer(Modifier.width(10.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title ?: "No track selected", fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(
                    if (error != null) error else (artist ?: "Yuki Music • background player"),
                    color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2
                )
            }
            if (title != null) {
                IconButton(onClick = onPlayPause) {
                    Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = if (isPlaying) "Pause" else "Play")
                }
            }
        }
    }
}
