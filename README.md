# sACN Controller (Android)

Professional E1.31 sACN lighting controller for Android.

## Quick Start

### Requirements
- **Android Studio** (latest stable)
- Android device running **Android 8.0+** (API 26)
- Wi-Fi connection for sACN multicast

### Build & Run
1. Clone: `git clone https://github.com/entertainmentcontrolsystems/sACN-Controller-Android.git`
2. Open in Android Studio
3. Wait for Gradle sync to complete
4. Connect your Android device via USB (with USB debugging enabled)
5. Press Run ▶️

All dependencies are declared in `build.gradle.kts` — Gradle handles everything automatically.

### What It Does
- Import GDTF fixture profiles
- Patch fixtures across multiple sACN universes
- Manual DMX control with 8-bit and 16-bit channel support
- Save/recall Looks (DMX snapshots) with searchable tags
- Cue Lists with crossfades, delays, and timed auto-advance
- D16xy converter for ETC EOS integration
- Live DMX monitor per universe
- Fixture groups with multi-select control
- Show file export/import for backup and sharing
- Blackout safety

## Architecture

```
app/src/main/java/com/sacn/controller/
├── sacn/           # sACN sender/receiver (via sacn-common)
├── engine/         # Cue list crossfade engine
├── gdtf/           # GDTF XML parser
├── model/          # Data models
├── data/           # Room database
├── ui/             # Compose screens
└── viewmodel/      # ViewModel + focused controllers
```

## License

Copyright © 2026 ECS Lighting. All rights reserved.
