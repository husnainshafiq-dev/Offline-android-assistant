package com.offlineassistant.offline_voice_assistant

import android.content.Context
import android.util.Log
import org.vosk.Model
import java.io.File
import java.io.FileOutputStream

/**
 * Manages the Vosk speech recognition model.
 * The model is bundled in assets/model-en-us and extracted
 * to internal storage on first launch for fully offline use.
 */
class VoskModelManager(private val context: Context) {

    companion object {
        const val TAG = "VoskModelManager"
        const val ASSET_DIR = "model-en-us"
        const val MODEL_DIR_NAME = "model"
    }

    interface SetupCallback {
        fun onProgress(percent: Int)
        fun onComplete(model: Model)
        fun onError(error: String)
    }

    private val modelDir: File
        get() = File(context.filesDir, MODEL_DIR_NAME)

    /**
     * Check if the model is already extracted and ready.
     */
    fun isModelReady(): Boolean {
        val dir = modelDir
        return dir.exists() && dir.isDirectory && dir.listFiles()?.isNotEmpty() == true
    }

    /**
     * Load the model from local storage. Call only if isModelReady() is true.
     */
    fun loadModel(): Model? {
        return try {
            Model(modelDir.absolutePath)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model", e)
            null
        }
    }

    /**
     * Extract the bundled model from assets to internal storage.
     * This is a one-time operation on first app launch.
     */
    fun setupModel(callback: SetupCallback) {
        Thread {
            try {
                if (isModelReady()) {
                    Log.d(TAG, "Model already extracted, loading...")
                    callback.onProgress(90)
                    val model = loadModel()
                    if (model != null) {
                        callback.onProgress(100)
                        callback.onComplete(model)
                    } else {
                        // Model corrupted, re-extract
                        modelDir.deleteRecursively()
                        extractAndLoad(callback)
                    }
                    return@Thread
                }

                extractAndLoad(callback)

            } catch (e: Exception) {
                Log.e(TAG, "Error setting up model", e)
                callback.onError("Failed to setup speech model: ${e.message}")
            }
        }.start()
    }

    private fun extractAndLoad(callback: SetupCallback) {
        Log.d(TAG, "Extracting model from assets...")
        callback.onProgress(10)

        // Clean up
        if (modelDir.exists()) {
            modelDir.deleteRecursively()
        }
        modelDir.mkdirs()

        // List and copy all asset files recursively
        val assetManager = context.assets
        copyAssetsRecursive(assetManager, ASSET_DIR, modelDir, callback, 10, 80)

        callback.onProgress(85)
        Log.d(TAG, "Extraction complete, loading model...")

        val model = Model(modelDir.absolutePath)
        callback.onProgress(100)
        callback.onComplete(model)
    }

    private fun copyAssetsRecursive(
        assetManager: android.content.res.AssetManager,
        assetPath: String,
        outputDir: File,
        callback: SetupCallback,
        startProgress: Int,
        endProgress: Int
    ) {
        val files = assetManager.list(assetPath) ?: return

        if (files.isEmpty()) {
            // It's a file, copy it
            assetManager.open(assetPath).use { input ->
                FileOutputStream(outputDir).use { output ->
                    input.copyTo(output)
                }
            }
        } else {
            // It's a directory
            outputDir.mkdirs()
            val progressPerFile = if (files.isNotEmpty()) {
                (endProgress - startProgress) / files.size
            } else 0

            files.forEachIndexed { index, fileName ->
                val childAssetPath = "$assetPath/$fileName"
                val childOutputFile = File(outputDir, fileName)
                copyAssetsRecursive(
                    assetManager, childAssetPath, childOutputFile,
                    callback, startProgress, endProgress
                )
                callback.onProgress(startProgress + (index + 1) * progressPerFile)
            }
        }
    }

    /**
     * Delete the extracted model from local storage.
     */
    fun deleteModel() {
        if (modelDir.exists()) {
            modelDir.deleteRecursively()
        }
    }
}
