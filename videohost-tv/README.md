# VideoHost TV

Native Android TV application for [VideoHost](https://github.com/sakurka-cmd/videohost) — viewing only (no upload or playlist-create features). Built with Kotlin + Jetpack Compose + Media3 ExoPlayer.

## Features

- **Netflix-style rows on the home screen:**
  - *Continue watching* — videos with a saved playback position
  - *Recently added* — last 20 uploads across all videos
  - One row per playlist (sorted by `PlaylistItem.order`, which the yt2tg bot sets chronologically by YouTube `publishedAt`)
- **D-pad-controlled video player (Media3 ExoPlayer):**
  - `OK` / `Enter` — play / pause
  - `←` / `→` — seek ±10 seconds
  - `↑` / `↓` — previous / next video in the current list
  - `Back` — save progress and close the player
  - On video end: clears saved progress (so the next viewing starts from the beginning) and auto-advances to the next video
- **Resume position sync** — automatically resumes from the saved position when a video is opened. Position is saved to the server every 5 seconds, so it stays in sync across web, mobile, and TV.
- **Login screen** — username/password from VideoHost (cookie `vh_session` persisted in DataStore).
- **Settings screen** — the VideoHost URL is configured on first launch and can be changed later via the Settings button on the home screen.
- **YouTube thumbnails** — automatically used when a video has a `thumbnail` URL or a `youtubeId` (redirects to `img.youtube.com`).

## Installation

### Option 1: ADB (recommended)

1. Enable USB debugging on the Android TV: Settings → System → About → tap *Build* 7 times → Developer options → USB debugging = ON.
2. Connect the Android TV and your computer to the same network.
3. From the computer:
   ```bash
   adb connect <TV_IP>:5555
   adb install -r VideoHostTV-debug-v1.0.apk
   ```
4. Open "VideoHost TV" from the Android TV launcher.

### Option 2: USB flash drive

1. Copy the APK to a USB flash drive.
2. On Android TV, allow installation from unknown sources for your file manager (Settings → Apps → Special access → Install unknown apps).
3. Insert the flash drive, open the file manager, tap the APK → install.

### Option 3: Send Files To TV (from Play Store)

Install "Send Files To TV" on both the Android TV and your phone/PC. Send the APK from the phone, accept on the TV, install.

## First launch

1. The settings screen opens — enter the VideoHost URL (e.g. `http://158.46.44.74:3002`).
2. The login screen opens — enter the username/password of an existing VideoHost user.
3. The home screen opens with rows of videos.

## Build

Requirements:
- JDK 17+ (tested with Java 21)
- Android SDK (platform 34, build-tools 34.0.0)

```bash
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

For a release build, generate a keystore and reference it in `app/build.gradle.kts` via `signingConfigs`, then run `./gradlew assembleRelease`.

## Tech stack

| Library | Version | Purpose |
|---------|---------|---------|
| Kotlin | 1.9.24 | Language |
| Compose BOM | 2024.06.00 | UI toolkit |
| Material3 | (BOM-managed) | Components |
| Media3 ExoPlayer | 1.3.1 | Video player |
| Retrofit | 2.11.0 | HTTP client |
| OkHttp | 4.12.0 | HTTP transport + cookie jar |
| kotlinx-serialization | 1.6.3 | JSON (de)serialization |
| Coil | 2.6.0 | Image loading |
| DataStore Preferences | 1.1.1 | Persisted server URL + session cookie |
| Navigation Compose | 2.7.7 | Screen navigation |

## Minimum requirements

- Android 5.0 (API 21) or later
- Leanback-compatible device (Android TV / Google TV) — also works on tablets/phones with D-pad navigation
- Network access to a VideoHost instance (HTTP or HTTPS)

## Supported ABIs

- `arm64-v8a` — modern TV boxes (NVIDIA Shield, Chromecast with Google TV, Xiaomi Mi Box)
- `armeabi-v7a` — older TV boxes
- `x86`, `x86_64` — emulators and rare Intel-based TV boxes

## Architecture

```
app/src/main/java/com/videohost/tv/
├── MainActivity.kt                  # Entry point, dark MaterialTheme
├── data/
│   ├── api/
│   │   ├── VideoHostApi.kt          # Retrofit interface
│   │   └── VideoHostRepository.kt   # DataStore + OkHttp + cookie management
│   └── model/Models.kt              # DTOs
└── ui/
    ├── NavGraph.kt                  # Routes: splash → settings/login/home/player
    └── screens/
        ├── login/LoginScreen.kt
        ├── settings/SettingsScreen.kt
        ├── home/HomeScreen.kt       # Netflix-rows
        └── player/
            ├── PlayerScreen.kt      # ExoPlayer + D-pad handler
            └── PlaybackTarget.kt    # Serialized playback target
```

## API endpoints used

| Method | Endpoint | Purpose |
|--------|----------|---------|
| `POST` | `/api/auth/login` | Login (returns Set-Cookie `vh_session`) |
| `GET` | `/api/auth/me` | Verify session is still valid |
| `GET` | `/api/playlists` | List all playlists with items |
| `GET` | `/api/videos` | List all videos |
| `GET` | `/api/videos/:id/progress` | Get saved playback position |
| `PUT` | `/api/videos/:id/progress` | Save current playback position |
| `DELETE` | `/api/videos/:id/progress` | Clear progress when video ends |
| `GET` | `/api/videos/:id/stream` | Video stream URL (passed to ExoPlayer) |
| `GET` | `/api/videos/:id/thumbnail` | Thumbnail (302 redirect to YouTube URL if applicable) |

## Known limitations

- No upload / playlist creation (intentional — use the VideoHost web UI or the [yt2tg-bot](https://github.com/sakurka-cmd/yt2tg-bot) for that)
- No search
- No favorites
- No playback speed control (planned for a future release)

## License

MIT
