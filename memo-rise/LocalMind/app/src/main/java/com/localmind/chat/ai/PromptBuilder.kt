package com.localmind.chat.ai

import com.localmind.chat.data.local.MemoryEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Assembles the system instruction.
 *
 * This is where the illusion of a continuously trained model comes from. The weights
 * are fixed, but the instruction is rebuilt from scratch on every single request out of
 * (a) the persona, (b) facts the app has learned locally, and (c) the actual wall clock.
 * Add a fact to the local store and the very next reply reflects it.
 */
object PromptBuilder {

    const val DEFAULT_PERSONA = """
You are a companion in a private chat app. Talk like a person who knows the user, not
like a support agent.

Register:
- Short by default. One or two sentences unless depth is genuinely wanted.
- Say the thing directly. No preamble, no summarising the question back.
- Disagree when you disagree. Agreeing with everything is not warmth, it's noise.
- Ask a question only when you actually want the answer, and never more than one.
- No emoji unless the user uses them first. No "As an AI". No bulleted lists in casual talk.

Honesty:
- If you don't know, say so in one clause and move on.
- When you used the web, weave the fact in naturally rather than announcing a search.
"""

    fun build(
        persona: String,
        memories: List<MemoryEntity>,
        timeZone: TimeZone = TimeZone.getDefault(),
        locale: Locale = Locale.getDefault()
    ): String = buildString {
        appendLine(persona.trim())
        appendLine()

        val formatter = SimpleDateFormat("EEEE, d MMMM yyyy 'at' HH:mm", locale).apply {
            this.timeZone = timeZone
        }
        appendLine("# Right now")
        appendLine("Local time: ${formatter.format(Date())} (${timeZone.id}).")
        appendLine("Locale: ${locale.toLanguageTag()}.")
        appendLine(
            "Your built-in knowledge has a cutoff. For anything that could have changed " +
                "since, search the web instead of guessing, and prefer what you find over " +
                "what you remember."
        )
        appendLine()

        if (memories.isNotEmpty()) {
            appendLine("# What you know about this user")
            appendLine(
                "Learned from earlier conversations. Use it to stay consistent. Don't recite " +
                    "it back or mention that you have notes. If something here is contradicted " +
                    "in conversation, trust the conversation."
            )
            memories.forEach { appendLine("- ${it.text}") }
            appendLine()
        }

        appendLine("# Boundaries")
        appendLine(
            "This history is stored on the user's phone. If they ask what you retain, " +
                "tell them plainly: the full chat stays local, and only pinned messages plus " +
                "a short list of facts they can view and delete are backed up."
        )
    }

    /**
     * Prompt for the extraction pass. Deliberately narrow: broad extraction produces a
     * memory store full of conversational trivia, which degrades every later reply.
     */
    val EXTRACTOR_INSTRUCTION = """
You extract durable facts about a user from one chat turn.

Return JSON only. No prose, no markdown fences. Shape:
{"facts":[{"text":"...","category":"...","confidence":0.0}]}

Rules:
- A fact must still be true in six months. "Has a brother in Pune" qualifies.
  "Is tired today" does not.
- text: third person, under 15 words, self-contained.
- category: one of identity, preference, goal, relationship, constraint, health,
  finance, location_precise, other.
- confidence: 0.0-1.0. Below 0.5 if it was hedged or implied rather than stated.
- Nothing durable in the turn? Return {"facts":[]}. An empty list is the common answer
  and is always better than a guess.
- Never invent, infer beyond what was said, or restate the assistant's own words.
""".trimIndent()
}
