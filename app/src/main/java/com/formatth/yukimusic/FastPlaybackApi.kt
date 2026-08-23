package com.formatth.yukimusic

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

private const val PLAYBACK_API = "https://richmusic.vercel.app"

object FastPlaybackApi {
    suspend fun resolvePlaybackUrl(videoId: String): String = withContext(Dispatchers.IO) {
        val start = get("$PLAYBACK_API/api/download-start?videoId=${URLEncoder.encode(videoId, "UTF-8")}")
        val progressUrl = start.optString("progressUrl").ifBlank { start.optString("progress_url") }
        if (progressUrl.isBlank()) throw IllegalStateException(start.optString("error").ifBlank { "Playback converter did not return a progress URL" })
        repeat(180) {
            val p = get("$PLAYBACK_API/api/download-progress?progressUrl=${URLEncoder.encode(progressUrl, "UTF-8")}")
            val url = p.optString("url").ifBlank { p.optString("download_url") }
            if ((p.optBoolean("done") || p.optBoolean("success")) && url.isNotBlank()) return@withContext url
            if (p.optString("text").contains("error", true)) throw IllegalStateException(p.optString("text"))
            delay(250)
        }
        throw IllegalStateException("Playback conversion timed out")
    }

    private fun get(raw: String): JSONObject {
        val c = URL(raw).openConnection() as HttpURLConnection
        c.requestMethod = "GET"
        c.connectTimeout = 8000
        c.readTimeout = 15000
        c.setRequestProperty("Accept", "application/json")
        return try {
            if (c.responseCode !in 200..299) throw IllegalStateException("Playback API HTTP ${c.responseCode}")
            JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        } finally { c.disconnect() }
    }
}
