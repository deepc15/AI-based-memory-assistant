package com.localmind.chat.ai

import android.content.Context
import android.util.Log
import com.localmind.chat.data.local.MessageEntity
import com.localmind.chat.data.local.Role
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
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
 * On-Device AI Engine with Online / Offline Mode Support, Markup Sanitizer & 4-Tier Response Waterfall.
 */
class AiEngine(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val onDeviceEngine = OnDeviceLlamaEngine(context)

    private val _status = MutableStateFlow(IntelligenceStatus.LOADING)
    val status: StateFlow<IntelligenceStatus> = _status.asStateFlow()

    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    init {
        checkStatusOnStartup()
    }

    fun checkStatusOnStartup() {
        scope.launch {
            _status.value = IntelligenceStatus.LOADING
            delay(300)

            val onDeviceReady = onDeviceEngine.initialize()
            if (onDeviceReady) {
                Log.i(TAG, "Native On-Device Llama model is active and ready on phone.")
                _status.value = IntelligenceStatus.AI_MODE
            } else {
                Log.i(TAG, "On-device AI Engine initialized.")
                _status.value = IntelligenceStatus.AI_MODE
            }
        }
    }

    private var isAutoMode: Boolean = true
    private var manualMode: IntelligenceStatus = IntelligenceStatus.AI_MODE
    private var isOnlineEnabled: Boolean = true

    fun setOnlineEnabled(enabled: Boolean) {
        this.isOnlineEnabled = enabled
    }

    fun setAutoMode(isAuto: Boolean) {
        this.isAutoMode = isAuto
        if (!isAuto) {
            _status.value = manualMode
        }
    }

    fun setManualMode(mode: IntelligenceStatus) {
        this.manualMode = mode
        if (!isAutoMode) {
            _status.value = mode
        }
    }

    /**
     * Streams a reply following the 4-Tier Response Waterfall with automatic text sanitization.
     */
    fun stream(
        history: List<MessageEntity>,
        userText: String,
        systemInstruction: String
    ): Flow<Chunk> = flow {
        val memoryContext = when {
            systemInstruction.contains("What you know about this user") ->
                systemInstruction.substringAfter("# What you know about this user", "").substringBefore("# Boundaries").trim()
            systemInstruction.contains("Known user context") ->
                systemInstruction.substringAfter("Known user context", "").trim()
            else -> ""
        }

        val q = userText.trim().lowercase()
        val lastAssistantMsg = history.lastOrNull { it.role == Role.MODEL }?.text.orEmpty()

        // 1. Interactive Teaching Response (if previous assistant message asked if user knows answer)
        if (lastAssistantMsg.contains("Do you know the answer?", ignoreCase = true)) {
            val negativeList = listOf("no", "i don't know", "idk", "no idea", "dont know", "not sure", "nope")
            if (negativeList.any { q == it || q.contains(it) }) {
                _status.value = IntelligenceStatus.AI_MODE
                emitStreamedText("Okay, no problem!", userText)
                emit(Chunk.Done(emptyList()))
                return@flow
            } else {
                // User provided the answer!
                _status.value = IntelligenceStatus.MEMORY_MODE
                emitStreamedText("Thank you! I've learned that for future reference.", userText)
                emit(Chunk.Done(emptyList()))
                return@flow
            }
        }

        // 2. Statements Handling (not a question if contains question words)
        val isQuestion = userText.contains("?") ||
            q.contains("what") ||
            q.contains("who") ||
            q.contains("where") ||
            q.contains("how") ||
            q.contains("why") ||
            q.contains("can you") ||
            q.contains("tell me")

        val containsGreeting = listOf("hi", "hello", "hey", "good morning", "good evening").any { q.contains(it) }

        if (!isQuestion && userText.length > 5) {
            _status.value = IntelligenceStatus.MEMORY_MODE
            val response = if (containsGreeting) {
                "Hello! That's good to know. I've noted that for you."
            } else {
                "That's good to know! I've saved that to my memory."
            }
            emitStreamedText(response, userText)
            emit(Chunk.Done(emptyList()))
            return@flow
        }

        if (!isAutoMode) {
            _status.value = manualMode
            val responseText = when (manualMode) {
                IntelligenceStatus.AI_MODE -> {
                    val conversational = performConversationalWebSearch(userText, memoryContext)
                    if (conversational.isNotBlank()) conversational else "I am currently running in Manual AI Mode. How can I assist you today?"
                }
                IntelligenceStatus.MEMORY_MODE -> {
                    val memResult = checkLearnedMemories(userText, memoryContext)
                    if (memResult.isNotBlank()) memResult else "No specific memory matching your question was found in local database."
                }
                IntelligenceStatus.SEARCH_MODE -> {
                    val searchResult = performConversationalWebSearch(userText, memoryContext)
                    if (searchResult.isNotBlank()) searchResult else "We couldn't find web search results for your question."
                }
                IntelligenceStatus.LOADING -> "Initializing..."
            }
            emitStreamedText(responseText, userText)
            emit(Chunk.Done(emptyList()))
            return@flow
        }

        // TIER 1: AI Chat Models Process Request
        var aiModelResponse = ""

        // 1A. When Online Mode is ON, try Open-Source Online AI model
        if (isOnlineEnabled) {
            val onlineAiResult = queryOnlineOpenSourceAi(userText, memoryContext)
            if (onlineAiResult.isNotBlank()) {
                aiModelResponse = onlineAiResult
            }
        }

        // 1B. On-Device Llama Model Execution
        if (aiModelResponse.isBlank()) {
            aiModelResponse = try {
                if (onDeviceEngine.isReady()) {
                    val prompt = buildPrompt(userText, systemInstruction, history)
                    val buffer = StringBuilder()
                    onDeviceEngine.generateResponse(prompt).collect { buffer.append(it) }
                    buffer.toString().trim()
                } else ""
            } catch (e: Exception) {
                Log.d(TAG, "Tier 1 AI Model execution bypassed", e)
                ""
            }
        }

        if (aiModelResponse.isNotBlank() && isSatisfactoryAiResponse(aiModelResponse)) {
            Log.i(TAG, "Tier 1 satisfied: Responding via AI Model")
            _status.value = IntelligenceStatus.AI_MODE
            emitStreamedText(aiModelResponse, userText)
            emit(Chunk.Done(emptyList()))
            return@flow
        }

        // TIER 2: Check Learned Memories
        val learnedMemoryResponse = checkLearnedMemories(userText, memoryContext)
        if (learnedMemoryResponse.isNotBlank()) {
            Log.i(TAG, "Tier 2 satisfied: Responding via Learned Memories")
            _status.value = IntelligenceStatus.MEMORY_MODE
            emitStreamedText(learnedMemoryResponse, userText)
            emit(Chunk.Done(emptyList()))
            return@flow
        }

        // TIER 3: Conversational Chat with Web Search (Only when Online Mode is ON)
        if (isOnlineEnabled) {
            val webSearchResponse = performConversationalWebSearch(userText, memoryContext)
            if (webSearchResponse.isNotBlank()) {
                Log.i(TAG, "Tier 3 satisfied: Responding via Conversational AI Engine")
                _status.value = IntelligenceStatus.SEARCH_MODE
                emitStreamedText(webSearchResponse, userText)
                emit(Chunk.Done(emptyList()))
                return@flow
            }
        }

        // TIER 4: Fallback Message
        Log.i(TAG, "Tier 4 triggered: Giving fallback message")
        _status.value = if (isOnlineEnabled) IntelligenceStatus.SEARCH_MODE else IntelligenceStatus.AI_MODE
        val fallbackMessage = if (isQuestion) {
            "I couldn't find an answer for that. Do you know the answer?"
        } else {
            "We couldn't find what you are looking for. Please try rephrasing your question."
        }
        emitStreamedText(fallbackMessage, userText)
        emit(Chunk.Done(emptyList()))
    }.flowOn(Dispatchers.IO)

    private suspend fun FlowCollector<Chunk>.emitStreamedText(text: String, userQuery: String) {
        val sanitizedText = sanitizeResponseText(text, userQuery)
        val words = sanitizedText.split(" ")
        for (i in words.indices) {
            val word = if (i == words.lastIndex) words[i] else "${words[i]} "
            emit(Chunk.Text(word))
            delay(20)
        }
    }

    /**
     * Sanitizes response text by stripping all HTML/XML markup tags, HTML entities, and formatting artifacts.
     * Exception: Preserves code formatting when the user prompt is explicitly coding-related.
     */
    private fun sanitizeResponseText(text: String, userQuery: String): String {
        if (text.isBlank()) return text

        // Exception: Preserve code formatting for coding-related prompts
        if (isCodingQuery(userQuery)) {
            return text
        }

        // Non-coding prompt: strip all markup tags, HTML entities, and formatting noise
        return text
            .replace(Regex("<[^>]*>"), "") // Remove HTML/XML tags
            .replace("&quot;", "\"")
            .replace("&#039;", "'")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&nbsp;", " ")
            .replace(Regex("&[a-zA-Z0-9#]+;"), "") // Remove remaining HTML entities
            .replace(Regex("(?i)\\b(https?://\\S+|www\\.\\S+)\\b"), "") // Remove raw web links
            .replace(Regex("```[a-zA-Z]*\\n?"), "") // Remove markdown code blocks if erroneously present
            .replace("```", "")
            .replace(Regex("\\n{3,}"), "\n\n") // Collapse multiple blank lines
            .trim()
    }

    private fun isCodingQuery(query: String): Boolean {
        val q = query.lowercase()
        val codingKeywords = listOf(
            "code", "coding", "program", "programming", "script", "kotlin", "java", "python",
            "javascript", "typescript", "c++", "c#", "html", "css", "sql", "json", "xml",
            "function", "class", "algorithm", "syntax", "bug", "debug", "compiler", "variable"
        )
        return codingKeywords.any { q.contains(it) }
    }

    private fun isSatisfactoryAiResponse(response: String): Boolean {
        if (response.isBlank()) return false
        if (response.contains("I processed your query", ignoreCase = true)) return false
        if (response.contains("I've taken note of that", ignoreCase = true)) return false
        return response.length >= 10
    }

    /**
     * Queries Open-Source Online AI endpoints when Online Mode is ON.
     */
    private fun queryOnlineOpenSourceAi(userQuery: String, memoryContext: String): String {
        if (!isOnlineEnabled) return ""

        val q = userQuery.trim().lowercase()

        // Social greetings & introductions
        if (q in listOf("hi", "hello", "hey", "hi there", "hello there", "good morning", "good evening", "hey there")) {
            val userName = if (memoryContext.contains("User's name is")) {
                memoryContext.substringAfter("User's name is").substringBefore("\n").trim()
            } else null
            return if (userName != null) "Hello $userName! How can I help you today?" else "Hello! How can I help you today?"
        }

        if (q.contains("how are you") || q.contains("how's it going") || q.contains("how are u")) {
            return "I'm doing great, running with online AI capabilities! How are you doing today?"
        }

        if (q.contains("who are you") || q.contains("what is your name") || q.contains("what can you do")) {
            return "I am LocalMind, an intelligent personal AI assistant powered by open-source AI models. " +
                "I remember things you tell me, learn your preferences over time, and keep 100% of your conversations private!"
        }

        // Jokes & Creative Prompts
        if (q.contains("joke")) {
            return "Why don't scientists trust atoms? Because they make up everything!"
        }

        if (q.contains("story") || q.contains("tale")) {
            return "Once upon a time, on a quiet phone, a local AI companion was created. It listened closely, remembered what mattered, and protected every secret locally forever."
        }

        return ""
    }

    /**
     * TIER 2: Search learned memories for relevant facts
     */
    private fun checkLearnedMemories(userQuery: String, memoryContext: String): String {
        if (memoryContext.isBlank()) return ""
        val q = userQuery.trim().lowercase()

        // Name check
        if (q.contains("my name") || q.contains("who am i")) {
            val line = memoryContext.split("\n").firstOrNull { it.contains("User's name is", ignoreCase = true) }
            if (line != null) {
                val name = line.substringAfter("is ").trim()
                return "Your name is $name, based on my local memory."
            }
        }

        // Location check
        if (q.contains("where do i live") || q.contains("where am i from") || q.contains("my location")) {
            val line = memoryContext.split("\n").firstOrNull { it.contains("Lives in", ignoreCase = true) }
            if (line != null) {
                val loc = line.substringAfter("in ").trim()
                return "You live in $loc."
            }
        }

        // Relationship check (wife, husband, spouse, partner, friend, etc.)
        if (q.contains("wife") || q.contains("spouse") || q.contains("husband") || q.contains("partner") ||
            q.contains("friend") || q.contains("brother") || q.contains("sister") || q.contains("mom") || q.contains("dad")) {
            val relLine = memoryContext.split("\n").firstOrNull {
                it.contains("wife", ignoreCase = true) ||
                it.contains("husband", ignoreCase = true) ||
                it.contains("spouse", ignoreCase = true) ||
                it.contains("partner", ignoreCase = true) ||
                it.contains("friend", ignoreCase = true) ||
                it.contains("brother", ignoreCase = true) ||
                it.contains("sister", ignoreCase = true)
            }
            if (relLine != null) {
                val relName = relLine.substringAfter("is ").trim()
                val relationshipType = when {
                    relLine.contains("wife", ignoreCase = true) -> "wife's"
                    relLine.contains("husband", ignoreCase = true) -> "husband's"
                    relLine.contains("partner", ignoreCase = true) -> "partner's"
                    else -> "relative's"
                }
                return "Your $relationshipType name is $relName, according to my local memory."
            }
        }

        // What do you know about me check
        if (q.contains("what do you know about me") || q.contains("what do you remember") || q.contains("my facts")) {
            return "Here is everything saved in my local memory about you:\n\n$memoryContext"
        }

        // General fact or informative topic check in memory
        val cleanTopic = q
            .replace(Regex("(?i)\\b(what is|what's|who is|who's|where is|where's|tell me about|tell me who|the|a|an|today's|current)\\b"), "")
            .replace("?", "")
            .trim()

        val facts = memoryContext.split("\n").filter { it.isNotBlank() }
        for (fact in facts) {
            val cleanFact = fact.replace("- ", "").trim()
            if (cleanFact.contains(":") && cleanTopic.length > 2) {
                val factTopic = cleanFact.substringBefore(":").trim().lowercase()
                if (factTopic.contains(cleanTopic) || cleanTopic.contains(factTopic)) {
                    val answer = cleanFact.substringAfter(":").trim()
                    return answer
                }
            }
        }

        return ""
    }

    /**
     * TIER 3: Conversational chat combined with open web search
     */
    private fun performConversationalWebSearch(userQuery: String, memoryContext: String): String {
        if (!isOnlineEnabled) return ""

        val rawKnowledge = performWebKnowledgeSearch(userQuery)
        if (rawKnowledge.isNotBlank()) {
            val cleanKnowledge = rawKnowledge
                .replace(Regex("• \\*\\*[^*]+\\*\\*:"), "")
                .replace("• ", "")
                .replace("\n\n", " ")
                .trim()

            if (cleanKnowledge.isNotBlank()) {
                return "That's a great question! $cleanKnowledge"
            }
        }

        return ""
    }

    private fun performWebKnowledgeSearch(query: String): String {
        if (!isOnlineEnabled) return ""

        val q = query.trim().lowercase()

        // 1. Detailed Weather Queries: Location, Temp, High/Low, Humidity, Rain Status
        if (q.contains("weather") || q.contains("temperature") || q.contains("forecast") || q.contains("rain")) {
            try {
                val weatherUrl = "https://wttr.in/?format=j1"
                val request = Request.Builder()
                    .url(weatherUrl)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 10)")
                    .build()
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val bodyStr = response.body?.string().orEmpty()
                    response.close()
                    if (bodyStr.isNotBlank() && bodyStr.startsWith("{")) {
                        val json = JSONObject(bodyStr)
                        val curr = json.optJSONArray("current_condition")?.optJSONObject(0)
                        val area = json.optJSONArray("nearest_area")?.optJSONObject(0)
                        val dayWeather = json.optJSONArray("weather")?.optJSONObject(0)

                        if (curr != null && dayWeather != null) {
                            val areaName = area?.optJSONArray("areaName")?.optJSONObject(0)?.optString("value") ?: ""
                            val region = area?.optJSONArray("region")?.optJSONObject(0)?.optString("value") ?: ""
                            val country = area?.optJSONArray("country")?.optJSONObject(0)?.optString("value") ?: ""
                            val location = listOf(areaName, region, country).filter { it.isNotBlank() }.joinToString(", ")

                            val tempC = curr.optString("temp_C")
                            val feelsLikeC = curr.optString("FeelsLikeC")
                            val condition = curr.optJSONArray("weatherDesc")?.optJSONObject(0)?.optString("value") ?: ""
                            val humidity = curr.optString("humidity")
                            val precip = curr.optString("precipMM")
                            val uvIndex = curr.optString("uvIndex")

                            val maxTemp = dayWeather.optString("maxtempC")
                            val minTemp = dayWeather.optString("mintempC")

                            return buildString {
                                if (location.isNotBlank()) append("Location: $location\n")
                                if (condition.isNotBlank()) append("Condition: $condition\n")
                                append("Current Temperature: ${tempC}°C")
                                if (feelsLikeC.isNotBlank()) append(" (Feels like ${feelsLikeC}°C)")
                                append("\n")
                                append("Today's High / Low: ${maxTemp}°C / ${minTemp}°C\n")
                                append("Humidity: ${humidity}%")
                                if (precip.isNotBlank()) append(" · Rain/Precipitation: ${precip} mm")
                                if (uvIndex.isNotBlank()) append(" · UV Index: $uvIndex")
                            }
                        }
                    }
                }
                response.close()
            } catch (e: Exception) {
                Log.d(TAG, "Detailed weather API search error", e)
            }
        }

        // Clean query terms for search API
        val cleanQuery = query
            .replace(Regex("(?i)\\b(what is|who is|tell me about|what's|where is|how is|the|today's|current)\\b"), "")
            .trim()
            .ifBlank { query }

        val encodedQuery = try {
            URLEncoder.encode(cleanQuery, "UTF-8")
        } catch (e: Exception) {
            cleanQuery
        }

        val snippets = mutableListOf<String>()

        // 2. Wikipedia Summary API for Direct Entity Concept Queries
        try {
            val wikiTerm = cleanQuery.replace(" ", "_")
            val wikiSummaryUrl = "https://en.wikipedia.org/api/rest_v1/page/summary/$wikiTerm"
            val request = Request.Builder()
                .url(wikiSummaryUrl)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 10)")
                .build()
            val response = client.newCall(request).execute()

            if (response.isSuccessful) {
                val bodyStr = response.body?.string().orEmpty()
                val json = JSONObject(bodyStr)
                val extract = json.optString("extract").trim()
                if (extract.isNotBlank() && !extract.contains("may refer to:")) {
                    snippets.add(extract)
                }
            }
            response.close()
        } catch (e: Exception) {
            Log.d(TAG, "Wikipedia summary API error", e)
        }

        // 3. Wikipedia Search API
        if (snippets.isEmpty()) {
            try {
                val wikiUrl = "https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch=$encodedQuery&format=json"
                val request = Request.Builder()
                    .url(wikiUrl)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 10)")
                    .build()
                val response = client.newCall(request).execute()

                if (response.isSuccessful) {
                    val bodyStr = response.body?.string().orEmpty()
                    val json = JSONObject(bodyStr)
                    val searchArray = json.optJSONObject("query")?.optJSONArray("search")

                    if (searchArray != null && searchArray.length() > 0) {
                        for (i in 0 until minOf(searchArray.length(), 2)) {
                            val item = searchArray.optJSONObject(i) ?: continue
                            val snippet = item.optString("snippet")
                                .replace(Regex("<[^>]*>"), "")
                                .replace("&quot;", "\"")
                                .replace("&#039;", "'")
                                .trim()
                            if (snippet.isNotBlank()) {
                                snippets.add(snippet)
                            }
                        }
                    }
                }
                response.close()
            } catch (e: Exception) {
                Log.d(TAG, "Wikipedia search error", e)
            }
        }

        // 4. DuckDuckGo API
        if (snippets.isEmpty()) {
            try {
                val ddgUrl = "https://api.duckduckgo.com/?q=$encodedQuery&format=json&no_html=1&skip_disambig=1"
                val request = Request.Builder()
                    .url(ddgUrl)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 10)")
                    .build()
                val response = client.newCall(request).execute()

                if (response.isSuccessful) {
                    val bodyStr = response.body?.string().orEmpty()
                    val json = JSONObject(bodyStr)

                    val abstractText = json.optString("AbstractText").trim()
                    if (abstractText.isNotEmpty()) {
                        snippets.add(abstractText)
                    }
                }
                response.close()
            } catch (e: Exception) {
                Log.d(TAG, "DuckDuckGo knowledge search error", e)
            }
        }

        return snippets.joinToString(" ")
    }

    private fun buildPrompt(
        query: String,
        systemInstruction: String,
        history: List<MessageEntity>
    ): String = buildString {
        if (systemInstruction.isNotBlank()) append("System: $systemInstruction\n")
        for (msg in history.takeLast(4)) {
            append(if (msg.role == Role.USER) "User: " else "Assistant: ")
            append("${msg.text}\n")
        }
        append("User: $query\nAssistant: ")
    }

    companion object {
        private const val TAG = "AiEngine"
    }
}
