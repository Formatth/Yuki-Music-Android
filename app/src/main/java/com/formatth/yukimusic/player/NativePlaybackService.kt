package com.formatth.yukimusic.player

import android.content.Intent
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Native background playback engine.
 *
 * The WebView remains the Yuki Music UI. This service is intentionally fed a
 * playable/authorized media URL rather than a YouTube video id. Once a media
 * source is available to the Android app, Media3 owns playback independently
 * from the Activity/WebView lifecycle.
 */
class NativePlaybackService : MediaSessionService() {
    private lateinit var player: ExoPlayer
    private lateinit var mediaSession: MediaSession

    override fun onCreate() {
        super.onCreate()

        player = ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(
                androidx.media3.common.AudioAttributes.Builder()
                    .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                    .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true
            )
            setHandleAudioBecomingNoisy(true)
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) {
                        // Keep the session alive for the next queued item.
                    }
                }
            })
        }

        mediaSession = MediaSession.Builder(this, player)
            .setCallback(object : MediaSession.Callback {})
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = mediaSession

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_URL -> {
                val url = intent.getStringExtra(EXTRA_URL).orEmpty()
                if (url.isNotBlank()) {
                    val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
                    val artist = intent.getStringExtra(EXTRA_ARTIST).orEmpty()
                    val artwork = intent.getStringExtra(EXTRA_ARTWORK).orEmpty()

                    val metadata = MediaMetadata.Builder()
                        .setTitle(title.ifBlank { "Yuki Music" })
                        .setArtist(artist)
                        .setAlbumTitle("Yuki Music")
                        .apply {
                            if (artwork.isNotBlank()) {
                                setArtworkUri(android.net.Uri.parse(artwork))
                            }
                        }
                        .build()

                    player.setMediaItem(
                        MediaItem.Builder()
                            .setUri(url)
                            .setMediaMetadata(metadata)
                            .build()
                    )
                    player.prepare()
                    player.play()
                }
            }

            ACTION_PAUSE -> player.pause()
            ACTION_PLAY -> player.play()
            ACTION_NEXT -> player.seekToNextMediaItem()
            ACTION_PREVIOUS -> player.seekToPreviousMediaItem()
            ACTION_STOP -> {
                player.stop()
                stopSelf()
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        mediaSession.release()
        player.release()
        super.onDestroy()
    }

    companion object {
        const val ACTION_PLAY_URL = "com.formatth.yukimusic.native.PLAY_URL"
        const val ACTION_PLAY = "com.formatth.yukimusic.native.PLAY"
        const val ACTION_PAUSE = "com.formatth.yukimusic.native.PAUSE"
        const val ACTION_NEXT = "com.formatth.yukimusic.native.NEXT"
        const val ACTION_PREVIOUS = "com.formatth.yukimusic.native.PREVIOUS"
        const val ACTION_STOP = "com.formatth.yukimusic.native.STOP"

        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"
        const val EXTRA_ARTIST = "artist"
        const val EXTRA_ARTWORK = "artwork"
    }
}
