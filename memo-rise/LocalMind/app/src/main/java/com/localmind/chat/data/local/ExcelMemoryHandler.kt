package com.localmind.chat.data.local

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.localmind.chat.ai.ExtractedFact
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader

sealed interface ImportResult {
    data class Success(val facts: List<ExtractedFact>) : ImportResult
    data class Error(val message: String) : ImportResult
}

/**
 * Handles Excel / CSV export and import validation for learned memories.
 */
object ExcelMemoryHandler {

    private const val TAG = "ExcelMemoryHandler"

    fun exportToCsvFile(context: Context, memories: List<MemoryEntity>): File {
        val exportFile = File(context.cacheDir, "LocalMind_Learned_Memories.csv")
        FileOutputStream(exportFile).use { output ->
            val writer = output.bufferedWriter()
            writer.write("Fact,Category,Confidence\n")
            memories.forEach { memory ->
                val cleanText = memory.text.replace("\"", "\"\"")
                val cleanCategory = memory.category.replace("\"", "\"\"")
                writer.write("\"$cleanText\",\"$cleanCategory\",${memory.confidence}\n")
            }
            writer.flush()
        }
        return exportFile
    }

    fun parseAndValidateFile(context: Context, uri: Uri): ImportResult {
        val fileName = getFileName(context, uri).lowercase()

        // 1. File format / extension check
        if (!fileName.endsWith(".csv") && !fileName.endsWith(".xlsx") && !fileName.endsWith(".xls")) {
            return ImportResult.Error("The file format is not compatible.")
        }

        return try {
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: return ImportResult.Error("The file format is not compatible.")

            val reader = BufferedReader(InputStreamReader(inputStream))
            val headerLine = reader.readLine()

            if (headerLine == null) {
                reader.close()
                return ImportResult.Error("The data is not in correct format.")
            }

            val headers = headerLine.split(",").map { it.trim().trim('"').lowercase() }

            // 2. Column format check (Must contain 'fact' or 'text')
            val factColIndex = headers.indexOfFirst { it == "fact" || it == "text" }
            val categoryColIndex = headers.indexOfFirst { it == "category" }

            if (factColIndex == -1) {
                reader.close()
                return ImportResult.Error("The data is not in correct format.")
            }

            val importedFacts = mutableListOf<ExtractedFact>()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val currentLine = line?.trim() ?: continue
                if (currentLine.isBlank()) continue

                val columns = parseCsvLine(currentLine)
                if (columns.size > factColIndex) {
                    val factText = columns[factColIndex].trim()
                    val category = if (categoryColIndex != -1 && columns.size > categoryColIndex) {
                        columns[categoryColIndex].trim().ifBlank { "imported" }
                    } else "imported"

                    if (factText.isNotBlank()) {
                        importedFacts.add(
                            ExtractedFact(
                                text = factText,
                                category = category,
                                confidence = 0.90f
                            )
                        )
                    }
                }
            }
            reader.close()

            if (importedFacts.isEmpty()) {
                ImportResult.Error("The data is not in correct format.")
            } else {
                ImportResult.Success(importedFacts)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing memory file", e)
            ImportResult.Error("The file format is not compatible.")
        }
    }

    private fun parseCsvLine(line: String): List<String> {
        val tokens = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false

        for (c in line) {
            when {
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> {
                    tokens.add(sb.toString().trim())
                    sb.clear()
                }
                else -> sb.append(c)
            }
        }
        tokens.add(sb.toString().trim())
        return tokens
    }

    private fun getFileName(context: Context, uri: Uri): String {
        var name = ""
        try {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index != -1) {
                        name = it.getString(index)
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Failed to get filename from cursor", e)
        }
        return name.ifBlank { uri.path.orEmpty() }
    }
}
