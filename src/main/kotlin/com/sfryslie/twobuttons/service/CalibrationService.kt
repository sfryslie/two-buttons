package com.sfryslie.twobuttons.service

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.sfryslie.twobuttons.config.ScoringProperties
import com.sfryslie.twobuttons.model.CalibrationSet
import com.sfryslie.twobuttons.model.ScorerOutput
import org.slf4j.LoggerFactory
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Service

@Service
class CalibrationService(
    private val properties: ScoringProperties,
    private val scoringService: ScoringService
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val objectMapper = ObjectMapper()
        .registerKotlinModule()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    private val calibrationSet: CalibrationSet by lazy {
        val json = ClassPathResource("scoring/calibration-set.json").inputStream.bufferedReader().readText()
        objectMapper.readValue(json, CalibrationSet::class.java)
    }

    fun runCalibration() {
        val cases = calibrationSet.cases
        val n = properties.calibrationRuns
        val enabledScorers = properties.enabledScorers().map { it.first }

        log.info("=== CALIBRATION START: ${cases.size} cases × $n run(s) × ${enabledScorers.size} scorer(s) ===")

        // scorer → caseId → list of run outputs
        val allRuns: Map<String, Map<String, List<ScorerOutput?>>> = enabledScorers.associateWith { scorer ->
            cases.associate { case ->
                case.id to (1..n).map {
                    scoringService.scoreTexts(
                        q1 = case.q1,
                        q2 = case.q2 ?: "",
                        q4 = case.q4 ?: ""
                    )[scorer]
                }
            }
        }

        var totalCorrect = 0
        var totalChecked = 0

        for (scorer in enabledScorers) {
            log.info("--- Scorer: $scorer ---")
            val scorerRuns = allRuns[scorer]!!

            for (case in cases) {
                val runs = scorerRuns[case.id]!!
                val expected = case.expected.toScorerOutput()

                if (expected == null) {
                    log.warn("[${case.id}] Unparseable expected value — skipping")
                    continue
                }

                val nonNullRuns = runs.filterNotNull()
                if (nonNullRuns.isEmpty()) {
                    log.warn("[${case.id}] All $n run(s) returned null — scorer unavailable or failed entirely")
                    continue
                }

                val first = nonNullRuns.first()
                val voteOk  = first.vote              == expected.vote
                val confOk  = first.confidence        == expected.confidence
                val ruleOk  = first.ruleError         == expected.ruleError
                val gtOk    = first.engagesGameTheory == expected.engagesGameTheory
                val q4Ok    = first.recantsBy_q4      == expected.recantsBy_q4
                val safeOk  = first.safetyRefusal     == expected.safetyRefusal

                totalCorrect += listOf(voteOk, confOk, ruleOk, gtOk, q4Ok, safeOk).count { it }
                totalChecked += 6

                val fieldStr = buildString {
                    fun mark(ok: Boolean) = if (ok) "✓" else "✗"
                    append("vote=${first.vote}${mark(voteOk)} ")
                    if (!voteOk)  append("(exp=${expected.vote}) ")
                    append("conf=${first.confidence}${mark(confOk)} ")
                    if (!confOk)  append("(exp=${expected.confidence}) ")
                    append("rule=${first.ruleError}${mark(ruleOk)} ")
                    if (!ruleOk)  append("(exp=${expected.ruleError}) ")
                    append("gt=${first.engagesGameTheory}${mark(gtOk)} ")
                    if (!gtOk)    append("(exp=${expected.engagesGameTheory}) ")
                    append("q4=${first.recantsBy_q4}${mark(q4Ok)} ")
                    if (!q4Ok)    append("(exp=${expected.recantsBy_q4}) ")
                    append("safe=${first.safetyRefusal}${mark(safeOk)}")
                    if (!safeOk)  append(" (exp=${expected.safetyRefusal})")
                }

                val deterStr = when {
                    nonNullRuns.size < 2 -> ""
                    nonNullRuns.all { it == first } -> " [det:✓]"
                    else -> " [det:✗ non-deterministic across $n runs]"
                }

                log.info("[${case.id}] $fieldStr$deterStr")
            }
        }

        if (totalChecked > 0) {
            val pct = totalCorrect * 100 / totalChecked
            log.info("=== CALIBRATION COMPLETE: $totalCorrect/$totalChecked fields correct ($pct%) ===")
        } else {
            log.warn("=== CALIBRATION COMPLETE: no fields checked (no scorers available?) ===")
        }
    }
}
