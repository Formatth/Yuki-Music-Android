package com.formatth.yukimusic.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

private const val BASE_URL = "https://richmusic.vercel.app"

 data class MusicSearchItem(
    val title: String,
    val subtitle: String,
    val videoId: String?,
    val thumbnail: String?,
    val type: String = "song"
)

data class HomeSection(
    val title: String,
    val items: List<MusicSearchItem>
)

data class NextResponse(
    val queue: List<MusicSearchItem>,
    val lyricsBrowseId: String?,
    val relatedBrowseId: String?
)

data class LyricsResult(
    val synced: String?,
    val plain: String?,
    val source: String?
)

object MusicApi {
    private data class CachedUrl(val value: String, val createdAt: Long)
    private val playbackCache = ConcurrentHashMap<String, CachedUrl>()

    suspend fun getHome(): List<HomeSection> = withContext(Dispatchers.IO) {
        val connection = openGet("$BASE_URL/api/home")
        try {
            if (connection.responseCode !in 200..299) throw apiError("Home API", connection)
            parseHome(JSONObject(connection.inputStream.bufferedReader().use { it.readText() }))
        } finally {
            connection.disconnect()
        }
    }

    suspend fun searchSongs(query: String): List<MusicSearchItem> = withContext(Dispatchers.IO) {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val connection = openGet("$BASE_URL/api/search?q=$encoded&filter=songs")
        try {
            if (connection.responseCode !in 200..299) throw apiError("Search API", connection)
            parseSearch(JSONObject(connection.inputStream.bufferedReader().use { it.readText() }))
        } finally {
            connection.disconnect()
        }
    }

    suspend fun resolvePlaybackUrl(videoId: String): String = withContext(Dispatchers.IO) {
        require(videoId.isNotBlank()) { "Missing videoId" }

        val now = System.currentTimeMillis()
        playbackCache[videoId]?.let { cached ->
            if (now - cached.createdAt < 5 * 60 * 1000L) return@withContext cached.value
            playbackCache.remove(videoId)
        }

        val start = openGet("$BASE_URL/api/download-start?videoId=${URLEncoder.encode(videoId, "UTF-8")}")
        val startJson = try {
            if (start.responseCode !in 200..299) throw apiError("Playback start", start)
            JSONObject(start.inputStream.bufferedReader().use { it.readText() })
        } finally {
            start.disconnect()
        }

        val progressUrl = startJson.optString("progressUrl").ifBlank { startJson.optString("progress_url") }
        if (progressUrl.isBlank()) {
            throw IllegalStateException(startJson.optString("error").ifBlank { "Playback converter did not return a progress URL" })
        }

        repeat(45) {
            val progress = openGet("$BASE_URL/api/download-progress?progressUrl=${URLEncoder.encode(progressUrl, "UTF-8")}")
            val json = try {
                if (progress.responseCode !in 200..299) throw apiError("Playback progress", progress)
                JSONObject(progress.inputStream.bufferedReader().use { it.readText() })
            } finally {
                progress.disconnect()
            }

            val url = json.optString("url").ifBlank { json.optString("download_url") }
            if ((json.optBoolean("done") || json.optBoolean("success")) && url.isNotBlank()) {
                playbackCache[videoId] = CachedUrl(url, System.currentTimeMillis())
                return@withContext url
            }

            val statusText = json.optString("text")
            if (statusText.contains("error", ignoreCase = true)) throw IllegalStateException(statusText)
            delay(1000)
        }

        throw IllegalStateException("Playback conversion timed out")
    }

