# BuildFiler 🏢🧭

An autonomous Android Building Profiler app written in **pure Kotlin** that embeds live **OpenStreetMap (OSM)** tiles, tracks your real-time **GPS location and compass heading**, and queries the VPS Building Profiler microservice to display an instant HUD card with the details of whichever building you are facing.

Built to run and compile anywhere without requiring Android Studio—every commit triggers an automated GitHub Actions CI workflow that compiles and produces a ready-to-install APK.

---

## Features

- **Embedded OpenStreetMap:** Uses `osmdroid` for responsive vector/raster OSM tile rendering with zero Google Maps API keys or Play Services dependencies.
- **Dynamic Field-of-View Cone:** Renders a directional raycast cone directly onto the map aligned with your phone's real-time hardware compass sensor.
- **Live Building Profiler HUD:** Debounced background coroutines query the VPS Profiler API (`/profile`) over ZeroTier or public URL to determine the closest building or point-of-interest inside your facing cone.
- **Smart Target Marker:** Places an interactive OSM map pin on the exact centroid of the facing building with distance, address, category, and angle offset.
- **In-App Configuration:** Tap ⚙ to customize the target server URL, scan radius (meters), and cone aperture (degrees) at any time.
- **Automated Cloud CI:** Bundled with a GitHub Actions workflow that builds the debug APK on `ubuntu-latest` and makes it downloadable under the **Actions** tab or Releases.

---

## Project Structure

```text
BuildFiler/
├── .github/workflows/
│   └── build-apk.yml           # Automated APK build & release workflow
├── app/
│   ├── build.gradle.kts        # App module dependencies (osmdroid, okhttp, coroutines)
│   ├── src/main/
│   │   ├── AndroidManifest.xml # Permissions & activity declarations
│   │   ├── java/com/borgorninja/buildfiler/
│   │   │   ├── MainActivity.kt    # Main UI, MapView, overlay management
│   │   │   ├── SensorTracker.kt   # Compass rotation vector / magnetometer math
│   │   │   ├── ProfilerClient.kt  # OkHttp async client for VPS Profiler API
│   │   │   └── Models.kt          # POI & Building Target data structures
│   │   └── res/
│   │       ├── layout/
│   │       │   ├── activity_main.xml
│   │       │   └── dialog_settings.xml
│   │       └── values/
├── build.gradle.kts
├── settings.gradle.kts
└── gradlew
```

---

## Building the APK

You don't need Android Studio installed locally.

### Option 1: GitHub Actions (Recommended)
1. Go to the repository's **Actions** tab on GitHub.
2. Select **Build APK** and click **Run workflow** (or simply push any commit to `main`).
3. Once completed, download the `BuildFiler-Debug-APK` artifact from the run summary.

### Option 2: Command Line
```bash
./gradlew assembleDebug
```
The APK will be located at `app/build/outputs/apk/debug/app-debug.apk`.
