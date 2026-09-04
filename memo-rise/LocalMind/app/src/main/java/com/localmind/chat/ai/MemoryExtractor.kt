package com.localmind.chat.ai

import android.util.Log
import com.localmind.chat.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

data class ExtractedFact(
    val text: String,
    val category: String,
    val confidence: Float
)

/**
 * Local AI & Offline Pattern Fact Extractor.
 *
 * Runs after a user turn to extract durable facts about the user.
 * Connects to local Llama model over HTTP when online, and falls back to
 * a pattern-based NLP rule engine when offline.
 */
class MemoryExtractor(
    private val baseUrl: String = BuildConfig.LOCAL_LLAMA_URL,
    private val modelName: String = BuildConfig.LOCAL_LLAMA_MODEL
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun extract(userText: String): List<ExtractedFact> = withContext(Dispatchers.IO) {
        if (userText.length < 8) return@withContext emptyList() // "ok", "thanks", "lol"

        val facts = try {
            extractFromLlamaServer(userText)
        } catch (e: Exception) {
            Log.d(TAG, "Local Llama server unavailable for extraction. Using offline rule extractor.", e)
            emptyList()
        }

        // Combine or fallback to pattern-based offline fact extraction
        return@withContext if (facts.isNotEmpty()) {
            facts
        } else {
            extractOfflineFacts(userText)
        }
    }

    private fun extractFromLlamaServer(userText: String): List<ExtractedFact> {
        val requestJson = JSONObject().apply {
            put("model", modelName)
            put("system", PromptBuilder.EXTRACTOR_INSTRUCTION)
            put("prompt", "Chat turn from the user:\n\n$userText")
            put("format", "json")
            put("stream", false)
        }

        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/api/generate")
            .post(requestJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            response.close()
            return emptyList()
        }

        val body = response.body?.string().orEmpty()
        response.close()

        val json = JSONObject(body)
        val raw = json.optString("response").orEmpty()
        return parse(raw)
    }

    /**
     * Offline rule-based NLP extractor when local Llama server is down.
     */
    private fun extractOfflineFacts(userText: String): List<ExtractedFact> {
        val results = mutableListOf<ExtractedFact>()
        val text = userText.trim()

        // 1. Identity & Name
        matchSingleGroup(text, "(?i)\\b(?:my name is|i am|i'm|call me)\\s+([A-Z][a-z0-9_-]+(?:\\s+[A-Z][a-z0-9_-]+)?)\\b") { name ->
            if (name.lowercase() !in setofCommonStopwords) {
                results.add(ExtractedFact("User's name is $name", "identity", 0.90f))
            }
        }

        // 2. Location
        matchSingleGroup(text, "(?i)\\b(?:i live in|i am from|i'm from|i reside in)\\s+([A-Za-z0-9\\s,]+?)(?:[.!\\n]|\$)") { location ->
            results.add(ExtractedFact("Lives in ${location.trim()}", "identity", 0.85f))
        }

        // 3. Occupation
        matchSingleGroup(text, "(?i)\\b(?:i work as|i am a|i'm a|my job is)\\s+([A-Za-z0-9\\s]+?)(?:[.!\\n]|\$)") { job ->
            results.add(ExtractedFact("Works as ${job.trim()}", "identity", 0.85f))
        }

        // 4. Preferences & Likes
        matchSingleGroup(text, "(?i)\\b(?:i love|i really like|i prefer)\\s+([^.,!\\n]+)") { preference ->
            val clean = preference.trim()
            if (clean.length in 3..80) {
                results.add(ExtractedFact("Likes $clean", "preference", 0.80f))
            }
        }

        // 5. Favorites
        matchTwoGroups(text, "(?i)\\bmy favorite\\s+([a-z]+)\\s+is\\s+([^.,!\\n]+)") { itemType, favValue ->
            results.add(ExtractedFact("Favorite $itemType is ${favValue.trim()}", "preference", 0.85f))
        }

        // 6. Allergies & Health Constraints
        matchSingleGroup(text, "(?i)\\b(?:i am allergic to|i'm allergic to|allergic to)\\s+([^.,!\\n]+)") { allergy ->
            results.add(ExtractedFact("Allergic to ${allergy.trim()}", "constraint", 0.95f))
        }

        // 7. Dietary Constraints
        matchSingleGroup(text, "(?i)\\b(?:i cannot eat|i can't eat|i don't eat)\\s+([^.,!\\n]+)") { food ->
            results.add(ExtractedFact("Does not eat ${food.trim()}", "constraint", 0.85f))
        }

        // 8. Goals & Learning
        matchSingleGroup(text, "(?i)\\b(?:my goal is|i want to|i am learning|i'm learning to)\\s+([^.,!\\n]+)") { goal ->
            val clean = goal.trim()
            if (clean.length in 3..80) {
                results.add(ExtractedFact("Goal: $clean", "goal", 0.80f))
            }
        }

        return results.take(MAX_PER_TURN)
    }

    private inline fun matchSingleGroup(text: String, regex: String, onMatch: (String) -> Unit) {
        val matcher = Pattern.compile(regex).matcher(text)
        if (matcher.find()) {
            val group = matcher.group(1)
            if (!group.isNullOrBlank()) {
                onMatch(group)
            }
        }
    }

    private inline fun matchTwoGroups(text: String, regex: String, onMatch: (String, String) -> Unit) {
        val matcher = Pattern.compile(regex).matcher(text)
        if (matcher.find() && matcher.groupCount() >= 2) {
            val g1 = matcher.group(1).orEmpty()
            val g2 = matcher.group(2).orEmpty()
            if (g1.isNotBlank() && g2.isNotBlank()) {
                onMatch(g1, g2)
            }
        }
    }

    /** Lenient JSON parser for Llama server responses. */
    private fun parse(raw: String): List<ExtractedFact> {
        val json = raw.substringAfter("```json", raw)
            .substringBefore("```")
            .trim()
            .ifEmpty { return emptyList() }

        val start = json.indexOf('{')
        val end = json.lastIndexOf('}')
        if (start == -1 || end <= start) return emptyList()

        val facts = JSONObject(json.substring(start, end + 1)).optJSONArray("facts")
            ?: return emptyList()

        return (0 until facts.length()).mapNotNull { index ->
            val item = facts.optJSONObject(index) ?: return@mapNotNull null
            val text = item.optString("text").trim()
            if (text.isEmpty() || text.length > 140) return@mapNotNull null

            ExtractedFact(
                text = text,
                category = item.optString("category", "other").trim().lowercase(),
                confidence = item.optDouble("confidence", 0.0).toFloat().coerceIn(0f, 1f)
            )
        }.take(MAX_PER_TURN)
    }

    private companion object {
        const val TAG = "MemoryExtractor"
        const val MAX_PER_TURN = 4

        val setofCommonStopwords = setOf(
            "fine", "good", "okay", "ok", "great", "sad", "happy", "tired", "busy",
            "bored", "here", "there", "doing", "going", "well", "hungry", "ready"
        )
    }
}
