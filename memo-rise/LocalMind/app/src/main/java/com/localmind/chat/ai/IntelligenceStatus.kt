package com.localmind.chat.ai

/**
 * Modes for the Chat AI Status:
 *
 * AI_MODE (Green): "AI Mode" (On-device Llama AI model response).
 * MEMORY_MODE (Cyan/Mint): "Memory Mode" (Retrieved from local Room memory database).
 * SEARCH_MODE (White): "Search Mode" (Curated from open web search).
 * LOADING (Yellow): "Initializing..." (Setting up modes).
 */
enum class IntelligenceStatus(val label: String, val description: String) {
    AI_MODE("AI Mode", "On-device Llama AI model active"),
    MEMORY_MODE("Memory Mode", "Local memory recall active"),
    SEARCH_MODE("Search Mode", "Open web search active"),
    LOADING("Initializing...", "Setting up AI modes")
}
