package com.offlineassistant.offline_voice_assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.app.SearchManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.media.audiofx.AutomaticGainControl
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.PowerManager
import android.util.Log
import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineManager
import ai.picovoice.porcupine.PorcupineManagerCallback
import ai.picovoice.porcupine.PorcupineException
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.IOException

class VoiceAssistantService : Service(), RecognitionListener {

    companion object {
        const val TAG = "VoiceAssistantService"
        const val CHANNEL_ID = "voice_assistant_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP_SERVICE = "com.offlineassistant.STOP_SERVICE"

        var isRunning = false
            private set

        var lastRecognizedText: String = ""
            private set

        var lastCommandAction: String = ""
            private set

        var statusMessage: String = "Idle"
            private set

        var statusListener: ((String, String, String) -> Unit)? = null
        var voskModel: Model? = null

        const val PREFS_NAME = "voice_assistant_prefs"
        const val KEY_PORCUPINE_ACCESS_KEY = "porcupine_access_key"
    }

    private var speechService: SpeechService? = null
    private var porcupineManager: PorcupineManager? = null
    private var commandRouter: CommandRouter? = null
    private var appLauncher: AppLauncher? = null
    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null

    // Awake state machine
    private var isAwake = false
    private var agc: AutomaticGainControl? = null

    // Dictation (note-taking) state machine
    private var isDictating = false
    private val dictationBuffer = StringBuilder()

    private val awakeTimeoutRunnable = Runnable {
        Log.d(TAG, "Awake timer expired, going back to sleep")
        // If dictating, auto-save whatever was collected so far
        if (isDictating) {
            finishDictation()
        }
        isAwake = false
        stopVoskListening()
        startPorcupineListening()
        updateStatus("Idle", "", "Listening for wake word...")
        updateNotification("Listening for wake word...")
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate")
        commandRouter = CommandRouter(this)
        appLauncher = AppLauncher(this)
        LibVosk.setLogLevel(LogLevel.INFO)
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service onStartCommand, action: ${intent?.action}")

        if (intent?.action == ACTION_STOP_SERVICE) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundServiceNotification()
        isRunning = true
        updateStatus("Starting", "", "Loading speech model...")

        handler.postDelayed({
            initModelsAndListen()
        }, 300)

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.d(TAG, "Service onDestroy")
        isRunning = false
        isAwake = false
        if (isDictating) finishDictation()
        isDictating = false
        handler.removeCallbacks(awakeTimeoutRunnable)
        stopVoskListening()
        stopPorcupineListening()
        releaseAgc()
        try {
            porcupineManager?.delete()
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting Porcupine", e)
        }
        releaseWakeLock()
        updateStatus("Stopped", "", "")
        super.onDestroy()
    }

    // ── Model Loading ──────────────────────────────────────────

