package com.sfryslie.twobuttons.runner

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.sfryslie.twobuttons.config.ScoringProperties
import com.sfryslie.twobuttons.model.SessionOutput
import com.sfryslie.twobuttons.service.ScoreWriterService
import com.sfryslie.twobuttons.service.ScoringService
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Runs after ExperimentRunner (Order 2). Walks results/ to find session files
 * matching the configured target models and locales, scores each one, and writes
 * to scores/.
 *
 * Enable with: --scoring.enabled=true
 * Disable experiment with: --experiment.enabled-providers= (empty list)
 */
@Component
@Order(2)
class ScoringRunner(
    private val properties: ScoringProperties,
    private val scoringService: ScoringService,
    private val scoreWriterService: ScoreWriterService
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(javaClass)

    private val objectMapper: ObjectMapper = ObjectMapper()
        .registerKotlinModule()
        .registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

    override fun run(args: ApplicationArguments) {
        if (!properties.enabled) {
            log.debug("Scoring disabled — skipping. Enable with --scoring.enabled=true")
            return
        }

        val sessionFiles = findSessionFiles()
        if (sessionFiles.isEmpty()) {
            log.warn("No session files found matching targetModels=${properties.targetModels} targetLocales=${properties.targetLocales}")
            return
        }

        log.info("Scoring ${sessionFiles.size} session(s) with ${properties.scorerModel}")

        for (file in sessionFiles) {
            try {
                val session = objectMapper.readValue(file.toFile(), SessionOutput::class.java)
                val score = scoringService.score(file, session)
                scoreWriterService.write(score)
            } catch (e: Exception) {
                log.error("Failed to score ${file.fileName}: ${e.message}", e)
            }
        }

        log.info("Scoring complete.")
    }

    /**
     * Walks results/{modelLabel}/ and returns all session files matching the
     * configured target models and locales.
     *
     * Filename pattern: two-buttons-{locale}-{modelLabel}-{timestamp}.json
     *
     * If targetModels is empty, all model subdirectories are included.
     */
    private fun findSessionFiles(): List<Path> {
        val inputDir = Paths.get(properties.inputDir)
        if (!Files.exists(inputDir)) {
            log.warn("Input directory $inputDir does not exist")
            return emptyList()
        }

        val modelDirs = Files.list(inputDir)
            .filter { Files.isDirectory(it) }
            .filter { dir ->
                properties.targetModels.isEmpty() || dir.fileName.toString() in properties.targetModels
            }
            .toList()

        return modelDirs.flatMap { modelDir ->
            Files.list(modelDir)
                .filter { it.fileName.toString().endsWith(".json") }
                .filter { file ->
                    // Skip already-scored files (shouldn't be in results/ but just in case)
                    !file.fileName.toString().endsWith("-scored.json")
                }
                .filter { file ->
                    // Match locale: filename starts with two-buttons-{locale}-
                    properties.targetLocales.isEmpty() || properties.targetLocales.any { locale ->
                        file.fileName.toString().startsWith("two-buttons-${locale.replace("-", "_")}-") ||
                        file.fileName.toString().startsWith("two-buttons-$locale-")
                    }
                }
                .toList()
        }.sortedBy { it.fileName.toString() }
    }
}
