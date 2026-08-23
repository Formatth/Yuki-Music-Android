package com.formatth.yukimusic.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL

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

object MusicApi {
    suspend fun getHome(): List<HomeSection> = withContext(Dispatchers.IO) {
        val connection = openGet("$BASE_URL/api/home")
        try {
            if (connection.responseCode !in 200..299) {
                throw apiError("Home API", connection)
            }
            parseHome(JSONObject(connection.inputStream.bufferedReader().use { it.readText() }))
        } finally {
            connection.disconnect()
        }
    }

    suspend fun searchSongs(query: String): List<MusicSearchItem> = withContext(Dispatchers.IO) {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val connection = openGet("$BASE_URL/api/search?q=$encoded&filter=songs")
        try {
            if (connection.responseCode !in 200..299) {
                throw apiError("Search API", connection)
            }
            parseSearch(JSONObject(connection.inputStream.bufferedReader().use { it.readText() }))
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Resolve a song through the same Rich Music converter flow used by
     * YT-Music-Mod: download-start -> download-progress -> final URL.
     */
    suspend fun resolvePlaybackUrl(videoId: String): String = withContext(Dispatchers.IO) {
        require(videoId.isNotBlank()) { "Missing videoId" }

        val start = openGet(
            "$BASE_URL/api/download-start?videoId=${URLEncoder.encode(videoId, "UTF-8")}"
        )
        val startJson = try {
            if (start.responseCode !in 200..299) {
                throw apiError("Playback start", start)
            }
            JSONObject(start.inputStream.bufferedReader().use { it.readText() })
        } finally {
            start.disconnect()
        }

        // Rich Music returns progressUrl. Keep the snake_case fallback for
        // compatibility with converter responses.
        val progressUrl = startJson.optString("progressUrl").ifBlank {
            startJson.optString("progress_url")
        }
        if (progressUrl.isBlank()) {
            throw IllegalStateException(
                startJson.optString("error").ifBlank {
                    "Playback converter did not return a progress URL"
                }
            )
        }

        // IMPORTANT: do not poll the third-party converter directly. The
        // Rich Music backend exposes /api/download-progress and validates the
        // progress host before forwarding the result.
        repeat(45) {
            val encodedProgress = URLEncoder.encode(progressUrl, "UTF-8")
            val progress = openGet(
                "$BASE_URL/api/download-progress?progressUrl=$encodedProgress"
            )
            val json = try {
                if (progress.responseCode !in 200..299) {
                    throw apiError("Playback progress", progress)
                }
                JSONObject(progress.inputStream.bufferedReader().use { it.readText() })
            } finally {
                progress.disconnect()
            }

            val url = json.optString("url").ifBlank {
                json.optString("download_url")
            }
            if ((json.optBoolean("done") || json.optBoolean("success")) && url.isNotBlank()) {
                return@withContext url
            }

            val statusText = json.optString("text")
            if (statusText.contains("error", ignoreCase = true)) {
                throw IllegalStateException(statusText)
            }
            delay(1000)
        }

        throw IllegalStateException("Playback conversion timed out")
    }

    private fun openGet(rawUrl: String): HttpURLConnection =
        (URL(rawUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "Yuki-Music-Android/0.5")
        }

    private fun apiError(name: String, connection: HttpURLConnection): IllegalStateException {
        return IllegalStateException("$name returned HTTP ${connection.responseCode}")
    }

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

            if (parsed.isNotEmpty()) {
                result += HomeSection(title.ifBlank { "Yuki Music" }, parsed)
            }
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
