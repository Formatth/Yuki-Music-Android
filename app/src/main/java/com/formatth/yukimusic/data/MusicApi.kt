package com.formatth.yukimusic.data

import kotlinx.coroutines.Dispatchers
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
    val thumbnail: String?
)

object MusicApi {
    suspend fun searchSongs(query: String): List<MusicSearchItem> = withContext(Dispatchers.IO) {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val url = URL("$BASE_URL/api/search?q=$encoded&filter=songs")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "Yuki-Music-Android/0.1")
        }

        try {
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("API returned HTTP ${connection.responseCode}")
            }

            val body = connection.inputStream.bufferedReader().use { it.readText() }
            parseSearch(JSONObject(body))
        } finally {
            connection.disconnect()
        }
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
