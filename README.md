# sACN Controller (Android)

Professional E1.31 sACN lighting controller for Android with Jetpack Compose.

## Features

- **GDTF profile import** — Parse and use GDTF fixture profiles
- **Fixture patching** — Patch fixtures across multiple sACN universes
- **Manual DMX control** — Per-channel control with 8-bit and 16-bit support
- **Looks** — Save and recall complete DMX snapshots with tagging
- **Cue Lists** — Sequenced playback with smoothstep crossfades, delays, and timed auto-GO
- **D16xy converter** — Convert D16xy sACN input to fixture-specific DMX output
- **DMX monitor** — View live DMX values per universe
- **Fixture groups** — Multi-select and group control
- **Show file export/import** — Full show file format for backup and sharing
- **Blackout** — Instant kill switch

## Requirements

- Android 8.0+ (API 26)
- Wi-Fi connection for sACN multicast

## Build

1. Open in Android Studio
2. Sync Gradle
3. Build → Run on device

## Architecture

```
app/src/main/java/com/sacn/controller/
├── sacn/           # sACN sender/receiver (shared library)
├── engine/         # Cue list crossfade engine
├── gdtf/           # GDTF XML parser
├── model/          # Data models
├── data/           # Room database
├── ui/             # Compose screens
└── viewmodel/      # Orchestrating ViewModel + focused controllers

sacn-common/        # Shared sACN/Art-Net library
```

## License

Copyright © 2026 ECS Lighting. All rights reserved.
