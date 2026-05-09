package com.sfryslie.twobuttons.runner

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.sfryslie.twobuttons.config.ScoringProperties
import com.sfryslie.twobuttons.model.Agreement
import com.sfryslie.twobuttons.model.SessionOutput
import com.sfryslie.twobuttons.model.Vote
import com.sfryslie.twobuttons.service.CalibrationService
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
 * Runs after ExperimentRunner (Order 2). Two modes:
 *
 *   calibrate=true  → run calibration-set.json against active scorers and exit
 *   calibrate=false → walk reruns/{lang}/{model}/*.json, score each one, write to scores/{lang}/{model}/
 *
 * Enable: --scoring.enabled=true
 * Disable experiment: --experiment.enabled-providers= (empty list)
 */
@Component
@Order(2)
class ScoringRunner(
    private val properties: ScoringProperties,
    private val scoringService: ScoringService,
    private val scoreWriterService: ScoreWriterService,
    private val calibrationService: CalibrationService
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(javaClass)

    private val objectMapper: ObjectMapper = ObjectMapper()
        .registerKotlinModule()
        .registerModule(JavaTimeModule())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

    override fun run(args: ApplicationArguments) {
        if (!properties.enabled) {
            log.debug("Scoring disabled — skipping. Enable with --scoring.enabled=true")
            return
        }

        if (properties.calibrate) {
            calibrationService.runCalibration()
            return
        }

        val sessionFiles = findSessionFiles()
        if (sessionFiles.isEmpty()) {
            log.warn(
                "No session files found in '${properties.inputDir}' " +
                "(targetLocales=${properties.targetLocales}, targetModels=${properties.targetModels})"
            )
            return
        }

        val enabledScorers = properties.enabledScorers().map { it.first }
        log.info("Scoring ${sessionFiles.size} session(s) with scorer(s): $enabledScorers")

        var scored = 0
        var skipped = 0

        for ((file, lang, model) in sessionFiles) {
            val filename = file.fileName.toString()

            if (!properties.force && scoreWriterService.scoreExists(lang, model, filename)) {
                log.debug("[$lang/$model/$filename] Already scored — skipping (use --scoring.force=true to override)")
                skipped++
                continue
            }

            try {
                val session = objectMapper.readValue(file.toFile(), SessionOutput::class.java)
                val scores = scoringService.scoreSession(session)

                val nonNullVotes = scores.values.filterNotNull().map { it.vote }
                val agreement = when {
                    nonNullVotes.size < 2 -> Agreement.NO_DATA
                    nonNullVotes.toSet().size == 1 -> Agreement.AGREE
                    else -> Agreement.DISAGREE
                }
                val majorityVote = nonNullVotes
                    .groupingBy { it }
                    .eachCount()
                    .maxByOrNull { it.value }
                    ?.key
                    ?: Vote.NONE

                scoreWriterService.write(
                    lang         = lang,
                    model        = model,
                    filename     = filename,
                    session      = session,
                    scores       = scores,
                    agreement    = agreement,
                    majorityVote = majorityVote
                )
                scored++
                log.info("[$lang/$model/$filename] vote=$majorityVote agreement=$agreement")
            } catch (e: Exception) {
                log.error("[$lang/$model/$filename] Failed: ${e.message}", e)
            }
        }

        log.info("Scoring complete — scored=$scored skipped=$skipped")
    }

    /**
     * Walks reruns/{lang}/{model}/*.json and returns (file, lang, model) triples.
     * Filters by targetLocales and targetModels when non-empty.
     */
    private fun findSessionFiles(): List<Triple<Path, String, String>> {
        val inputDir = Paths.get(properties.inputDir)
        if (!Files.exists(inputDir)) {
            log.warn("Input directory '$inputDir' does not exist")
            return emptyList()
        }

        val results = mutableListOf<Triple<Path, String, String>>()

        Files.list(inputDir)
            .filter { Files.isDirectory(it) }
            .filter { langDir ->
                val lang = langDir.fileName.toString()
                properties.targetLocales.isEmpty() || lang in properties.targetLocales
            }
            .sorted()
            .forEach { langDir ->
                val lang = langDir.fileName.toString()
                Files.list(langDir)
                    .filter { Files.isDirectory(it) }
                    .filter { modelDir ->
                        val model = modelDir.fileName.toString()
                        properties.targetModels.isEmpty() || model in properties.targetModels
                    }
                    .sorted()
                    .forEach { modelDir ->
                        val model = modelDir.fileName.toString()
                        Files.list(modelDir)
                            .filter { it.fileName.toString().endsWith(".json") }
                            .sorted()
                            .forEach { file -> results.add(Triple(file, lang, model)) }
                    }
            }

        return results
    }
}
