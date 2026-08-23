package com.formatth.yukimusic

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import com.formatth.yukimusic.data.*
import com.formatth.yukimusic.player.PlaybackService
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

private const val API = "https://richmusic.vercel.app"
private data class QueueMeta(val item: MusicSearchItem, val durationMs: Long, val queue: List<MusicSearchItem>, val lyricsId: String?, val relatedId: String?)
private data class LyricLine(val ms: Long?, val text: String)

class FixedMainActivity : ComponentActivity() {
    private var controller: MediaController? = null
    private var future: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
    private var playing by mutableStateOf(false)
    private var title by mutableStateOf<String?>(null)
    private var artist by mutableStateOf<String?>(null)
    private var artwork by mutableStateOf<String?>(null)
    private var videoId by mutableStateOf<String?>(null)
    private var loading by mutableStateOf<String?>(null)
    private var position by mutableLongStateOf(0)
    private var duration by mutableLongStateOf(0)
    private var lyrics by mutableStateOf<LyricsResult?>(null)
    private var queue by mutableStateOf<List<MusicSearchItem>>(emptyList())
    private var related by mutableStateOf<List<HomeSection>>(emptyList())
    private var lyricsBrowseId: String? = null
    private var anchor = 0L
    private var anchorClock = 0L

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(v: Boolean) { playing = v; if (v) startClock() else position = controller?.currentPosition?.coerceAtLeast(0) ?: position; sync() }
        override fun onMediaItemTransition(item: MediaItem?, reason: Int) { val id = item?.mediaId ?: return; switchTo(id) }
        override fun onPlaybackStateChanged(state: Int) { sync(); if (state == Player.STATE_READY && controller?.isPlaying == true) startClock(); if (state == Player.STATE_ENDED) lifecycleScope.launch { preloadNext(); delay(250); controller?.takeIf { it.hasNextMediaItem() }?.seekToNextMediaItem() } }
    }

    override fun onCreate(b: Bundle?) { super.onCreate(b); if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001); setContent { Theme { App() } } }
    override fun onStart() { super.onStart(); if (controller != null) { sync(); return }; val token = SessionToken(this, ComponentName(this, PlaybackService::class.java)); future = MediaController.Builder(this, token).buildAsync().also { f -> f.addListener({ try { controller = f.get(); controller?.addListener(listener); sync() } catch (_: Exception) {} }, mainExecutor) } }
    override fun onDestroy() { controller?.removeListener(listener); controller?.release(); future?.cancel(false); super.onDestroy() }

    private fun startClock() { anchor = controller?.currentPosition?.coerceAtLeast(0) ?: position; anchorClock = SystemClock.elapsedRealtime() }
    private fun sync() { val c = controller ?: return; playing = c.isPlaying; title = c.mediaMetadata.title?.toString() ?: title; artist = c.mediaMetadata.artist?.toString() ?: artist; artwork = c.mediaMetadata.artworkUri?.toString() ?: artwork; videoId = c.currentMediaItem?.mediaId ?: videoId; val d = c.duration; if (d > 0) duration = d; val p = c.currentPosition.coerceAtLeast(0); position = if (p > 0 || !c.isPlaying) p else if (anchorClock > 0) (anchor + SystemClock.elapsedRealtime() - anchorClock).coerceAtMost(duration.takeIf { it > 0 } ?: Long.MAX_VALUE) else 0 }
    private fun play(item: MusicSearchItem) { val id = item.videoId ?: return; val c = controller ?: return; if (id == videoId && c.currentMediaItem != null) { if (!c.isPlaying) c.play(); return }; loading = id; title = item.title; artist = clean(item.subtitle); artwork = item.thumbnail; videoId = id; position = 0; duration = 0; lyrics = null; lifecycleScope.launch { try { val meta = async { getQueueMeta(id) }; val url = MusicApi.resolvePlaybackUrl(id); val media = MediaItem.Builder().setUri(url).setMediaId(id).setMediaMetadata(MediaMetadata.Builder().setTitle(item.title).setArtist(clean(item.subtitle)).setArtworkUri(item.thumbnail?.let(Uri::parse)).build()).build(); c.setMediaItem(media); c.prepare(); c.play(); startClock(); sync(); val q = meta.await(); applyMeta(q); launch { preloadNext() } } catch (_: Exception) { } finally { loading = null } } }
    private fun switchTo(id: String) { videoId = id; position = 0; lyrics = null; lifecycleScope.launch { val q = getQueueMeta(id) ?: return@launch; applyMeta(q); launch { preloadNext() } } }
    private fun applyMeta(meta: QueueMeta?) { if (meta == null) return; val q = meta.item; queue = meta.queue; lyricsBrowseId = meta.lyricsId; title = q.title; artist = clean(q.subtitle); artwork = q.thumbnail ?: artwork; duration = meta.durationMs.takeIf { it > 0 } ?: duration; lifecycleScope.launch { try { lyrics = MusicApi.getLyrics(q.title, clean(q.subtitle), meta.durationMs / 1000, meta.lyricsId) } catch (_: Exception) {} }; if (!meta.relatedId.isNullOrBlank()) lifecycleScope.launch { try { related = MusicApi.getRelated(meta.relatedId!!) } catch (_: Exception) {} } }
    private suspend fun getQueueMeta(id: String): QueueMeta? = withContext(Dispatchers.IO) { try { val u = "$API/api/next?videoId=${URLEncoder.encode(id, "UTF-8")}"; val c = URL(u).openConnection() as HttpURLConnection; c.connectTimeout = 8000; c.readTimeout = 12000; val root = JSONObject(c.inputStream.bufferedReader().use { it.readText() }); val a = root.optJSONArray("queue") ?: return@withContext null; val all = mutableListOf<MusicSearchItem>(); var current: QueueMeta? = null; for (i in 0 until a.length()) { val o = a.optJSONObject(i) ?: continue; val vid = o.optString("videoId").takeIf { it.isNotBlank() } ?: continue; val item = MusicSearchItem(o.optString("title"), o.optString("artist"), vid, o.optString("thumbnail").takeIf { it.isNotBlank() }, "song"); all += item; if (vid == id) current = QueueMeta(item, parseDuration(o.optString("duration")), emptyList(), root.optString("lyricsBrowseId").takeIf { it.isNotBlank() }, root.optString("relatedBrowseId").takeIf { it.isNotBlank() }) }; current?.copy(queue = all) } catch (_: Exception) { null } }
    private fun parseDuration(s: String): Long { val p = s.split(":"); if (p.size != 2) return 0; return ((p[0].toLongOrNull() ?: 0) * 60 + (p[1].toLongOrNull() ?: 0)) * 1000 }
    private suspend fun preloadNext() { val c = controller ?: return; val id = c.currentMediaItem?.mediaId ?: videoId ?: return; val r = withContext(Dispatchers.IO) { try { MusicApi.getNext(id) } catch (_: Exception) { return@withContext null } } ?: return; if (r.queue.isNotEmpty()) queue = r.queue; val idx = r.queue.indexOfFirst { it.videoId == id }; val next = r.queue.getOrNull(if (idx >= 0) idx + 1 else 1) ?: return; val nid = next.videoId ?: return; if ((0 until c.mediaItemCount).any { c.getMediaItemAt(it).mediaId == nid }) return; try { val url = MusicApi.resolvePlaybackUrl(nid); c.addMediaItem(MediaItem.Builder().setUri(url).setMediaId(nid).setMediaMetadata(MediaMetadata.Builder().setTitle(next.title).setArtist(clean(next.subtitle)).setArtworkUri(next.thumbnail?.let(Uri::parse)).build()).build()) } catch (_: Exception) {} }
    private fun seek(ms: Long) { controller?.seekTo(ms); position = ms; startClock() }
    private fun clean(s: String) = s.substringBefore(" • ").substringBefore(" · ").trim().ifBlank { "YouTube Music" }

    @Composable private fun App() { var tab by remember { mutableIntStateOf(0) }; var q by remember { mutableStateOf("") }; var home by remember { mutableStateOf<List<HomeSection>>(emptyList()) }; var search by remember { mutableStateOf<List<MusicSearchItem>>(emptyList()) }; var show by remember { mutableStateOf(false) }; var searchLoading by remember { mutableStateOf(false) }
        LaunchedEffect(tab) { if (tab == 0 && home.isEmpty()) home = try { MusicApi.getHome() } catch (_: Exception) { emptyList() } }
        LaunchedEffect(q, tab) { if (tab != 1 || q.trim().length < 2) return@LaunchedEffect; delay(300); searchLoading = true; search = try { MusicApi.searchSongs(q) } catch (_: Exception) { emptyList() }; searchLoading = false }
        LaunchedEffect(show, title) { if (!show || title == null) return@LaunchedEffect; while (true) { sync(); delay(250) } }
        Scaffold(bottomBar = { if (!show) NavigationBar { listOf("Home" to Icons.Default.Home, "Search" to Icons.Default.Search, "Library" to Icons.Default.LibraryMusic).forEachIndexed { i, x -> NavigationBarItem(tab == i, { tab = i }, { Icon(x.second, x.first) }, { Text(x.first) }) } } }) { pad -> if (show) PlayerScreen({ show = false }) else Column(Modifier.fillMaxSize().padding(pad).padding(16.dp)) { Text("Yuki Music", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold); Text(if (tab == 1) "Search YouTube Music" else "Listen to what you love", color = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.height(14.dp)); if (tab == 0) Home(home) { play(it); show = true } else if (tab == 1) Search(q, { q = it }, search, searchLoading) { play(it); show = true } else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Your library") }; if (title != null) { Spacer(Modifier.height(10.dp)); Mini() } } }
    }
    @Composable private fun Home(sections: List<HomeSection>, onPlay: (MusicSearchItem) -> Unit) { LazyColumn(verticalArrangement = Arrangement.spacedBy(18.dp)) { items(sections) { s -> Column { Text(s.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) { items(s.items) { Card(it, onPlay) } } } } } }
    @Composable private fun Search(q: String, onQ: (String) -> Unit, items: List<MusicSearchItem>, loading: Boolean, onPlay: (MusicSearchItem) -> Unit) { OutlinedTextField(q, onQ, Modifier.fillMaxWidth(), singleLine = true, leadingIcon = { Icon(Icons.Default.Search, null) }, placeholder = { Text("Search songs, artists, albums...") }); Spacer(Modifier.height(12.dp)); if (loading) CircularProgressIndicator() else LazyColumn { items(items) { x -> Row(Modifier.fillMaxWidth().clickable { onPlay(x) }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) { AsyncImage(x.thumbnail, x.title, Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)), ContentScale.Crop); Spacer(Modifier.width(10.dp)); Column(Modifier.weight(1f)) { Text(x.title, fontWeight = FontWeight.SemiBold); Text(x.subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }; Icon(Icons.Default.PlayArrow, null) } } } }
    @Composable private fun Card(x: MusicSearchItem, onPlay: (MusicSearchItem) -> Unit) { Column(Modifier.width(148.dp).clickable { onPlay(x) }) { AsyncImage(x.thumbnail, x.title, Modifier.size(148.dp).clip(RoundedCornerShape(14.dp)), ContentScale.Crop); Spacer(Modifier.height(6.dp)); Text(x.title, fontWeight = FontWeight.SemiBold, maxLines = 2); Text(x.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) } }
    @Composable private fun Mini() { Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) { Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) { AsyncImage(artwork, title, Modifier.size(50.dp).clip(RoundedCornerShape(8.dp)), ContentScale.Crop); Spacer(Modifier.width(8.dp)); Column(Modifier.weight(1f)) { Text(title ?: "", maxLines = 1); Text(artist ?: "", style = MaterialTheme.typography.bodySmall) }; IconButton({ if (playing) controller?.pause() else controller?.play() }) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null) } } } }
    @Composable private fun PlayerScreen(back: () -> Unit) { var tab by remember { mutableIntStateOf(0) }; Column(Modifier.fillMaxSize().padding(12.dp)) { Row(verticalAlignment = Alignment.CenterVertically) { IconButton(back) { Icon(Icons.Default.ArrowBack, "Back") }; Text("NOW PLAYING", Modifier.weight(1f), fontWeight = FontWeight.Bold); Icon(Icons.Default.MoreVert, null) }; Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("Song", "Lyrics", "Queue", "Related").forEachIndexed { i, s -> Surface(Modifier.clickable { tab = i }, RoundedCornerShape(50), color = if (tab == i) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant) { Text(s, Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) } } }; Spacer(Modifier.height(12.dp)); when (tab) { 0 -> Song(); 1 -> Lyrics(); 2 -> Queue(); 3 -> Related() } } }
    @Composable private fun Song() { val max = duration.coerceAtLeast(1); Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) { Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(22.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) { AsyncImage(artwork, title, Modifier.fillMaxSize(), ContentScale.Crop) }; Spacer(Modifier.height(10.dp)); Column(Modifier.fillMaxWidth()) { Text(title ?: "", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); Text(artist ?: "", color = MaterialTheme.colorScheme.onSurfaceVariant) }; Slider(position.toFloat(), { seek(it.toLong()) }, valueRange = 0f..max.toFloat()); Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Text(time(position)); Text(time(max)) }; Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly, Alignment.CenterVertically) { IconButton({ controller?.seekToPreviousMediaItem() }) { Icon(Icons.Default.SkipPrevious, null) }; FilledIconButton({ if (playing) controller?.pause() else controller?.play() }) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null) }; IconButton({ controller?.seekToNextMediaItem() }) { Icon(Icons.Default.SkipNext, null) } } } }
    @Composable private fun Lyrics() { val l = lyrics; if (l == null) Box(Modifier.fillMaxSize(), Alignment.Center) { Text("Lyrics not available yet.") } else { val lines = remember(l) { parse(l) }; val active = lines.indexOfLast { it.ms != null && it.ms <= position }; Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) { Text("Lyrics", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); lines.forEachIndexed { i, x -> Text(x.text, style = if (i == active) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium, color = if (i == active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = if (i == active) FontWeight.Bold else FontWeight.Normal) } } } }
    @Composable private fun Queue() { LazyColumn { items(queue) { x -> Row(Modifier.fillMaxWidth().clickable { play(x) }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) { AsyncImage(x.thumbnail, x.title, Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)), ContentScale.Crop); Spacer(Modifier.width(10.dp)); Column { Text(x.title); Text(x.subtitle, style = MaterialTheme.typography.bodySmall) } } } } }
    @Composable private fun Related() { LazyColumn { items(related) { s -> Text(s.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold); LazyRow { items(s.items) { Card(it) { play(it) } } } } } }
    private fun parse(r: LyricsResult): List<LyricLine> { val s = r.synced; if (s.isNullOrBlank()) return r.plain.orEmpty().lines().filter(String::isNotBlank).map { LyricLine(null, it) }; val re = Regex("\\[(\\d+):(\\d{2})(?:\\.(\\d{1,3}))?\\](.*)"); return s.lines().mapNotNull { m -> val x = re.matchEntire(m.trim()) ?: return@mapNotNull null; val f = x.groupValues[3].padEnd(3, '0').take(3).toLong(); LyricLine((x.groupValues[1].toLong() * 60 + x.groupValues[2].toLong()) * 1000 + f, x.groupValues[4].trim()) }.filter { it.text.isNotBlank() } }
    private fun time(ms: Long): String { val t = (ms.coerceAtLeast(0) / 1000).toInt(); return String.format(Locale.US, "%d:%02d", t / 60, t % 60) }
}

@Composable private fun Theme(content: @Composable () -> Unit) { MaterialTheme(if (isSystemInDarkTheme()) darkColorScheme(background = Color(0xFF0B0B0D), surface = Color(0xFF151518)) else lightColorScheme(), content = content) }
