package com.formatth.yukimusic

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.formatth.yukimusic.data.MusicApi
import com.formatth.yukimusic.data.MusicSearchItem
import com.formatth.yukimusic.player.PlaybackService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var mediaController: MediaController? = null
    private var controllerFuture: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
    private var isPlaying by mutableStateOf(false)
    private var currentTitle by mutableStateOf<String?>(null)
    private var playbackError by mutableStateOf<String?>(null)
    private var loadingVideoId by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        }

        setContent {
            YukiMusicTheme {
                YukiMusicApp(
                    isPlaying = isPlaying,
                    currentTitle = currentTitle,
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
        val sessionToken = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, sessionToken).buildAsync().also { future ->
            future.addListener(
                {
                    try {
                        mediaController = future.get()
                        isPlaying = mediaController?.isPlaying == true
                        currentTitle = mediaController?.mediaMetadata?.title?.toString()
                    } catch (_: Exception) {
                        mediaController = null
                    }
                },
                ContextCompat.getMainExecutor(this)
            )
        }
    }

    override fun onStop() {
        mediaController?.release()
        mediaController = null
        controllerFuture = null
        super.onStop()
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
                            .setArtworkUri(item.thumbnail?.let { android.net.Uri.parse(it) })
                            .build()
                    )
                    .build()

                controller.setMediaItem(mediaItem)
                controller.prepare()
                controller.play()
                currentTitle = item.title
                isPlaying = true
            } catch (e: Exception) {
                playbackError = e.message ?: "Unable to start playback"
                isPlaying = false
            } finally {
                loadingVideoId = null
            }
        }
    }

    private fun togglePlayback() {
        val controller = mediaController ?: return
        if (controller.isPlaying) {
            controller.pause()
            isPlaying = false
        } else if (controller.currentMediaItem != null) {
            controller.play()
            isPlaying = true
        }
    }
}

@Composable
private fun YukiMusicTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme.copy(
            background = Color(0xFF0B0B0D),
            surface = Color(0xFF141416),
            surfaceVariant = Color(0xFF202024)
        ),
        content = content
    )
}

@Composable
private fun YukiMusicApp(
    isPlaying: Boolean,
    currentTitle: String?,
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
            results = emptyList()
            error = e.message ?: "Search failed"
        } finally {
            loading = false
        }
    }

    Scaffold(
        containerColor = Color(0xFF0B0B0D),
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF111113)) {
                val items = listOf(
                    "Home" to Icons.Default.Home,
                    "Search" to Icons.Default.Search,
                    "Library" to Icons.Default.LibraryMusic
                )
                items.forEachIndexed { index, (label, icon) ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        icon = { Icon(icon, contentDescription = label) },
                        label = { Text(label) }
                    )
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            Text("Yuki Music", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(
                when (selectedTab) {
                    1 -> "Search YouTube Music"
                    2 -> "Your library"
                    else -> "Listen to what you love"
                },
                color = Color.LightGray,
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(18.dp))

            if (selectedTab == 1) {
                SearchScreen(query, { query = it }, results, loading, error, loadingVideoId, onPlayTrack)
            } else {
                HomeScreen(isPlaying, currentTitle, playbackError, onPlayPause)
            }

            Spacer(Modifier.weight(1f))
            MiniPlayer(currentTitle, isPlaying, onPlayPause)
        }
    }
}

@Composable
private fun SearchScreen(
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
        loading -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        error != null -> Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp))
        query.trim().length < 2 -> Text("Type at least 2 characters to search.", color = Color.Gray, modifier = Modifier.padding(12.dp))
        results.isEmpty() -> Text("No results found.", color = Color.Gray, modifier = Modifier.padding(12.dp))
        else -> LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(results) { item -> SearchResultCard(item, loadingVideoId, onPlayTrack) }
        }
    }
}

@Composable
private fun SearchResultCard(
    item: MusicSearchItem,
    loadingVideoId: String?,
    onPlayTrack: (MusicSearchItem) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF171719)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(item.title, fontWeight = FontWeight.SemiBold)
                if (item.subtitle.isNotBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Text(item.subtitle, color = Color.Gray, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                }
            }
            if (item.videoId != null) {
                if (loadingVideoId == item.videoId) {
                    CircularProgressIndicator(modifier = Modifier.padding(8.dp))
                } else {
                    IconButton(onClick = { onPlayTrack(item) }) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Play ${item.title}")
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeScreen(
    isPlaying: Boolean,
    currentTitle: String?,
    playbackError: String?,
    onPlayPause: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(210.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Color(0xFF18181B)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = null,
                modifier = Modifier.height(52.dp),
                tint = Color.White
            )
            Spacer(Modifier.height(10.dp))
            Text(currentTitle ?: "Nothing playing", fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(
                if (isPlaying) "Playing in background" else "Search a song to start playback",
                color = Color.Gray
            )
            if (playbackError != null) {
                Spacer(Modifier.height(8.dp))
                Text(playbackError, color = MaterialTheme.colorScheme.error, maxLines = 2)
            }
            if (currentTitle != null) {
                Spacer(Modifier.height(10.dp))
                Button(onClick = onPlayPause) { Text(if (isPlaying) "Pause" else "Resume") }
            }
        }
    }
    Spacer(Modifier.height(20.dp))
    Text("Quick access", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(10.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        QuickCard("Favorites", Icons.Default.FavoriteBorder, Modifier.weight(1f))
        QuickCard("Library", Icons.Default.LibraryMusic, Modifier.weight(1f))
    }
}

@Composable
private fun QuickCard(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(18.dp), color = Color(0xFF171719)) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null)
            Spacer(Modifier.padding(4.dp))
            Text(title, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun MiniPlayer(currentTitle: String?, isPlaying: Boolean, onPlayPause: () -> Unit) {
    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = Color(0xFF19191C)) {
        Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(currentTitle ?: "No track selected", fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text("Yuki Music • background player", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
            }
            if (currentTitle != null) {
                IconButton(onClick = onPlayPause) {
                    Icon(
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play"
                    )
                }
            }
        }
    }
}
