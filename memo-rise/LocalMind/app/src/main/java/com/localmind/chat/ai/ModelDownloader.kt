package com.localmind.chat.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

sealed interface DownloadStatus {
    data object Idle : DownloadStatus
    data class Downloading(val progressPercent: Int, val bytesDownloaded: Long, val totalBytes: Long) : DownloadStatus
    data class Completed(val file: File) : DownloadStatus
    data class Error(val message: String) : DownloadStatus
}

/**
 * Automatically downloads on-device model files directly into the app internal system folder:
 * `/data/data/com.localmind.chat/files/models/llama3.2.task`
 */
class ModelDownloader(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    fun getModelsDir(): File {
        val dir = File(context.filesDir, "models")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getModelFile(): File = File(getModelsDir(), "llama3.2.gguf")

    fun isModelDownloaded(): Boolean {
        val file = getModelFile()
        return file.exists() && file.length() > 100_000_000L // Valid model >= 100MB
    }

    fun deleteDownloadedModel(): Boolean {
        val file = getModelFile()
        return if (file.exists()) file.delete() else false
    }

    /**
     * Downloads public model into internal app models folder with progress updates.
     */
    fun downloadModel(modelUrl: String = DEFAULT_MODEL_URL): Flow<DownloadStatus> = flow {
        val destination = getModelFile()
        if (isModelDownloaded()) {
            emit(DownloadStatus.Completed(destination))
            return@flow
        }

        try {
            val request = Request.Builder().url(modelUrl).build()
            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                emit(DownloadStatus.Error("Server returned HTTP ${response.code}"))
                return@flow
            }

            val body = response.body
            if (body == null) {
                emit(DownloadStatus.Error("Empty response body"))
                return@flow
            }

            val totalBytes = body.contentLength()
            val tempFile = File(getModelsDir(), "llama3.2.task.tmp")

            var bytesDownloaded = 0L
            body.byteStream().use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(8192)
                    var read: Int
                    var lastReportTime = System.currentTimeMillis()

                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        bytesDownloaded += read

                        val now = System.currentTimeMillis()
                        if (now - lastReportTime > 500L) {
                            lastReportTime = now
                            val percent = if (totalBytes > 0) ((bytesDownloaded * 100) / totalBytes).toInt() else -1
                            emit(DownloadStatus.Downloading(percent, bytesDownloaded, totalBytes))
                        }
                    }
                }
            }

            tempFile.renameTo(destination)
            Log.i(TAG, "Model download completed: ${destination.absolutePath} (${destination.length()} bytes)")
            emit(DownloadStatus.Completed(destination))
        } catch (e: Exception) {
            Log.e(TAG, "Model download failed", e)
            emit(DownloadStatus.Error(e.message ?: "Download error"))
        }
    }.flowOn(Dispatchers.IO)

    companion object {
        private const val TAG = "ModelDownloader"
        const val DEFAULT_MODEL_URL = "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf"
    }
}
