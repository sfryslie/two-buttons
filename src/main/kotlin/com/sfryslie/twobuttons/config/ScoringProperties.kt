package com.sfryslie.twobuttons.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "scoring")
data class ScoringProperties(
    val enabled: Boolean = false,
    val scorerModel: String = "claude-opus-4-7",
    val scorerProvider: String = "anthropic",
    val inputDir: String = "results",
    val outputDir: String = "scores",
    // Model labels to score — if empty, scores all models found in inputDir
    val targetModels: List<String> = emptyList(),
    // Locales to score — defaults to English only to keep costs down
    val targetLocales: List<String> = listOf("en")
)
