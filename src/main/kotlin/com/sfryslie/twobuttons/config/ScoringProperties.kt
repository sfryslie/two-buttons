package com.sfryslie.twobuttons.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "scoring")
data class ScoringProperties(
    val enabled: Boolean = false,
    /** Run calibration set against active scorers instead of scoring real data. */
    val calibrate: Boolean = false,
    val calibrationRuns: Int = 3,
    val inputDir: String = "reruns",
    val outputDir: String = "scores",
    /** BCP-47 tags to score. Empty = all locales found in inputDir. */
    val targetLocales: List<String> = emptyList(),
    /** Model labels to score. Empty = all models found for each locale. */
    val targetModels: List<String> = emptyList(),
    /** Re-score files even if a .score.json already exists. */
    val force: Boolean = false,
    val openai: ScorerConfig = ScorerConfig(model = "gpt-5.4-nano"),
    val gemini: ScorerConfig = ScorerConfig(model = "gemini-3.1-flash-lite"),
    val ollama: ScorerConfig = ScorerConfig(model = "qwen2.5")
) {
    data class ScorerConfig(
        val enabled: Boolean = true,
        val model: String = ""
    )

    fun enabledScorers(): List<Pair<String, ScorerConfig>> = buildList {
        if (openai.enabled && openai.model.isNotBlank()) add("openai" to openai)
        if (gemini.enabled && gemini.model.isNotBlank()) add("gemini" to gemini)
        if (ollama.enabled && ollama.model.isNotBlank()) add("ollama" to ollama)
    }
}
