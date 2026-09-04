package com.localmind.chat.ai

import android.util.Log
import com.localmind.chat.BuildConfig
import com.localmind.chat.data.local.MessageEntity
import com.localmind.chat.data.local.Role
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** A web page or local reference consulted while answering. */
data class Source(val title: String, val url: String)

sealed interface Chunk {
    /** A piece of text to append to the visible reply. */
    data class Text(val delta: String) : Chunk

    /** Emitted once at the end. */
    data class Done(val sources: List<Source>) : Chunk
}

/**
 * 100% Local Open-Source Llama AI Engine with Open Web Search Fallback.
 *
 * Connects to a local open-source Llama model endpoint (e.g. Ollama or local Llama API).
 * If the local Llama server is offline or unreachable, it falls back seamlessly to
 * an open search engine (DuckDuckGo / Wikipedia) to curate a smart, organized response.
 */
class AiEngine(
    private val baseUrl: String = BuildConfig.LOCAL_LLAMA_URL,
    private val modelName: String = BuildConfig.LOCAL_LLAMA_MODEL
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Streams a reply turn-by-turn. Tries local Llama first, then falls back to open search.
     */
    fun stream(
        history: List<MessageEntity>,
        userText: String,
        systemInstruction: String
    ): Flow<Chunk> = flow {
        try {
            streamFromLocalLlama(history, userText, systemInstruction)
        } catch (e: Exception) {
            Log.w(TAG, "Local Llama server offline/unreachable. Using open web search fallback.", e)
            streamFallbackSearchResponse(userText, systemInstruction)
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun FlowCollector<Chunk>.streamFromLocalLlama(
        history: List<MessageEntity>,
        userText: String,
        systemInstruction: String
    ) {
        val messagesArray = JSONArray()

        if (systemInstruction.isNotBlank()) {
            messagesArray.put(
                JSONObject().apply {
                    put("role", "system")
                    put("content", systemInstruction)
                }
            )
        }

        for (message in history) {
            messagesArray.put(
                JSONObject().apply {
                    put("role", if (message.role == Role.USER) "user" else "assistant")
                    put("content", message.text)
                }
            )
        }

        messagesArray.put(
            JSONObject().apply {
                put("role", "user")
                put("content", userText)
            }
        )

        val requestJson = JSONObject().apply {
            put("model", modelName)
            put("messages", messagesArray)
            put("stream", true)
        }

        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/api/chat")
            .post(requestJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            val errorBody = response.body?.string().orEmpty()
            response.close()
            throw IllegalStateException("Local Llama API error (${response.code}): $errorBody")
        }

        val body = response.body ?: throw IllegalStateException("Empty response body from Local Llama API")
        val reader = BufferedReader(InputStreamReader(body.byteStream()))

        try {
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val currentLine = line?.trim().orEmpty()
                if (currentLine.isEmpty()) continue

                val json = try {
                    JSONObject(currentLine)
                } catch (e: Exception) {
                    continue
                }

                val messageObj = json.optJSONObject("message")
                val delta = messageObj?.optString("content").orEmpty()
                if (delta.isNotEmpty()) {
                    emit(Chunk.Text(delta))
                }

                if (json.optBoolean("done", false)) {
                    break
                }
            }
        } finally {
            reader.close()
            body.close()
            response.close()
        }

        emit(Chunk.Done(emptyList()))
    }

    /**
     * Curates a smart, organized answer from open search engines (DuckDuckGo / Wikipedia).
     */
    private suspend fun FlowCollector<Chunk>.streamFallbackSearchResponse(
        userQuery: String,
        systemInstruction: String
    ) {
        val sources = mutableListOf<Source>()
        val searchResults = performOpenWebSearch(userQuery, sources)

        val responseText = if (searchResults.isNotBlank()) {
            buildString {
                append("Here is an organized answer compiled from open web search results:\n\n")
                append(searchResults)
                append("\n\n*(Note: Local Llama server is currently offline. Answers were curated directly via open web search.)*")
            }
        } else {
            buildString {
                append("I understand you're asking about: \"$userQuery\".\n\n")
                if (systemInstruction.contains("Known user context")) {
                    val memoryContext = systemInstruction.substringAfter("Known user context", "").trim()
                    if (memoryContext.isNotBlank()) {
                        append("Based on what you've shared with me:\n")
                        append(memoryContext.take(200))
                        append("\n\n")
                    }
                }
                append("I am operating in offline fallback mode while the local Llama server is unreachable. Please start your local Llama server (`ollama serve`) for full AI reasoning.")
            }
        }

        // Stream the fallback response line by line for a smooth typing effect
        for (line in responseText.split("\n")) {
            emit(Chunk.Text(line + "\n"))
            delay(20)
        }

        emit(Chunk.Done(sources))
    }

    private fun performOpenWebSearch(query: String, sources: MutableList<Source>): String {
        val encodedQuery = try {
            URLEncoder.encode(query, "UTF-8")
        } catch (e: Exception) {
            query
        }

        val snippetList = mutableListOf<String>()

        // 1. DuckDuckGo Instant Answer API
        try {
            val ddgUrl = "https://api.duckduckgo.com/?q=$encodedQuery&format=json&no_html=1&skip_disambig=1"
            val ddgRequest = Request.Builder().url(ddgUrl).build()
            val ddgResponse = client.newCall(ddgRequest).execute()

            if (ddgResponse.isSuccessful) {
                val bodyStr = ddgResponse.body?.string().orEmpty()
                val json = JSONObject(bodyStr)

                val abstractText = json.optString("AbstractText").trim()
                val abstractUrl = json.optString("AbstractURL").trim()
                val heading = json.optString("Heading").trim()

                if (abstractText.isNotEmpty()) {
                    snippetList.add("• **$heading**: $abstractText")
                    if (abstractUrl.isNotEmpty()) {
                        sources.add(Source(heading.ifEmpty { "DuckDuckGo Summary" }, abstractUrl))
                    }
                }

                val relatedTopics = json.optJSONArray("RelatedTopics")
                if (relatedTopics != null) {
                    for (i in 0 until minOf(relatedTopics.length(), 3)) {
                        val topic = relatedTopics.optJSONObject(i) ?: continue
                        val text = topic.optString("Text").trim()
                        val firstUrl = topic.optString("FirstURL").trim()
                        if (text.isNotEmpty() && text != abstractText) {
                            snippetList.add("• $text")
                            if (firstUrl.isNotEmpty()) {
                                sources.add(Source("Search Result ${i + 1}", firstUrl))
                            }
                        }
                    }
                }
            }
            ddgResponse.close()
        } catch (e: Exception) {
            Log.d(TAG, "DuckDuckGo search error", e)
        }

        // 2. Wikipedia Search API
        if (snippetList.isEmpty()) {
            try {
                val wikiUrl = "https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch=$encodedQuery&format=json"
                val wikiRequest = Request.Builder().url(wikiUrl).build()
                val wikiResponse = client.newCall(wikiRequest).execute()

                if (wikiResponse.isSuccessful) {
                    val bodyStr = wikiResponse.body?.string().orEmpty()
                    val json = JSONObject(bodyStr)
                    val searchArray = json.optJSONObject("query")?.optJSONArray("search")

                    if (searchArray != null) {
                        for (i in 0 until minOf(searchArray.length(), 3)) {
                            val item = searchArray.optJSONObject(i) ?: continue
                            val title = item.optString("title").trim()
                            val snippet = item.optString("snippet")
                                .replace(Regex("<[^>]*>"), "") // Strip HTML tags
                                .trim()

                            if (title.isNotEmpty() && snippet.isNotEmpty()) {
                                snippetList.add("• **$title**: $snippet...")
                                val wikiArticleUrl = "https://en.wikipedia.org/wiki/${URLEncoder.encode(title, "UTF-8")}"
                                sources.add(Source(title, wikiArticleUrl))
                            }
                        }
                    }
                }
                wikiResponse.close()
            } catch (e: Exception) {
                Log.d(TAG, "Wikipedia search error", e)
            }
        }

        return snippetList.joinToString("\n\n")
    }

    companion object {
        private const val TAG = "AiEngine"
    }
}
