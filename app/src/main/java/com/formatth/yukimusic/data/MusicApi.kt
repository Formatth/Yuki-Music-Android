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

data class MusicSearchItem(val title: String, val subtitle: String, val videoId: String?, val thumbnail: String?, val type: String = "song")
data class HomeSection(val title: String, val items: List<MusicSearchItem>)
data class NextResponse(val queue: List<MusicSearchItem>, val lyricsBrowseId: String?, val relatedBrowseId: String?)
data class LyricsResult(val synced: String?, val plain: String?, val source: String?)

object MusicApi {
    private data class CachedUrl(val value: String, val createdAt: Long)
    private val playbackCache = ConcurrentHashMap<String, CachedUrl>()

    suspend fun getHome(): List<HomeSection> = withContext(Dispatchers.IO) {
        val c = openGet("$BASE_URL/api/home")
        try { if (c.responseCode !in 200..299) throw apiError("Home API", c); parseHome(JSONObject(c.inputStream.bufferedReader().use { it.readText() })) } finally { c.disconnect() }
    }

    suspend fun searchSongs(query: String): List<MusicSearchItem> = withContext(Dispatchers.IO) {
        val c = openGet("$BASE_URL/api/search?q=${URLEncoder.encode(query.trim(), "UTF-8")}&filter=songs")
        try { if (c.responseCode !in 200..299) throw apiError("Search API", c); parseSearch(JSONObject(c.inputStream.bufferedReader().use { it.readText() })) } finally { c.disconnect() }
    }

    suspend fun resolvePlaybackUrl(videoId: String): String = withContext(Dispatchers.IO) {
        require(videoId.isNotBlank()) { "Missing videoId" }
        val now = System.currentTimeMillis()
        playbackCache[videoId]?.let { if (now - it.createdAt < 5 * 60 * 1000L) return@withContext it.value else playbackCache.remove(videoId) }
        val start = openGet("$BASE_URL/api/download-start?videoId=${URLEncoder.encode(videoId, "UTF-8")}")
        val startJson = try { if (start.responseCode !in 200..299) throw apiError("Playback start", start); JSONObject(start.inputStream.bufferedReader().use { it.readText() }) } finally { start.disconnect() }
        val progressUrl = startJson.optString("progressUrl").ifBlank { startJson.optString("progress_url") }
        if (progressUrl.isBlank()) throw IllegalStateException(startJson.optString("error").ifBlank { "Playback converter did not return a progress URL" })
        repeat(180) {
            val p = openGet("$BASE_URL/api/download-progress?progressUrl=${URLEncoder.encode(progressUrl, "UTF-8")}")
            val json = try { if (p.responseCode !in 200..299) throw apiError("Playback progress", p); JSONObject(p.inputStream.bufferedReader().use { it.readText() }) } finally { p.disconnect() }
            val url = json.optString("url").ifBlank { json.optString("download_url") }
            if ((json.optBoolean("done") || json.optBoolean("success")) && url.isNotBlank()) { playbackCache[videoId] = CachedUrl(url, System.currentTimeMillis()); return@withContext url }
            if (json.optString("text").contains("error", true)) throw IllegalStateException(json.optString("text"))
            delay(250)
        }
        throw IllegalStateException("Playback conversion timed out")
    }

    suspend fun getNext(videoId: String): NextResponse = withContext(Dispatchers.IO) {
        val c = openGet("$BASE_URL/api/next?videoId=${URLEncoder.encode(videoId, "UTF-8")}")
        try {
            if (c.responseCode !in 200..299) throw apiError("Queue API", c)
            val root = JSONObject(c.inputStream.bufferedReader().use { it.readText() }); val a = root.optJSONArray("queue"); val out = mutableListOf<MusicSearchItem>()
            if (a != null) for (i in 0 until a.length()) { val o = a.optJSONObject(i) ?: continue; val t = o.optString("title").trim(); val id = o.optString("videoId").takeIf { it.isNotBlank() } ?: continue; if (t.isNotBlank()) out += MusicSearchItem(t, o.optString("artist").ifBlank { o.optString("subtitle") }, id, o.optString("thumbnail").takeIf { it.isNotBlank() }) }
            NextResponse(out, root.optString("lyricsBrowseId").takeIf { it.isNotBlank() }, root.optString("relatedBrowseId").takeIf { it.isNotBlank() })
        } finally { c.disconnect() }
    }

