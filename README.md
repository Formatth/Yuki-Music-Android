# Yuki Music Android

Native Android music player by **Formatth**.

> Early development — the current release is the foundation for the Android client and playback engine.

## Stack

- Kotlin 2.3.21
- Jetpack Compose
- Material 3
- Android Gradle Plugin 9.3.0
- Media3 / ExoPlayer 1.11.0
- MediaSession
- GitHub Actions

## Current status

- [x] Native Android project
- [x] Compose UI foundation
- [x] Dark music-player interface
- [x] Media3 ExoPlayer dependency
- [x] MediaSession playback service foundation
- [x] GitHub Actions debug APK build
- [ ] YouTube Music search/backend
- [ ] Real track streaming
- [ ] Queue and player screen
- [ ] Lyrics
- [ ] Library and playlists
- [ ] Offline/cache support

## Build

The repository is configured to build the debug APK on GitHub Actions. The workflow produces `app-debug.apk` as a downloadable artifact.

For local CLI builds, use Gradle 9.5+ with JDK 17 and Android SDK 37.

## Architecture

```text
Compose UI
   │
   ▼
Playback controller
   │
   ▼
MediaSession
   │
   ▼
ExoPlayer
   │
   ▼
Android media / foreground service
```

## License

Project licensing will be finalized before the first public release.
