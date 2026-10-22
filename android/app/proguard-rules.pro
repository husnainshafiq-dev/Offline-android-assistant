# ── Vosk Speech Recognition ──────────────────────────────
-keep class org.vosk.** { *; }
-keep class org.kaldi.** { *; }
-dontwarn org.vosk.**
-dontwarn org.kaldi.**

# ── JNA (required by Vosk) ───────────────────────────────
-keep class com.sun.jna.** { *; }
-keep class com.sun.jna.internal.** { *; }
-keepclassmembers class * extends com.sun.jna.** { public *; }
-dontwarn com.sun.jna.**
-dontwarn java.awt.**

# ── Porcupine Wake Word Engine ───────────────────────────
-keep class ai.picovoice.** { *; }
-dontwarn ai.picovoice.**

# ── Keep native methods ─────────────────────────────────
-keepclasseswithmembernames class * {
    native <methods>;
}

# ── Keep our app classes ─────────────────────────────────
-keep class com.offlineassistant.offline_voice_assistant.** { *; }

# ── Flutter ──────────────────────────────────────────────
-keep class io.flutter.** { *; }
-dontwarn io.flutter.**

# ── JSON parsing (used by Vosk results) ──────────────────
-keep class org.json.** { *; }
