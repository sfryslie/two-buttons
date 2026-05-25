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
import com.sfryslie.twobuttons.service.ReportGeneratorService
import com.sfryslie.twobuttons.service.ScoreWriterService
import com.sfryslie.twobuttons.service.ScoringService
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import java.io.FileInputStream
import java.io.InputStreamReader
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

// Runs after ExperimentRunner (Order 2). Two modes:
//   calibrate=true  -> delegate to CalibrationService and exit
//   calibrate=false -> walk reruns/{lang}/{model}/ and score each session file
//
// Enable:           --scoring.enabled=true
// Skip experiment:  --experiment.enabled-providers= (empty list)
@Component
@Order(2)
class ScoringRunner(
    private val properties: ScoringProperties,
    private val scoringService: ScoringService,
    private val scoreWriterService: ScoreWriterService,
    private val calibrationService: CalibrationService,
    private val reportGeneratorService: ReportGeneratorService
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(javaClass)

    private val objectMapper: ObjectMapper = ObjectMapper()
        .registerKotlinModule()
        .registerModule(JavaTimeModule())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

    override fun run(args: ApplicationArguments) {
        if (!properties.enabled) {
            log.debug("Scoring disabled. Enable with --scoring.enabled=true")
            return
        }

        if (properties.calibrate) {
            calibrationService.runCalibration()
            return
        }

        val sessionFiles = findSessionFiles()
        if (sessionFiles.isNotEmpty()) {
            val enabledScorers = properties.enabledScorers().map { it.first }
            log.info("Scoring ${sessionFiles.size} session(s) with scorer(s): $enabledScorers")

            val scored  = AtomicInteger(0)
            val skipped = AtomicInteger(0)
            val pool    = Executors.newFixedThreadPool(8)

            for ((file, lang, model) in sessionFiles) {
                val filename = file.fileName.toString()

                if (!properties.force && scoreWriterService.scoreExists(lang, model, filename)) {
                    log.debug("[$lang/$model/$filename] Already scored. Use --scoring.force=true to override.")
                    skipped.incrementAndGet()
                    continue
                }

                pool.submit {
                    try {
                        // Read with a lenient decoder so a single bad byte (e.g. truncated
                        // multi-byte CJK sequence from an Ollama response) becomes U+FFFD
                        // rather than aborting the whole file with JsonParseException.
                        val lenient = Charsets.UTF_8.newDecoder()
                            .onMalformedInput(CodingErrorAction.REPLACE)
                            .onUnmappableCharacter(CodingErrorAction.REPLACE)
                        val text = InputStreamReader(FileInputStream(file.toFile()), lenient).readText()
                        val session = objectMapper.readValue(text, SessionOutput::class.java)
                        val scores = scoringService.scoreSession(session)

                        val nonNullVotes = scores.values.filterNotNull().map { it.initialVote }
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
                        scored.incrementAndGet()
                        log.info("[$lang/$model/$filename] vote=$majorityVote agreement=$agreement")
                    } catch (e: Exception) {
                        log.error("[$lang/$model/$filename] Failed: ${e.message}", e)
                    }
                }
            }

            pool.shutdown()
            pool.awaitTermination(6, TimeUnit.HOURS)
            log.info("Scoring complete: scored=${scored.get()} skipped=${skipped.get()}")
        } else {
            log.info(
                "No session files found in '${properties.inputDir}' " +
                "(targetLocales=${properties.targetLocales}, targetModels=${properties.targetModels}) — generating report only"
            )
        }

        reportGeneratorService.generateReport()
    }

    // Walks reruns/{lang}/{model}/ and returns (file, lang, model) triples.
    // Filters by targetLocales and targetModels when non-empty.
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
