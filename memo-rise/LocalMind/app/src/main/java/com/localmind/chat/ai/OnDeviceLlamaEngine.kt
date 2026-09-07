package com.localmind.chat.ai

import android.content.Context
import android.os.Environment
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 100% On-Device Native Llama AI Engine.
 *
 * Runs Llama 3.2 locally on the phone's GPU/CPU using Google MediaPipe Tasks GenAI.
 * Automatically auto-extracts bundled model assets from the APK upon installation.
 * Zero PC, zero server, and zero cloud network calls required.
 */
class OnDeviceLlamaEngine(private val context: Context) {

    private var llmInference: LlmInference? = null
    private var isInitialized = false
    private val assetsManager = ModelAssetsManager(context)

    /**
     * Auto-extracts asset model if needed, checks app storage or Downloads directory, and initializes LlmInference.
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        if (isInitialized && llmInference != null) return@withContext true

        // 1. Auto-extract from APK assets if bundled
        assetsManager.extractAssetModelIfNeeded()

        // 2. Find internal/downloaded model file
        val modelFile = findModelFile()
        if (modelFile == null || !modelFile.exists()) {
            Log.d(TAG, "No on-device Llama model file found in app assets, storage, or Download directory.")
            return@withContext false
        }

        return@withContext try {
            Log.i(TAG, "Initializing on-device Llama model from: ${modelFile.absolutePath}")
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelFile.absolutePath)
                .setMaxTokens(1024)
                .build()

            llmInference = LlmInference.createFromOptions(context, options)
            isInitialized = true
            Log.i(TAG, "On-device Llama engine initialized successfully on phone GPU/CPU.")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize on-device Llama model", e)
            isInitialized = false
            llmInference = null
            false
        }
    }

    fun isReady(): Boolean = isInitialized && llmInference != null

    /**
     * Streams responses turn-by-turn on-device from the phone's GPU/CPU.
     */
    fun generateResponse(prompt: String): Flow<String> = flow {
        val engine = llmInference
        if (engine == null || !isInitialized) {
            throw IllegalStateException("On-device Llama engine is not initialized.")
        }

        try {
            val response = engine.generateResponse(prompt)
            if (response.isNotBlank()) {
                for (chunk in response.chunked(6)) {
                    emit(chunk)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "On-device Llama inference error", e)
            throw e
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Locates Llama 3.2 model file on the device.
     */
    fun findModelFile(): File? {
        val modelNames = listOf(
            "llama3.2.task", "llama3.2.bin", "llama3.2.gguf",
            "llama3.bin", "llama3.task", "model.task", "model.bin"
        )

        val appModelsDir = File(context.filesDir, "models")
        if (!appModelsDir.exists()) appModelsDir.mkdirs()

        val candidateDirs = listOf(
            appModelsDir,
            context.filesDir,
            context.getExternalFilesDir(null),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        )

        for (dir in candidateDirs) {
            if (dir == null || !dir.exists()) continue
            for (name in modelNames) {
                val file = File(dir, name)
                if (file.exists() && file.length() > 0) {
                    return file
                }
            }
        }
        return null
    }

    fun close() {
        try {
            llmInference?.close()
        } catch (e: Exception) {
            Log.d(TAG, "Error closing LlmInference", e)
        } finally {
            llmInference = null
            isInitialized = false
        }
    }

    companion object {
        private const val TAG = "OnDeviceLlamaEngine"
    }
}
