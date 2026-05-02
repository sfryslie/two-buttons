package com.sfryslie.twobuttons.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "experiment")
data class ExperimentProperties(
    val outputDir: String = "results",
    val enabledProviders: List<String> = listOf("anthropic"),
    val modelLabel: String = "unknown",
    val enabledLanguages: List<String> = listOf("en"),
    val dryRun: Boolean = false
)
