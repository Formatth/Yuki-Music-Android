package com.formatth.yukimusic.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.IBinder
import com.formatth.yukimusic.R
import com.formatth.yukimusic.WebViewActivity
import java.net.URL

/**
 * Keeps the WebView media process important while audio is playing and
 * publishes native Android media controls/notification.
 *
 * The actual audio remains the official YouTube IFrame player in WebView.
 * This service does not extract or replace YouTube media streams.
 */
class PlaybackService : Service() {
    private lateinit var mediaSession: MediaSession
    private lateinit var notificationManager: NotificationManager
    private var title = "Yuki Music"
    private var artist = ""
    private var artworkUrl = ""
    private var isPlaying = false
    private var foregroundStarted = false

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        createChannel()

        mediaSession = MediaSession(this, "Yuki Music").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = sendControl(CONTROL_PLAY)
                override fun onPause() = sendControl(CONTROL_PAUSE)
                override fun onSkipToNext() = sendControl(CONTROL_NEXT)
                override fun onSkipToPrevious() = sendControl(CONTROL_PREV)
                override fun onStop() = sendControl(CONTROL_STOP)
            })
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_UPDATE -> {
                title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "Yuki Music" }
                artist = intent.getStringExtra(EXTRA_ARTIST).orEmpty()
                artworkUrl = intent.getStringExtra(EXTRA_ARTWORK).orEmpty()
                isPlaying = intent.getBooleanExtra(EXTRA_PLAYING, false)
                publishPlaybackState()
                showNotification()
                loadArtworkAsync(artworkUrl)
            }
            ACTION_PLAY_PAUSE -> sendControl(if (isPlaying) CONTROL_PAUSE else CONTROL_PLAY)
            ACTION_PLAY -> sendControl(CONTROL_PLAY)
            ACTION_PAUSE -> sendControl(CONTROL_PAUSE)
            ACTION_NEXT -> sendControl(CONTROL_NEXT)
            ACTION_PREV -> sendControl(CONTROL_PREV)
            ACTION_STOP -> {
                sendControl(CONTROL_STOP)
                stopPlaybackService()
            }
        }
        return START_STICKY
    }

    private fun publishPlaybackState() {
        val actions = PlaybackState.ACTION_PLAY or
            PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_SKIP_TO_PREVIOUS or
            PlaybackState.ACTION_SKIP_TO_NEXT or
            PlaybackState.ACTION_STOP

        val state = if (isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
        mediaSession.setPlaybackState(
            PlaybackState.Builder()
                .setActions(actions)
                .setState(state, PlaybackState.PLAYBACK_POSITION_UNKNOWN, if (isPlaying) 1f else 0f)
                .build()
        )

        mediaSession.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, "Yuki Music")
                .build()
        )
    }

    private fun showNotification(artwork: Bitmap? = null) {
        val openIntent = PendingIntent.getActivity(
            this,
            100,
            Intent(this, WebViewActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val playPause = PendingIntent.getService(
            this, 101, Intent(this, PlaybackService::class.java).setAction(ACTION_PLAY_PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val prev = PendingIntent.getService(
            this, 102, Intent(this, PlaybackService::class.java).setAction(ACTION_PREV),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val next = PendingIntent.getService(
            this, 103, Intent(this, PlaybackService::class.java).setAction(ACTION_NEXT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 104, Intent(this, PlaybackService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_music)
            .setContentTitle(title)
            .setContentText(if (artist.isBlank()) "Yuki Music" else artist)
            .setSubText("Yuki Music")
            .setContentIntent(openIntent)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(1, 2, 3)
            )
            .addAction(Notification.Action.Builder(
                android.R.drawable.ic_media_previous, "Previous", prev
            ).build())
            .addAction(Notification.Action.Builder(
                if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (isPlaying) "Pause" else "Play",
                playPause
            ).build())
            .addAction(Notification.Action.Builder(
                android.R.drawable.ic_media_next, "Next", next
            ).build())
            .addAction(Notification.Action.Builder(
                android.R.drawable.ic_menu_close_clear_cancel, "Stop", stop
            ).build())

        if (artwork != null) builder.setLargeIcon(artwork)

        if (Build.VERSION.SDK_INT >= 31) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }

        val notification = builder.build()
        if (!foregroundStarted) {
            startForeground(NOTIFICATION_ID, notification)
            foregroundStarted = true
        } else {
            notificationManager.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun loadArtworkAsync(url: String) {
        if (url.isBlank()) return
        Thread {
            try {
                val bitmap = URL(url).openStream().use { BitmapFactory.decodeStream(it) }
                if (bitmap != null && artworkUrl == url) {
                    notificationManager.notify(NOTIFICATION_ID, buildNotification(bitmap))
                }
            } catch (_: Exception) {
                // The notification still works with the Yuki Music icon.
            }
        }.start()
    }

    private fun buildNotification(artwork: Bitmap): Notification {
        // Rebuild through the same notification path, but without another
        // artwork download. Keeping this separate avoids blocking the service.
        return buildNotificationInternal(artwork)
    }

    private fun buildNotificationInternal(artwork: Bitmap): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 100,
            Intent(this, WebViewActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val playPause = servicePending(101, ACTION_PLAY_PAUSE)
        val prev = servicePending(102, ACTION_PREV)
        val next = servicePending(103, ACTION_NEXT)
        val stop = servicePending(104, ACTION_STOP)
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_music)
            .setContentTitle(title)
            .setContentText(if (artist.isBlank()) "Yuki Music" else artist)
            .setSubText("Yuki Music")
            .setContentIntent(openIntent)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setLargeIcon(artwork)
            .setStyle(Notification.MediaStyle().setMediaSession(mediaSession.sessionToken).setShowActionsInCompactView(1, 2, 3))
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_previous, "Previous", prev).build())
            .addAction(Notification.Action.Builder(if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play, if (isPlaying) "Pause" else "Play", playPause).build())
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_next, "Next", next).build())
            .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stop).build())
        if (Build.VERSION.SDK_INT >= 31) builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        return builder.build()
    }

    private fun servicePending(requestCode: Int, action: String): PendingIntent =
        PendingIntent.getService(
            this, requestCode,
            Intent(this, PlaybackService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun sendControl(command: String) {
        sendBroadcast(
            Intent(ACTION_CONTROL).setPackage(packageName).putExtra(EXTRA_COMMAND, command)
        )
    }

    private fun stopPlaybackService() {
        isPlaying = false
        mediaSession.isActive = false
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE)
        else @Suppress("DEPRECATION") stopForeground(true)
        stopSelf()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Music playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Yuki Music playback controls"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Keep the foreground service alive while WebView audio continues.
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        if (::mediaSession.isInitialized) {
            mediaSession.isActive = false
            mediaSession.release()
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_UPDATE = "com.formatth.yukimusic.PLAYBACK_UPDATE"
        const val ACTION_PLAY_PAUSE = "com.formatth.yukimusic.PLAY_PAUSE"
        const val ACTION_PLAY = "com.formatth.yukimusic.PLAY"
        const val ACTION_PAUSE = "com.formatth.yukimusic.PAUSE"
        const val ACTION_NEXT = "com.formatth.yukimusic.NEXT"
        const val ACTION_PREV = "com.formatth.yukimusic.PREV"
        const val ACTION_STOP = "com.formatth.yukimusic.STOP"
        const val ACTION_CONTROL = "com.formatth.yukimusic.CONTROL"
        const val EXTRA_TITLE = "title"
        const val EXTRA_ARTIST = "artist"
        const val EXTRA_ARTWORK = "artwork"
        const val EXTRA_PLAYING = "playing"
        const val EXTRA_COMMAND = "command"
        const val CONTROL_PLAY = "play"
        const val CONTROL_PAUSE = "pause"
        const val CONTROL_NEXT = "next"
        const val CONTROL_PREV = "prev"
        const val CONTROL_STOP = "stop"
        const val CHANNEL_ID = "yuki_music_playback"
        const val NOTIFICATION_ID = 4201

        fun update(context: android.content.Context, title: String, artist: String, artwork: String, playing: Boolean) {
            val intent = Intent(context, PlaybackService::class.java).apply {
                action = ACTION_UPDATE
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_ARTIST, artist)
                putExtra(EXTRA_ARTWORK, artwork)
                putExtra(EXTRA_PLAYING, playing)
            }
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }

        fun stop(context: android.content.Context) {
            context.startService(Intent(context, PlaybackService::class.java).setAction(ACTION_STOP))
        }
    }
}
