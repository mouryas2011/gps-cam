# GPS Map Camera

A native Android camera app in Kotlin with a dark camera layout based on the supplied reference screenshot. CameraX handles photo/video capture; foreground location provides GPS stamps. Photos save to **Pictures/GPS Stamp Camera** and videos to **Movies/GPS Stamp Camera**.

## Features

- Live camera preview, front/rear switch, flash off/auto/on, 4:3/16:9 framing, pinch zoom, tap-to-focus, exposure adjustment, and 3/10-second timer.
- Photo/Video modes. Video is saved locally; microphone permission is requested only if the user chooses video audio.
- Photo stamps with local date/time, coordinates, locally generated Plus Code, GPS accuracy, optional altitude/speed/name, and selectable templates.
- Recent captures list (up to 100), EXIF GPS/date metadata for photos where supported, and Android share action.
- Stylized local map preview that opens the coordinates in an installed maps app. No map tiles are downloaded.
- No account, ads, analytics, or background location. Media files are not uploaded. Weather is off by default; enabling it in Settings sends GPS coordinates to Open-Meteo for current conditions. See [Open-Meteo API docs](https://open-meteo.com/en/docs).

## Build locally

Requirements: JDK 17, Android SDK Platform 35, Gradle 8.8.

```sh
gradle assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`.

## GitHub Actions

Push to `main` or run **Android APK** from the Actions tab. Download `gps-stamp-camera-debug` from a successful workflow run; artifacts expire after 30 days.

Push a version tag such as `v1.0.0` to build and publish a persistent GitHub Release APK. The APK is a debug/test build. A production-signed APK requires the owner to configure their own signing keystore and GitHub secrets; none are included.
