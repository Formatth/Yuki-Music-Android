package com.formatth.yukimusic.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL

auto private const val BASE_URL = "https://richmusic.vercel.app"

data class MusicSearchItem(
    val title: String,
    val subtitle: String,
    val videoId: String?,
    val thumbnail: String?
)

object MusicApi {
    suspend fun searchSongs(query: String): List<MusicSearchItem> = withContext(Dispatchers.IO) {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val connection = openGet("$BASE_URL/api/search?q=$encoded&filter=songs")
        try {
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("API returned HTTP ${connection.responseCode}")
            }
            parseSearch(connection.inputStream.bufferedReader().use { it.readText() }.let(::JSONObject))
        } finally {
            connection.disconnect()
        }
    }

    suspend fun resolvePlaybackUrl(videoId: String): String = withContext(Dispatchers.IO) {
        require(videoId.isNotBlank()) { "Missing videoId" }

        val start = openGet("$BASE_URL/api/download-start?videoId=${URLEncoder.encode(videoId, "UTF-8")}")
        val startJson = try {
            if (start.responseCode !in 200..299) throw IllegalStateException("Playback start failed: HTTP ${start.responseCode}")
            JSONObject(start.inputStream.bufferedReader().use { it.readText() })
        } finally {
            start.disconnect()
        }

        val progressUrl = startJson.optString("progressUrl")
        if (progressUrl.isBlank()) throw IllegalStateException("Playback converter did not return a progress URL")

        repeat(30) {
            val progress = openGet(progressUrl)
            val json = try {
                if (progress.responseCode !in 200..299) throw IllegalStateException("Playback progress failed: HTTP ${progress.responseCode}")
                JSONObject(progress.inputStream.bufferedReader().use { it.readText() })
            } finally {
                progress.disconnect()
            }

            if (json.optBoolean("done") && json.optString("url").isNotBlank()) {
                return@withContext json.optString("url")
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
            setRequestProperty("User-Agent", "Yuki-Music-Android/0.2")
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
                    thumbnail = item.optString("thumbnail").takeIf { it.isNotBlank() }
                )
            }
        }
        return result.distinctBy { it.videoId ?: it.title }
    }
}
