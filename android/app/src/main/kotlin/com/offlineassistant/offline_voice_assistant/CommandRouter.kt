package com.offlineassistant.offline_voice_assistant

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CommandRouter(private val context: Context) {

    companion object {
        const val TAG = "CommandRouter"
    }

    /**
     * Routes recognized voice command to appropriate action.
     * Returns a description of the action taken, or null if no command matched.
     */
    fun routeCommand(text: String): String? {
        Log.d(TAG, "Routing command: $text")

        return when {
            // Open Gallery
            matchesCommand(text, listOf(
                "open gallery", "open photos", "show gallery",
                "show photos", "open images", "show images",
                "open photo gallery", "gallery"
            )) -> openGallery()

            // Open Camera
            matchesCommand(text, listOf(
                "open camera", "take photo", "take picture",
                "open cam", "camera", "take a photo",
                "take a picture", "launch camera"
            )) -> openCamera()

            else -> null
        }
    }

    private fun matchesCommand(input: String, commands: List<String>): Boolean {
        return commands.any { command ->
            input.contains(command) || levenshteinSimilar(input, command)
        }
    }

    /**
     * Basic fuzzy matching - checks if input is similar enough to a command.
     */
    private fun levenshteinSimilar(input: String, command: String): Boolean {
        // Simple word-based matching
        val inputWords = input.split(" ").toSet()
        val commandWords = command.split(" ").toSet()
        val commonWords = inputWords.intersect(commandWords)
        // If most command words are present in input, consider it a match
        return commandWords.size > 1 && commonWords.size >= commandWords.size - 1 &&
                commonWords.size.toFloat() / commandWords.size >= 0.6f
    }

    private fun openGallery(): String {
        return try {
            // Try standard gallery intent
            val intent = Intent(Intent.ACTION_VIEW).apply {
                type = "image/*"
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
            }

            // Fallback to content URI approach
            val fallbackIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    "image/*"
                )
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
            }

            try {
                launchIntent(intent)
            } catch (e: Exception) {
                launchIntent(fallbackIntent)
            }

            "✅ Opened Gallery"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open gallery", e)
            "❌ Failed to open gallery: ${e.message}"
        }
    }

    private fun openCamera(): String {
        return try {
            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
            }

            // Check if any camera app can handle this
            if (intent.resolveActivity(context.packageManager) != null) {
                launchIntent(intent)
                "✅ Opened Camera"
            } else {
                // Try generic camera intent
                val fallback = Intent("android.media.action.STILL_IMAGE_CAMERA").apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                launchIntent(fallback)
                "✅ Opened Camera"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open camera", e)
            "❌ Failed to open camera: ${e.message}"
        }
    }

    private fun launchIntent(intent: Intent) {
        try {
            Log.d(TAG, "Launching intent via PendingIntent: ${intent.action}")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val options = ActivityOptions.makeBasic()
                options.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                pendingIntent.send(context, 0, null, null, null, null, options.toBundle())
            } else {
                pendingIntent.send()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch external intent via PendingIntent", e)
            try {
                // Fallback direct launch
                context.startActivity(intent)
            } catch (fallbackEx: Exception) {
                Log.e(TAG, "Fallback direct launch also failed", fallbackEx)
            }
        }
    }

    private fun startDictation(): String {
        return "📝 Dictation mode started. Speak your notes, say 'stop notes' when done."
    }

    fun saveNotes(content: String): String {
        return try {
            val notesDir = File(context.filesDir, "notes")
            if (!notesDir.exists()) {
                notesDir.mkdirs()
            }

            val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
            val fileName = "note_$timestamp.txt"
            val file = File(notesDir, fileName)

            val headerTimestamp = SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss", Locale.US
            ).format(Date())

            val fileContent = buildString {
                appendLine("=== Voice Note ===")
                appendLine("Date: $headerTimestamp")
                appendLine("==================")
                appendLine()
                appendLine(content)
            }

            file.writeText(fileContent)
            Log.d(TAG, "Note saved: ${file.absolutePath}")

            file.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save notes", e)
            "Error: ${e.message}"
        }
    }

    private fun readRecentNotes(): String {
        return try {
            val notesDir = File(context.filesDir, "notes")
            if (!notesDir.exists() || notesDir.listFiles().isNullOrEmpty()) {
                return "📂 No notes found"
            }

            val recentFile = notesDir.listFiles()
                ?.sortedByDescending { it.lastModified() }
                ?.firstOrNull()

            if (recentFile != null) {
                val content = recentFile.readText()
                "📄 Recent note (${recentFile.name}):\n$content"
            } else {
                "📂 No notes found"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read notes", e)
            "❌ Error reading notes: ${e.message}"
        }
    }

    /**
     * Get list of all saved notes
     */
    fun getAllNotes(): List<Map<String, String>> {
        val notesDir = File(context.filesDir, "notes")
        if (!notesDir.exists()) return emptyList()

        return notesDir.listFiles()
            ?.sortedByDescending { it.lastModified() }
            ?.map { file ->
                mapOf(
                    "name" to file.name,
                    "path" to file.absolutePath,
                    "content" to file.readText(),
                    "modified" to SimpleDateFormat(
                        "yyyy-MM-dd HH:mm:ss", Locale.US
                    ).format(Date(file.lastModified()))
                )
            } ?: emptyList()
    }
}
