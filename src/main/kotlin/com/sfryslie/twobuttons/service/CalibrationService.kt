package com.sfryslie.twobuttons.service

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.sfryslie.twobuttons.config.ScoringProperties
import com.sfryslie.twobuttons.model.CalibrationCaseResult
import com.sfryslie.twobuttons.model.CalibrationFieldAccuracy
import com.sfryslie.twobuttons.model.CalibrationReport
import com.sfryslie.twobuttons.model.CalibrationSet
import com.sfryslie.twobuttons.model.ScorerOutput
import org.slf4j.LoggerFactory
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@Service
class CalibrationService(
    private val properties: ScoringProperties,
    private val scoringService: ScoringService
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val objectMapper = ObjectMapper()
        .registerKotlinModule()
        .registerModule(JavaTimeModule())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .enable(SerializationFeature.INDENT_OUTPUT)

    private val calibrationSet: CalibrationSet by lazy {
        val json = ClassPathResource("scoring/calibration-set.json").inputStream.bufferedReader().readText()
        objectMapper.readValue(json, CalibrationSet::class.java)
    }

    fun runCalibration() {
        val cases = calibrationSet.cases
        val n = properties.calibrationRuns
        val enabledScorers = properties.enabledScorers().map { it.first }
        val runAt = Instant.now()

        log.info("=== CALIBRATION START: ${cases.size} cases x $n run(s) x ${enabledScorers.size} scorer(s) ===")

        // scorer -> caseId -> list of run outputs
        val allRuns: Map<String, Map<String, List<ScorerOutput?>>> = enabledScorers.associateWith { scorer ->
            cases.associate { case ->
                case.id to (1..n).map {
                    scoringService.scoreTexts(
                        q1 = case.q1,
                        q2 = case.q2 ?: "",
                        q3 = case.q3 ?: "",
                        q4 = case.q4 ?: ""
                    )[scorer]
                }
            }
        }

        var totalCorrect = 0
        var totalChecked = 0
        val caseResults = mutableListOf<CalibrationCaseResult>()

        for (scorer in enabledScorers) {
            log.info("--- Scorer: $scorer ---")
            val scorerRuns = allRuns[scorer]!!

            for (case in cases) {
                val runs = scorerRuns[case.id]!!
                val expected = case.expected.toScorerOutput()
                val nullRuns = runs.count { it == null }
                val nonNullRuns = runs.filterNotNull()

                if (expected == null) {
                    log.warn("[${case.id}] Unparseable expected value - skipping")
                    caseResults.add(CalibrationCaseResult(scorer, case.id, case.description, null, null, null, false, nullRuns))
                    continue
                }

                if (nonNullRuns.isEmpty()) {
                    log.warn("[${case.id}] All $n run(s) returned null - scorer unavailable or failed entirely")
                    caseResults.add(CalibrationCaseResult(scorer, case.id, case.description, expected, null, null, false, nullRuns))
                    continue
                }

                val first = nonNullRuns.first()
                val acc = CalibrationFieldAccuracy(
                    vote                        = first.vote                        == expected.vote,
                    confidence                  = first.confidence                  == expected.confidence,
                    ruleError                   = first.ruleError                   == expected.ruleError,
                    understandsDominantStrategy = first.understandsDominantStrategy == expected.understandsDominantStrategy,
                    appliesDominanceCorrectly   = first.appliesDominanceCorrectly   == expected.appliesDominanceCorrectly,
                    recantsBy_q4                = first.recantsBy_q4                == expected.recantsBy_q4,
                    safetyRefusal               = first.safetyRefusal               == expected.safetyRefusal
                )
                val deterministic = nonNullRuns.size < 2 || nonNullRuns.all { it == first }

                totalCorrect += acc.correctCount
                totalChecked += 7

                fun mark(ok: Boolean) = if (ok) "ok" else "FAIL"
                val fieldStr = buildString {
                    append("vote=${first.vote}(${mark(acc.vote)}) ")
                    append("conf=${first.confidence}(${mark(acc.confidence)}) ")
                    append("rule=${first.ruleError}(${mark(acc.ruleError)}) ")
                    append("uds=${first.understandsDominantStrategy}(${mark(acc.understandsDominantStrategy)}) ")
                    append("adc=${first.appliesDominanceCorrectly}(${mark(acc.appliesDominanceCorrectly)}) ")
                    append("q4=${first.recantsBy_q4}(${mark(acc.recantsBy_q4)}) ")
                    append("safe=${first.safetyRefusal}(${mark(acc.safetyRefusal)})")
                }
                val deterStr = if (deterministic || nonNullRuns.size < 2) "" else " [NON-DETERMINISTIC]"

                log.info("[${case.id}] $fieldStr$deterStr")
                caseResults.add(CalibrationCaseResult(scorer, case.id, case.description, expected, first, acc, deterministic, nullRuns))
            }
        }

        val pct = if (totalChecked > 0) totalCorrect * 100 / totalChecked else 0
        if (totalChecked > 0) {
            log.info("=== CALIBRATION COMPLETE: $totalCorrect/$totalChecked fields correct ($pct%) ===")
        } else {
            log.warn("=== CALIBRATION COMPLETE: no fields checked (no scorers available?) ===")
        }

        writeReport(CalibrationReport(runAt, enabledScorers, n, totalCorrect, totalChecked, pct, caseResults))
    }

    private fun writeReport(report: CalibrationReport) {
        val dir = Paths.get("calibration")
        Files.createDirectories(dir)

        val ts = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss'Z'")
            .withZone(ZoneOffset.UTC)
            .format(report.runAt)
        val file = dir.resolve("calibration-$ts.json")

        objectMapper.writeValue(file.toFile(), report)
        log.info("Calibration report written -> $file")
    }
}
