package com.localmind.chat.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Manages checking and auto-extracting bundled Llama model files from APK assets
 * into internal storage (`context.filesDir`) on first app launch.
 */
class ModelAssetsManager(private val context: Context) {

    suspend fun extractAssetModelIfNeeded(): File? = withContext(Dispatchers.IO) {
        val targetFile = File(context.filesDir, "llama3.2.task")
        if (targetFile.exists() && targetFile.length() > 0) {
            return@withContext targetFile
        }

        val assetNames = listOf("llama3.2.task", "llama3.2.bin", "llama3.bin", "model.task", "model.bin")
        var matchedAsset: String? = null

        try {
            val assetsList = context.assets.list("") ?: emptyArray()
            for (name in assetNames) {
                if (name in assetsList) {
                    matchedAsset = name
                    break
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Error listing asset files", e)
        }

        if (matchedAsset == null) {
            return@withContext null
        }

        return@withContext try {
            Log.i(TAG, "Extracting bundled model asset '$matchedAsset' into internal storage...")
            context.assets.open(matchedAsset).use { inputStream ->
                FileOutputStream(targetFile).use { outputStream ->
                    val buffer = ByteArray(64 * 1024)
                    var bytesRead: Int
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        outputStream.write(buffer, 0, bytesRead)
                    }
                    outputStream.flush()
                }
            }
            Log.i(TAG, "Model asset extracted successfully: ${targetFile.absolutePath}")
            targetFile
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract model asset '$matchedAsset'", e)
            if (targetFile.exists()) targetFile.delete()
            null
        }
    }

    companion object {
        private const val TAG = "ModelAssetsManager"
    }
}