    suspend fun getNext(videoId: String): NextResponse = withContext(Dispatchers.IO) {
        val encoded = URLEncoder.encode(videoId, "UTF-8")
        val connection = openGet("$BASE_URL/api/next?videoId=$encoded")
        try {
            if (connection.responseCode !in 200..299) throw apiError("Queue API", connection)
            val root = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val array = root.optJSONArray("queue")
            val queue = mutableListOf<MusicSearchItem>()
            if (array != null) {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val title = item.optString("title").trim()
                    val id = item.optString("videoId").takeIf { it.isNotBlank() }
                    if (title.isBlank() || id == null) continue
                    queue += MusicSearchItem(
                        title = title,
                        subtitle = item.optString("artist").ifBlank { item.optString("subtitle") },
                        videoId = id,
                        thumbnail = item.optString("thumbnail").takeIf { it.isNotBlank() }
                    )
                }
            }
            NextResponse(
                queue = queue,
                lyricsBrowseId = root.optString("lyricsBrowseId").takeIf { it.isNotBlank() },
                relatedBrowseId = root.optString("relatedBrowseId").takeIf { it.isNotBlank() }
            )
        } finally {
            connection.disconnect()
        }
    }

    suspend fun getLyrics(title: String, artist: String, durationSeconds: Long, browseId: String?): LyricsResult = withContext(Dispatchers.IO) {
        val query = buildString {
            append("title=").append(URLEncoder.encode(title, "UTF-8"))
            append("&artist=").append(URLEncoder.encode(artist, "UTF-8"))
            append("&duration=").append(durationSeconds)
            if (!browseId.isNullOrBlank()) append("&browseId=").append(URLEncoder.encode(browseId, "UTF-8"))
        }
        val connection = openGet("$BASE_URL/api/lyrics?$query")
        try {
            if (connection.responseCode !in 200..299) throw apiError("Lyrics API", connection)
            val root = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            LyricsResult(
                synced = root.optString("synced").takeIf { it.isNotBlank() && it != "null" },
                plain = root.optString("plain").takeIf { it.isNotBlank() && it != "null" },
                source = root.optString("source").takeIf { it.isNotBlank() && it != "null" }
            )
        } finally {
            connection.disconnect()
        }
    }

    suspend fun getRelated(browseId: String): List<HomeSection> = withContext(Dispatchers.IO) {
        val connection = openGet("$BASE_URL/api/related?browseId=${URLEncoder.encode(browseId, "UTF-8")}")
        try {
            if (connection.responseCode !in 200..299) throw apiError("Related API", connection)
            parseHome(JSONObject(connection.inputStream.bufferedReader().use { it.readText() }))
        } finally {
            connection.disconnect()
        }
    }

    private fun openGet(rawUrl: String): HttpURLConnection =
        (URL(rawUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "Yuki-Music-Android/0.6")
        }

    private fun apiError(name: String, connection: HttpURLConnection) =
        IllegalStateException("$name returned HTTP ${connection.responseCode}")

    private fun parseHome(root: JSONObject): List<HomeSection> {
        val sections = root.optJSONArray("sections") ?: return emptyList()
        val result = mutableListOf<HomeSection>()
        for (i in 0 until sections.length()) {
            val section = sections.optJSONObject(i) ?: continue
            val title = section.optString("title").trim()
            val items = section.optJSONArray("items") ?: continue
            val parsed = mutableListOf<MusicSearchItem>()
            for (j in 0 until items.length()) {
                val item = items.optJSONObject(j) ?: continue
                val itemTitle = item.optString("title").trim()
                if (itemTitle.isBlank()) continue
                parsed += MusicSearchItem(
                    title = itemTitle,
                    subtitle = item.optString("subtitle").trim(),
                    videoId = item.optString("videoId").takeIf { it.isNotBlank() },
                    thumbnail = item.optString("thumbnail").takeIf { it.isNotBlank() },
                    type = item.optString("type").ifBlank { "song" }
                )
            }
            if (parsed.isNotEmpty()) result += HomeSection(title.ifBlank { "Yuki Music" }, parsed)
        }
        return result
    }

    private fun parseSearch(root: JSONObject): List<MusicSearchItem> {
        val result = mutableListOf<MusicSearchItem>()
        val sections = root.optJSONArray("sections") ?: return result
        for (i in 0 until sections.length()) {
            val section = sections.optJSONObject(i) ?: continue
            val items = section.optJSONArray("items") ?: continue
            for (j in 0 until items.length()) {
                val item = items.optJSONObject(j) ?: continue
                val videoId = item.optString("videoId").takeIf { it.isNotBlank() }
                val title = item.optString("title").trim()
                if (title.isBlank()) continue
                result += MusicSearchItem(
                    title = title,
                    subtitle = item.optString("subtitle").trim(),
                    videoId = videoId,
                    thumbnail = item.optString("thumbnail").takeIf { it.isNotBlank() },
                    type = item.optString("type").ifBlank { "song" }
                )
            }
        }
        return result.distinctBy { it.videoId ?: it.title }
    }
}
