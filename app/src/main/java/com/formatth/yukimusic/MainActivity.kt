package com.formatth.yukimusic

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.formatth.yukimusic.player.PlaybackService

class MainActivity : ComponentActivity() {
    private var mediaController: MediaController? = null
    private var controllerFuture: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
    private var isPlaying by mutableStateOf(false)

    private val demoMediaItem = MediaItem.Builder()
        .setUri("https://storage.googleapis.com/exoplayer-test-media-0/play.mp3")
        .setMediaId("yuki-demo")
        .setMediaMetadata(
            androidx.media3.common.MediaMetadata.Builder()
                .setTitle("Yuki Music Demo")
                .setArtist("Yuki Music")
                .build()
        )
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            YukiMusicTheme {
                YukiMusicApp(
                    isPlaying = isPlaying,
                    onPlayPause = ::toggleDemoPlayback
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val sessionToken = SessionToken(this, android.content.ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, sessionToken).buildAsync().also { future ->
            future.addListener(
                {
                    try {
                        mediaController = future.get()
                        isPlaying = mediaController?.isPlaying == true
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

    private fun toggleDemoPlayback() {
        val controller = mediaController ?: return
        if (controller.isPlaying) {
            controller.pause()
            isPlaying = false
            return
        }

        if (controller.currentMediaItem == null) {
            controller.setMediaItem(demoMediaItem)
            controller.prepare()
        }
        controller.play()
        isPlaying = true
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
    onPlayPause: () -> Unit
) {
    var selectedTab by mutableIntStateOf(0)

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
            Text(
                text = "Yuki Music",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = when (selectedTab) {
                    1 -> "Search music"
                    2 -> "Your library"
                    else -> "Listen to what you love"
                },
                color = Color.LightGray,
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(Modifier.height(24.dp))

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
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.height(52.dp),
                        tint = Color.White
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        if (isPlaying) "Yuki Music Demo" else "Nothing playing",
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        if (isPlaying) "Background player active" else "Tap play to test the player",
                        color = Color.Gray
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            Text("Quick access", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                QuickCard("Favorites", Icons.Default.FavoriteBorder, Modifier.weight(1f))
                QuickCard("Library", Icons.Default.LibraryMusic, Modifier.weight(1f))
            }

            Spacer(Modifier.weight(1f))
            MiniPlayer(isPlaying = isPlaying, onPlayPause = onPlayPause)
        }
    }
}

@Composable
private fun QuickCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = Color(0xFF171719)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null)
            Spacer(Modifier.padding(4.dp))
            Text(title, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun MiniPlayer(isPlaying: Boolean, onPlayPause: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = Color(0xFF19191C)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (isPlaying) "Yuki Music Demo" else "No track selected",
                    fontWeight = FontWeight.SemiBold
                )
                Text("Yuki Music", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = onPlayPause) {
                Icon(
                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play"
                )
            }
        }
    }
}
