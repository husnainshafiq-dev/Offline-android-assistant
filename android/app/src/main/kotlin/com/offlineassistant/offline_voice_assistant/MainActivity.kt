package com.offlineassistant.offline_voice_assistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel
import org.vosk.Model

class MainActivity : FlutterActivity() {

    companion object {
        const val TAG = "MainActivity"
        const val METHOD_CHANNEL = "com.offlineassistant/voice_assistant"
        const val EVENT_CHANNEL = "com.offlineassistant/voice_status"
        const val PERMISSION_REQUEST_CODE = 1001

        const val ACTION_LAUNCH_EXTERNAL = "com.offlineassistant.ACTION_LAUNCH_EXTERNAL"
        const val EXTRA_INTENT = "com.offlineassistant.EXTRA_INTENT"
    }

    private var eventSink: EventChannel.EventSink? = null
    private lateinit var prefs: SharedPreferences
    private lateinit var modelManager: VoskModelManager

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        prefs = getSharedPreferences(BootReceiver.PREFS_NAME, Context.MODE_PRIVATE)
        modelManager = VoskModelManager(this)

        setupMethodChannel(flutterEngine)
        setupEventChannel(flutterEngine)

        // Handle intent if the app was launched by the background service
        handleExternalLaunchIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleExternalLaunchIntent(intent)
    }

    private fun handleExternalLaunchIntent(intent: Intent?) {
        if (intent?.action == ACTION_LAUNCH_EXTERNAL) {
            val externalIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(EXTRA_INTENT, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(EXTRA_INTENT)
            }

            if (externalIntent != null) {
                Log.d(TAG, "MainActivity launching external intent: ${externalIntent.action}")
                try {
                    startActivity(externalIntent)
                } catch (e: Exception) {
                    Log.e(TAG, "MainActivity failed to launch external intent", e)
                }
            }
        }
    }

    private fun setupMethodChannel(flutterEngine: FlutterEngine) {
        MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            METHOD_CHANNEL
        ).setMethodCallHandler { call, result ->
            when (call.method) {
                "startService" -> {
                    val success = startVoiceService()
                    result.success(success)
                }
                "stopService" -> {
                    stopVoiceService()
                    result.success(true)
                }
                "isServiceRunning" -> {
                    result.success(VoiceAssistantService.isRunning)
                }
                "requestPermissions" -> {
                    requestAllPermissions()
                    result.success(true)
                }
                "checkPermissions" -> {
                    result.success(checkAllPermissions())
                }
                "requestBatteryOptimization" -> {
                    requestOverlayPermission() // Ask for popup over other apps
                    requestBatteryOptimizationExclusion() // Ask for doze whitelisting
                    result.success(true)
                }
                "isBatteryOptimized" -> {
                    result.success(isBatteryOptimized())
                }
                "getServiceStatus" -> {
                    val status = mapOf(
                        "isRunning" to VoiceAssistantService.isRunning,
                        "lastText" to VoiceAssistantService.lastRecognizedText,
                        "lastAction" to VoiceAssistantService.lastCommandAction,
                        "status" to VoiceAssistantService.statusMessage
                    )
                    result.success(status)
                }
                "getNotes" -> {
                    val router = CommandRouter(this)
                    val notes = router.getAllNotes()
                    result.success(notes)
                }
                // ── Model management ────────────────────────
                "isModelReady" -> {
                    result.success(modelManager.isModelReady())
                }
                "downloadModel" -> {
                    downloadModel()
                    result.success(true)
                }
                "setPorcupineKey" -> {
                    val key = (call.argument<String>("key") ?: "").trim()
                    Log.d(TAG, "Saving Porcupine key, length=${key.length}")
                    prefs.edit().putString("porcupine_access_key", key).apply()
                    result.success(true)
                }
                "getPorcupineKey" -> {
                    val key = prefs.getString("porcupine_access_key", "") ?: ""
                    result.success(key)
                }
                "validatePorcupineKey" -> {
                    val key = (call.argument<String>("key") ?: "").trim()
                    if (key.isEmpty()) {
                        result.success(mapOf("valid" to false, "error" to "Key is empty"))
                    } else {
                        // Validate on background thread to avoid blocking UI
                        Thread {
                            try {
                                val porcupine = ai.picovoice.porcupine.Porcupine.Builder()
                                    .setAccessKey(key)
                                    .setKeyword(ai.picovoice.porcupine.Porcupine.BuiltInKeyword.PORCUPINE)
                                    .build(applicationContext)
                                porcupine.delete()
                                runOnUiThread {
                                    result.success(mapOf("valid" to true, "error" to ""))
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Porcupine key validation failed", e)
                                runOnUiThread {
                                    result.success(mapOf("valid" to false, "error" to (e.message ?: "Unknown error")))
                                }
                            }
                        }.start()
                    }
                }
                else -> {
                    result.notImplemented()
                }
            }
        }
    }

    private fun setupEventChannel(flutterEngine: FlutterEngine) {
        EventChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            EVENT_CHANNEL
        ).setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                eventSink = events

                // Set up status listener on the service
                VoiceAssistantService.statusListener = { status, text, action ->
                    runOnUiThread {
                        eventSink?.success(
                            mapOf(
                                "status" to status,
                                "text" to text,
                                "action" to action
                            )
                        )
                    }
                }
            }

            override fun onCancel(arguments: Any?) {
                eventSink = null
                VoiceAssistantService.statusListener = null
            }
        })
    }

    private fun downloadModel() {
        modelManager.setupModel(object : VoskModelManager.SetupCallback {
            override fun onProgress(percent: Int) {
                runOnUiThread {
                    eventSink?.success(
                        mapOf(
                            "status" to "downloading",
                            "text" to "$percent",
                            "action" to "Setting up speech model... $percent%"
                        )
                    )
                }
            }

            override fun onComplete(model: Model) {
                VoiceAssistantService.voskModel = model
                runOnUiThread {
                    eventSink?.success(
                        mapOf(
                            "status" to "modelReady",
                            "text" to "",
                            "action" to "Speech model ready!"
                        )
                    )
                }
            }

            override fun onError(error: String) {
                runOnUiThread {
                    eventSink?.success(
                        mapOf(
                            "status" to "modelError",
                            "text" to "",
                            "action" to error
                        )
                    )
                }
            }
        })
    }

    private fun startVoiceService(): Boolean {
        if (!checkAllPermissions()) {
            Log.w(TAG, "Permissions not granted, cannot start service")
            return false
        }

        if (!modelManager.isModelReady()) {
            Log.w(TAG, "Model not downloaded yet")
            return false
        }

        val intent = Intent(this, VoiceAssistantService::class.java)

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            prefs.edit().putBoolean(BootReceiver.KEY_SERVICE_ENABLED, true).apply()
            Log.d(TAG, "Voice service started")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start service", e)
            false
        }
    }

    private fun stopVoiceService() {
        val intent = Intent(this, VoiceAssistantService::class.java)
        stopService(intent)
        prefs.edit().putBoolean(BootReceiver.KEY_SERVICE_ENABLED, false).apply()
        Log.d(TAG, "Voice service stopped")
    }

    private fun checkAllPermissions(): Boolean {
        val audioPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        val notificationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        return audioPermission && notificationPermission
    }

    private fun requestAllPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        ActivityCompat.requestPermissions(
            this,
            permissions.toTypedArray(),
            PERMISSION_REQUEST_CODE
        )
    }

    // ── Special Permissions (Bypass OS Blocks) ─────────────────

    private fun requestOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            Log.d(TAG, "Requesting SYSTEM_ALERT_WINDOW permission")
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }
    }

    private fun isBatteryOptimized(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return !pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun requestBatteryOptimizationExclusion() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            Log.d(TAG, "Requesting REQUEST_IGNORE_BATTERY_OPTIMIZATIONS")
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == PERMISSION_REQUEST_CODE) {
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            eventSink?.success(
                mapOf(
                    "status" to "permissionResult",
                    "text" to if (allGranted) "granted" else "denied",
                    "action" to ""
                )
            )
        }
    }
}
