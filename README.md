# Offline Android Assistant

A fully offline voice assistant for Android, built with Flutter and native Kotlin.

- **Speech recognition:** [Vosk](https://alphacephei.com/vosk/) (bundled `vosk-model-small-en-us`), works with no internet.
- **Wake word:** [Picovoice Porcupine](https://picovoice.ai/platform/porcupine/) (built-in keyword "Porcupine").
- **Runs as a foreground service**, so it keeps listening in the background and can restart on boot.
- **Voice commands:** "open gallery" / "open photos", "open camera" / "take a photo", plus a dictation/notes mode.

## Prerequisites

| Tool | Version |
|------|---------|
| [Flutter SDK](https://docs.flutter.dev/get-started/install) | Dart SDK `^3.11.0` (recent stable) |
| Android Studio (or Android SDK + command-line tools) | with Android SDK Platform & NDK `27.1.12297006` |
| JDK | 17 (bundled with Android Studio) |
| Android device | Android 8.0+ (API 26+), USB debugging enabled |
| Picovoice account | Free, for an AccessKey: https://console.picovoice.ai/ |

## Setup

1. **Clone the repo**
   ```bash
   git clone https://github.com/husnainshafiq-dev/Offline-android-assistant.git
   cd Offline-android-assistant
   ```

2. **Check your Flutter install**
   ```bash
   flutter doctor
   ```
   Fix anything flagged for Android (accept licenses with `flutter doctor --android-licenses`).

3. **Install dependencies**
   ```bash
   flutter pub get
   ```

4. **Get a Picovoice AccessKey** (free) from the [Picovoice Console](https://console.picovoice.ai/). You paste it into the app on first launch; it is stored on-device only and is not part of the repo.

5. **Connect your phone** (USB debugging on) and confirm it appears:
   ```bash
   flutter devices
   ```

6. **Run the app**
   ```bash
   flutter run
   ```
   Or build a release APK:
   ```bash
   flutter build apk --release
   ```
   The APK is at `build/app/outputs/flutter-apk/app-release.apk`.

## First launch

1. Grant the requested permissions (microphone, notifications, battery optimization exemption, etc.).
2. Wait for the one-time Vosk model extraction to finish.
3. Paste your Picovoice AccessKey and save it.
4. Start the assistant, then say the wake word **"Porcupine"** followed by a command, e.g. "open camera".

## Project layout

```
lib/main.dart                      Flutter UI (permissions, key entry, start/stop)
android/app/src/main/kotlin/...    Native side:
  VoiceAssistantService.kt           foreground service: wake word + Vosk listening
  CommandRouter.kt                   maps recognized text to actions
  AppLauncher.kt                     launches gallery / camera / apps
  VoskModelManager.kt                extracts the bundled model
  BootReceiver.kt, ActionReceiver.kt
android/app/src/main/assets/model-en-us/   Bundled Vosk English model (~68 MB)
android/app/libs/vosk-android-0.3.75.aar   Vosk Android library
```

## Adding commands

Edit `routeCommand` in `CommandRouter.kt` and add a new `matchesCommand(text, listOf("your phrase", ...)) -> yourAction()` branch.

## Troubleshooting

- **"Failed to start. Check permissions & model."** Make sure all permissions are granted and model extraction finished.
- **Assistant stops in the background:** disable battery optimization for the app in Android settings.
- **Invalid Picovoice key:** copy the full AccessKey from the console with no extra spaces.
- **Gradle/NDK errors:** install NDK `27.1.12297006` via Android Studio's SDK Manager.