    suspend fun getLyrics(title: String, artist: String, durationSeconds: Long, browseId: String?): LyricsResult = withContext(Dispatchers.IO) {
        val q = "title=${URLEncoder.encode(title, "UTF-8")}&artist=${URLEncoder.encode(artist, "UTF-8")}&duration=$durationSeconds" + if (!browseId.isNullOrBlank()) "&browseId=${URLEncoder.encode(browseId, "UTF-8")}" else ""
        val c = openGet("$BASE_URL/api/lyrics?$q")
        try { if (c.responseCode !in 200..299) throw apiError("Lyrics API", c); val r = JSONObject(c.inputStream.bufferedReader().use { it.readText() }); LyricsResult(r.optString("synced").takeIf { it.isNotBlank() && it != "null" }, r.optString("plain").takeIf { it.isNotBlank() && it != "null" }, r.optString("source").takeIf { it.isNotBlank() && it != "null" }) } finally { c.disconnect() }
    }

    suspend fun getRelated(browseId: String): List<HomeSection> = withContext(Dispatchers.IO) {
        val c = openGet("$BASE_URL/api/related?browseId=${URLEncoder.encode(browseId, "UTF-8")}")
        try { if (c.responseCode !in 200..299) throw apiError("Related API", c); parseHome(JSONObject(c.inputStream.bufferedReader().use { it.readText() })) } finally { c.disconnect() }
    }

    private fun openGet(raw: String) = (URL(raw).openConnection() as HttpURLConnection).apply { requestMethod = "GET"; connectTimeout = 8000; readTimeout = 15000; setRequestProperty("Accept", "application/json"); setRequestProperty("User-Agent", "Yuki-Music-Android/0.7") }
    private fun apiError(name: String, c: HttpURLConnection) = IllegalStateException("$name returned HTTP ${c.responseCode}")

    private fun parseHome(root: JSONObject): List<HomeSection> { val a = root.optJSONArray("sections") ?: return emptyList(); val out = mutableListOf<HomeSection>(); for (i in 0 until a.length()) { val s = a.optJSONObject(i) ?: continue; val items = s.optJSONArray("items") ?: continue; val list = mutableListOf<MusicSearchItem>(); for (j in 0 until items.length()) { val o = items.optJSONObject(j) ?: continue; val t = o.optString("title").trim(); if (t.isNotBlank()) list += MusicSearchItem(t, o.optString("subtitle").trim(), o.optString("videoId").takeIf { it.isNotBlank() }, o.optString("thumbnail").takeIf { it.isNotBlank() }, o.optString("type").ifBlank { "song" }) }; if (list.isNotEmpty()) out += HomeSection(s.optString("title").ifBlank { "Yuki Music" }, list) }; return out }
    private fun parseSearch(root: JSONObject): List<MusicSearchItem> { val a = root.optJSONArray("sections") ?: return emptyList(); val out = mutableListOf<MusicSearchItem>(); for (i in 0 until a.length()) { val s = a.optJSONObject(i) ?: continue; val items = s.optJSONArray("items") ?: continue; for (j in 0 until items.length()) { val o = items.optJSONObject(j) ?: continue; val t = o.optString("title").trim(); if (t.isNotBlank()) out += MusicSearchItem(t, o.optString("subtitle").trim(), o.optString("videoId").takeIf { it.isNotBlank() }, o.optString("thumbnail").takeIf { it.isNotBlank() }, o.optString("type").ifBlank { "song" }) } }; return out.distinctBy { it.videoId ?: it.title } }
}
