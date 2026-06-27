# VideoHost TV — Android TV приложение

Native Android TV приложение на Kotlin + Jetpack Compose для просмотра видео из VideoHost. Без возможности загрузки и создания плейлистов — только просмотр.

## Возможности

- **Netflix-style rows** на главном экране:
  - «Продолжить просмотр» — видео с сохранённой позицией
  - «Недавно добавленные» — последние 20 видео
  - По ряду на каждый плейлист
- **D-pad управление плеером**:
  - `OK` / `Enter` — play/pause
  - `←` / `→` — перемотка ±10 секунд
  - `↑` / `↓` — следующее / предыдущее видео в списке
  - `Back` — выйти из плеера (с сохранением позиции)
- **Resume position** — автоматически восстанавливается при открытии видео (через существующий `/api/videos/:id/progress`)
- **Сохранение позиции каждые 5 секунд** — можно продолжить с любого устройства
- **Login screen** — вход с username/password из VideoHost (cookie сохраняется в DataStore)
- **Settings screen** — адрес VideoHost (по умолчанию пусто, вводится при первом запуске)
- **YouTube thumbnails** — автоматически используются, если у видео есть `thumbnail` URL или `youtubeId`

## Сборка APK

В этом репозитории уже собран debug APK:
- `VideoHostTV-debug-v1.0.apk` (13.9 MB)

### Самостоятельная сборка

Требования:
- JDK 17+ (проверено на Java 21)
- Android SDK (platform 34, build-tools 34.0.0)

```bash
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Для release-сборки (нужно подписать своим keystore):

```bash
# 1. Сгенерировать keystore (один раз)
keytool -genkey -v -keystore videohost.jks -keyalg RSA -keysize 2048 \
  -validity 10000 -alias videohost

# 2. Добавить в app/build.gradle.kts signingConfigs + references в buildTypes

# 3. Собрать
./gradlew assembleRelease
```

## Установка на Android TV

### Способ 1: ADB (рекомендуется)

1. Включить отладку по USB на Android TV: Settings → System → About → Build (тапнуть 7 раз) → Developer options → USB debugging = ON
2. Подключить Android TV и компьютер к одной сети
3. С компьютера:
   ```bash
   adb connect <IP_адрес_TV>:5555
   adb install -r VideoHostTV-debug-v1.0.apk
   ```
4. На Android TV открыть приложение «VideoHost TV» из лаунчера

### Способ 2: USB-флешка

1. Скопировать `VideoHostTV-debug-v1.0.apk` на USB-флешку
2. На Android TV: разрешить установку из неизвестных источников для файлового менеджера (Settings → Apps → Special access → Install unknown apps)
3. Вставить флешку, открыть файловый менеджер (например, FX File Explorer, Solid Explorer)
4. Тапнуть по APK → установить
5. Открыть приложение из лаунчера

### Способ 3: Send Files To TV (из Play Store)

1. Установить «Send Files To TV» на Android TV и на телефон/компьютер
2. С компьютера отправить APK → принять на TV → установить

## Первый запуск

1. При первом запуске откроется экран «Настройки» — ввести адрес VideoHost (например, `http://158.46.44.74:3002`)
2. После сохранения откроется экран логина — ввести username/password администратора или обычного пользователя
3. После входа откроется главный экран с рядами видео

## Стек технологий

- **Kotlin** 1.9.24
- **Jetpack Compose** (BOM 2024.06.00) + Material3
- **Media3 ExoPlayer** 1.3.1 — видеоплеер
- **Retrofit 2** + **OkHttp 4** + **kotlinx-serialization** — API клиент
- **Coil 2** — загрузка изображений
- **DataStore Preferences** — persistence URL и cookie
- **Navigation Compose** — навигация между экранами

## Минимальные требования

- Android 5.0 (API 21) и выше
- Leanback-совместимое устройство (Android TV / Google TV) или планшет/телефон с D-pad управлением
- Доступ к VideoHost по сети (HTTP или HTTPS)

## Поддерживаемые архитектуры

- `arm64-v8a` — современные TV-приставки (NVIDIA Shield, Chromecast с Google TV, Xiaomi Mi Box)
- `armeabi-v7a` — старые TV-приставки
- `x86`, `x86_64` — эмуляторы и редкие Intel-based приставки

## Известные ограничения

- Загрузка видео и создание плейлистов недоступно — только через веб-интерфейс VideoHost или yt2tg-бота
- Поиск не реализован (можно добавить в следующей версии)
- Избранное не реализовано (можно добавить в следующей версии)
- Скорость воспроизведения фиксированная 1x (можно добавить в следующей версии)

## Архитектура кода

```
app/src/main/java/com/videohost/tv/
├── MainActivity.kt              # Точка входа, настройка MaterialTheme
├── data/
│   ├── api/
│   │   ├── VideoHostApi.kt      # Retrofit интерфейс API VideoHost
│   │   └── VideoHostRepository.kt  # DataStore + OkHttp + cookie management
│   └── model/
│       └── Models.kt            # DTO: VideoItem, Playlist, Session, WatchProgress
└── ui/
    ├── NavGraph.kt              # Навигация: splash → settings/login/home/player
    └── screens/
        ├── login/LoginScreen.kt
        ├── settings/SettingsScreen.kt
        ├── home/HomeScreen.kt   # Netflix-rows: Continue Watching + Recent + по плейлистам
        └── player/
            ├── PlayerScreen.kt  # ExoPlayer + D-pad handler
            └── PlaybackTarget.kt # Сериализуемый объект: какой video + список для next/prev
```

## Лицензия

MIT — делайте с APK что хотите.