    private fun initModelsAndListen() {
        // Read the user's Porcupine access key from SharedPreferences
        val accessKey = getPorcupineAccessKey()?.trim()
        if (accessKey.isNullOrBlank()) {
            Log.e(TAG, "Porcupine access key not set")
            updateStatus("Error", "", "Porcupine key not set. Please add your access key in the app.")
            return
        }

        updateStatus("Starting", "", "Initializing wake engine...")

        // Run ALL heavy initialization on a background thread to avoid blocking UI
        Thread {
            // Initialize Porcupine (this can block if validating key over network)
            try {
                Log.d(TAG, "Initializing Porcupine on background thread...")
                val manager = PorcupineManager.Builder()
                    .setAccessKey(accessKey)
                    .setKeyword(Porcupine.BuiltInKeyword.PORCUPINE)
                    .setSensitivities(floatArrayOf(0.85f))
                    .build(
                        applicationContext,
                        PorcupineManagerCallback { keywordIndex ->
                            if (keywordIndex == 0) {
                                Log.d(TAG, "Porcupine detected wake word!")
                                onWakeWordDetected()
                            }
                        }
                    )
                porcupineManager = manager
                Log.d(TAG, "Porcupine initialized successfully")
            } catch (e: PorcupineException) {
                Log.e(TAG, "Failed to initialize Porcupine", e)
                handler.post {
                    updateStatus("Error", "", "Wake engine error: ${e.message}")
                }
                return@Thread
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error initializing Porcupine", e)
                handler.post {
                    updateStatus("Error", "", "Wake engine error: ${e.message}")
                }
                return@Thread
            }

            // Initialize Vosk
            if (voskModel != null) {
                Log.d(TAG, "Vosk model already loaded")
                handler.post { startPorcupineListening() }
                return@Thread
            }

            val modelManager = VoskModelManager(this)
            if (modelManager.isModelReady()) {
                Log.d(TAG, "Loading Vosk model from disk...")
                handler.post { updateStatus("Starting", "", "Loading speech model...") }

                try {
                    voskModel = modelManager.loadModel()
                    if (voskModel != null) {
                        handler.post {
                            Log.d(TAG, "Vosk model loaded successfully")
                            startPorcupineListening()
                        }
                    } else {
                        handler.post {
                            updateStatus("Error", "", "Failed to load speech model")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error loading model", e)
                    handler.post {
                        updateStatus("Error", "", "Model load error: ${e.message}")
                    }
                }
            } else {
                Log.e(TAG, "Vosk model not set up yet")
                handler.post {
                    updateStatus("Error", "", "Speech model not set up. Open app to set up.")
                }
            }
        }.start()
    }

    // ── Speech Recognition Engines ─────────────────────────────

    private fun startPorcupineListening() {
        if (!isRunning || porcupineManager == null) return
        try {
            porcupineManager?.start()
            updateStatus("Listening", "", "Waiting for wake word")
            updateNotification("Listening for wake word...")
            Log.d(TAG, "Porcupine started")
        } catch (e: PorcupineException) {
            Log.e(TAG, "Error starting Porcupine", e)
        }
    }

    private fun stopPorcupineListening() {
        try {
            porcupineManager?.stop()
            Log.d(TAG, "Porcupine stopped")
        } catch (e: PorcupineException) {
            Log.e(TAG, "Error stopping Porcupine", e)
        }
    }

    private fun startVoskListening() {
        if (!isRunning || voskModel == null) return

        try {
            stopVoskListening()

            // Removed grammar constraint, now using full vocabulary
            val recognizer = Recognizer(voskModel, 16000.0f)
            speechService = SpeechService(recognizer, 16000.0f).apply {
                startListening(this@VoiceAssistantService)
            }

            // Enable Automatic Gain Control (AGC) using global audio session id 0 if possible
            // Vosk handles AudioSource.VOICE_RECOGNITION automatically internally.
            applyAutomaticGainControl()

            updateStatus("Awake", "", "Ready for command")
            updateNotification("Awake! Listening for command...")
            Log.d(TAG, "Vosk started")

        } catch (e: IOException) {
            Log.e(TAG, "Error starting Vosk", e)
            updateStatus("Error", "", "Microphone error: ${e.message}")
        }
    }

    private fun stopVoskListening() {
        try {
            releaseAgc()
            speechService?.stop()
            speechService?.shutdown()
            speechService = null
            Log.d(TAG, "Vosk stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping Vosk", e)
        }
    }

    private fun applyAutomaticGainControl() {
        try {
            if (AutomaticGainControl.isAvailable()) {
                // AudioSession 0 applies it generally to the output mix, which is best-effort since Vosk hides the AudioRecord.
                agc = AutomaticGainControl.create(0)
                agc?.enabled = true
                Log.d(TAG, "Automatic Gain Control (AGC) dynamically enabled for Vosk window.")
            } else {
                Log.w(TAG, "AGC is not available on this device's hardware.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize AGC", e)
        }
    }

    private fun releaseAgc() {
        try {
            agc?.enabled = false
            agc?.release()
            agc = null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release AGC", e)
        }
    }

    // ── RecognitionListener callbacks ──────────────────────────
    // Vosk calls onResult for each recognized utterance segment.
    // It keeps listening continuously — NO need to restart after each result.
    // onFinalResult is only called when the service/stream is explicitly stopped.

    override fun onResult(hypothesis: String?) {
        if (hypothesis.isNullOrEmpty()) return
        val text = extractText(hypothesis)
        if (text.isNotEmpty()) {
            Log.d(TAG, "Result: '$text'")
            handler.post { processRecognizedText(text) }
        }
    }

    override fun onFinalResult(hypothesis: String?) {
        // Called when the speech service is stopped/shutdown
        if (hypothesis.isNullOrEmpty()) return
        val text = extractText(hypothesis)
        if (text.isNotEmpty()) {
            Log.d(TAG, "Final result: '$text'")
            handler.post { processRecognizedText(text) }
        }

        // If the service ended but we should still be running, just process whatever last bits we had
        // We do not restart Vosk automatically here because it might be going to sleep
        Log.d(TAG, "Vosk speech stream ended")
    }

    override fun onPartialResult(hypothesis: String?) {
        if (hypothesis.isNullOrEmpty()) return
        val text = extractPartialText(hypothesis)
        if (text.isNotEmpty() && text != "[unk]") {
            statusListener?.invoke(if (isAwake) "Awake" else "Listening for wake word", text, "")
        }
    }

    override fun onError(e: Exception?) {
        Log.e(TAG, "Vosk recognition error", e)
        updateStatus("Error", "", "Recognition error: ${e?.message}")
        if (isRunning && isAwake) {
            handler.post {
                isAwake = false
                stopVoskListening()
                startPorcupineListening()
            }
        }
    }

    override fun onTimeout() {
        Log.d(TAG, "Vosk timeout, going to sleep")
        if (isRunning && isAwake) {
            handler.post {
                isAwake = false
                stopVoskListening()
                startPorcupineListening()
            }
        }
    }

    // ── Text Processing ────────────────────────────────────────

    private fun extractText(json: String): String {
        return try {
            JSONObject(json).optString("text", "").trim()
        } catch (e: Exception) { "" }
    }

    private fun extractPartialText(json: String): String {
        return try {
            JSONObject(json).optString("partial", "").trim()
        } catch (e: Exception) { "" }
    }

    private fun processRecognizedText(text: String) {
        if (text.isEmpty() || text == "[unk]") return
        lastRecognizedText = text
        Log.d(TAG, "Processing: '$text' (isAwake=$isAwake, isDictating=$isDictating)")

        val lowerText = text.lowercase()
        val strippedText = lowerText.replace(" ", "")

        // ── DICTATION MODE: capture speech as notes ──────────────
        if (isDictating) {
            // Check for stop commands
            if (matchesDictationStop(lowerText)) {
                finishDictation()
                updateStatus("Executed", text, "📝 Note saved!")
                updateNotification("Note saved!")

                // Stay awake for more commands
                handler.removeCallbacks(awakeTimeoutRunnable)
                handler.postDelayed(awakeTimeoutRunnable, 20000)
                return
            }

            // Accumulate spoken text into the note buffer
            if (dictationBuffer.isNotEmpty()) {
                dictationBuffer.append(" ")
            }
            dictationBuffer.append(text)
            Log.d(TAG, "Dictation buffer: ${dictationBuffer.length} chars")
            updateStatus("Dictating", text, "📝 Recording... (${countWords(dictationBuffer.toString())} words)")
            updateNotification("📝 Dictating... Say \"stop notes\" to save")

            // Keep alive – No timeout during active dictation.
            // Restart a long 5-minute timeout as a safety net.
            handler.removeCallbacks(awakeTimeoutRunnable)
            handler.postDelayed(awakeTimeoutRunnable, 5 * 60 * 1000L)
            return
        }

        // ── COMMAND MODE: process voice commands ─────────────────
        if (isAwake) {
            var handled = false
            var actionStr = ""

            // ── "Take notes" / "start notes" – enter dictation mode ──
            if (matchesDictationStart(lowerText)) {
                isDictating = true
                dictationBuffer.clear()
                actionStr = "📝 Dictation started. Speak your notes..."
                handled = true
                updateStatus("Dictating", text, actionStr)
                updateNotification("📝 Dictating... Say \"stop notes\" to save")

                // Use a long timeout (5 min) during dictation
                handler.removeCallbacks(awakeTimeoutRunnable)
                handler.postDelayed(awakeTimeoutRunnable, 5 * 60 * 1000L)
                return
            }

            // ── "Show notes" / "my notes" – tell Flutter to show notes ──
            if (matchesShowNotes(lowerText)) {
                actionStr = "📂 Showing notes..."
                handled = true
                updateStatus("showNotes", text, actionStr)
                updateNotification("Showing notes...")

                handler.removeCallbacks(awakeTimeoutRunnable)
                handler.postDelayed(awakeTimeoutRunnable, 20000)

                // Also update with Executed after a short delay
                handler.postDelayed({
                    updateStatus("Executed", "", actionStr)
                }, 500)
                return
            }

            // ── Google Search ──
            if (lowerText.startsWith("search ")) {
                val query = lowerText
                    .removePrefix("search ")
                    .replace("for ", "")
                    .replace("on google", "")
                    .trim()

                if (query.isNotEmpty()) {
                    performGoogleSearch(query)
                    actionStr = "Searched for: $query"
                    handled = true
                }
            }

            // ── Dynamic app launching – matches ANY installed app ──
            if (!handled) {
                val launchResult = appLauncher?.findAndLaunch(lowerText)
                if (launchResult != null) {
                    val (appDisplayName, success) = launchResult
                    if (success) {
                        actionStr = "Opened $appDisplayName"
                        handled = true
                    } else {
                        actionStr = "Found $appDisplayName but failed to open"
                        handled = true
                    }
                }
            }

            // ── Fallback router matching (gallery, camera, etc.) ──
            if (!handled) {
                val routerResult = commandRouter?.routeCommand(lowerText)
                if (routerResult != null && !routerResult.startsWith("Not understood")) {
                    actionStr = routerResult
                    handled = true
                }
            }

            if (handled) {
                lastCommandAction = actionStr
                updateStatus("Executed", text, actionStr)
                updateNotification("Executed: $actionStr")

                // CONTINUOUS LISTENING: reset the 20-second timer
                handler.removeCallbacks(awakeTimeoutRunnable)
                handler.postDelayed(awakeTimeoutRunnable, 20000)
            } else {
                updateStatus("Awake", text, "Not a command: \"$text\"")
            }
        }
    }

    // ── Dictation helpers ───────────────────────────────────────

    private fun matchesDictationStart(text: String): Boolean {
        val commands = listOf(
            "take notes", "take a note", "take note",
            "start notes", "start note", "new note",
            "write note", "write notes",
            "start dictation", "record note", "dictate"
        )
        return commands.any { text.contains(it) }
    }

    private fun matchesDictationStop(text: String): Boolean {
        val commands = listOf(
            "stop notes", "stop note", "stop saving",
            "save notes", "save note", "done notes",
            "finish notes", "finish note", "end notes",
            "end note", "stop dictation", "save it",
            "done dictating", "stop recording"
        )
        return commands.any { text.contains(it) }
    }

    private fun matchesShowNotes(text: String): Boolean {
        val commands = listOf(
            "show notes", "show my notes", "my notes",
            "read notes", "read my notes", "open notes",
            "view notes", "see notes", "see my notes",
            "all notes", "list notes"
        )
        // Exact or contains match, but NOT "take notes" or "stop notes"
        if (text.contains("take") || text.contains("stop") ||
            text.contains("save") || text.contains("start") ||
            text.contains("write") || text.contains("new")) {
            return false
        }
        return commands.any { text.contains(it) } || text.trim() == "notes"
    }

    private fun finishDictation() {
        isDictating = false
        val content = dictationBuffer.toString().trim()
        dictationBuffer.clear()

        if (content.isNotEmpty()) {
            val savedPath = commandRouter?.saveNotes(content)
            Log.d(TAG, "Note saved to: $savedPath")
            lastCommandAction = "📝 Note saved (${countWords(content)} words)"

            // Notify Flutter to refresh notes
            updateStatus("noteSaved", "", lastCommandAction)
        } else {
            Log.d(TAG, "Dictation buffer was empty, nothing saved")
            lastCommandAction = "📝 No content to save"
        }
    }

    private fun countWords(text: String): Int {
        return text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.size
    }


    private fun performGoogleSearch(query: String) {
        try {
            val searchIntent = Intent(Intent.ACTION_WEB_SEARCH).apply {
                putExtra(SearchManager.QUERY, query)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(searchIntent)
            Log.d(TAG, "Successfully launched search for: $query")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch web search intent", e)
        }
    }

    private fun playBeep() {
        try {
            val toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
            toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 200)
            handler.postDelayed({ toneGenerator.release() }, 250)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play beep", e)
        }
    }

    // ── Notification & Status ──────────────────────────────────

    private fun updateStatus(status: String, text: String, action: String) {
        statusMessage = status
        if (text.isNotEmpty()) lastRecognizedText = text
        if (action.isNotEmpty()) lastCommandAction = action
        statusListener?.invoke(status, text, action)
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "VoiceAssistant::WakeLock"
        ).apply {
            acquire(10 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun startForegroundServiceNotification() {
        createNotificationChannel()

        val stopIntent = Intent(this, VoiceAssistantService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val launchIntent = Intent(this, MainActivity::class.java)
        val launchPendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Voice Assistant Active")
            .setContentText("Listening for voice commands...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(launchPendingIntent)
            .addAction(
                Notification.Action.Builder(null, "Stop", stopPendingIntent).build()
            )
            .setOngoing(true)
            .build()

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPT is 1024 (0x00000400) in API 34+
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or 1024
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0
        }

        if (flags != 0) {
            startForeground(NOTIFICATION_ID, notification, flags)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Voice Assistant Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Notification for the voice assistant background service"
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun updateNotification(text: String) {
        val stopIntent = Intent(this, VoiceAssistantService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val launchIntent = Intent(this, MainActivity::class.java)
        val launchPendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Voice Assistant Active")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(launchPendingIntent)
            .addAction(
                Notification.Action.Builder(null, "Stop", stopPendingIntent).build()
            )
            .setOngoing(true)
            .build()

        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    // ── Wake Word & Command Execution ──────────────────────────────

    /**
     * Triggered when Porcupine detects the wake word.
     */
    fun onWakeWordDetected() {
        handler.post {
            if (!isRunning) return@post
            
            Log.d(TAG, "Wake word detected! Switching to command mode...")
            isAwake = true
            playBeep()
            
            // Switch engines
            stopPorcupineListening()
            startVoskListening()
            
            // Start 20-second timeout
            handler.removeCallbacks(awakeTimeoutRunnable)
            handler.postDelayed(awakeTimeoutRunnable, 20000)
        }
    }

    private fun getPorcupineAccessKey(): String? {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_PORCUPINE_ACCESS_KEY, null)
    }
}
