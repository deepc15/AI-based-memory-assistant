package com.localmind.chat.ai

import android.util.Log

/**
 * Compares multi-model candidate responses and synthesizes the smartest answer possible.
 */
class SmartestAnswerSynthesizer {

    data class EvaluatedCandidate(
        val candidate: ModelCandidate,
        val memoryScore: Float,
        val coherenceScore: Float,
        val relevanceScore: Float,
        val totalScore: Float
    )

    /**
     * Evaluates multiple local model candidates and returns the highest-scoring smartest response.
     */
    fun selectAndSynthesizeSmartest(
        candidates: List<ModelCandidate>,
        userQuery: String,
        memoryContext: String
    ): String {
        if (candidates.isEmpty()) return ""
        if (candidates.size == 1) return candidates.first().text

        val evaluated = candidates.map { candidate ->
            evaluateCandidate(candidate, userQuery, memoryContext)
        }.sortedByDescending { it.totalScore }

        val winner = evaluated.first()
        Log.i(
            TAG,
            "Smartest candidate selected: '${winner.candidate.modelName}' (Score: ${"%.2f".format(winner.totalScore)})"
        )

        return winner.candidate.text
    }

    private fun evaluateCandidate(
        candidate: ModelCandidate,
        userQuery: String,
        memoryContext: String
    ): EvaluatedCandidate {
        val text = candidate.text
        if (text.isBlank()) {
            return EvaluatedCandidate(candidate, 0f, 0f, 0f, 0f)
        }

        // 1. Memory Alignment Score (0.0 to 1.0)
        var memoryScore = 0.5f
        if (memoryContext.isNotBlank()) {
            val facts = memoryContext.split("\n").filter { it.isNotBlank() }
            var matches = 0
            for (fact in facts) {
                val keyword = fact.substringAfter("is ").substringAfter("in ").trim()
                if (keyword.length > 2 && text.contains(keyword, ignoreCase = true)) {
                    matches++
                }
            }
            if (facts.isNotEmpty()) {
                memoryScore += (matches.toFloat() / facts.size.toFloat()) * 0.5f
            }
        }

        // 2. Coherence & Formatting Score (0.0 to 1.0)
        var coherenceScore = 0.7f
        if (text.length in 20..1000) coherenceScore += 0.15f
        if (text.endsWith(".") || text.endsWith("!") || text.endsWith("?")) coherenceScore += 0.15f
        if (text.contains("Here is an organized answer") || text.contains("I processed your query")) {
            coherenceScore -= 0.2f // Deduct for generic template phrasing
        }

        // 3. Relevance Score (0.0 to 1.0)
        var relevanceScore = 0.5f
        val queryWords = userQuery.lowercase().split("\\s+".toRegex()).filter { it.length > 3 }
        if (queryWords.isNotEmpty()) {
            val matchedWords = queryWords.count { text.lowercase().contains(it) }
            relevanceScore += (matchedWords.toFloat() / queryWords.size.toFloat()) * 0.5f
        }

        var totalScore = (memoryScore * 0.5f) + (coherenceScore * 0.3f) + (relevanceScore * 0.2f)
        if (memoryScore > 0.75f) {
            totalScore += 0.5f // High priority boost for accurate memory recall
        }

        return EvaluatedCandidate(
            candidate = candidate,
            memoryScore = memoryScore,
            coherenceScore = coherenceScore,
            relevanceScore = relevanceScore,
            totalScore = totalScore
        )
    }

    companion object {
        private const val TAG = "SmartestAnswerSynthesizer"
    }
}
