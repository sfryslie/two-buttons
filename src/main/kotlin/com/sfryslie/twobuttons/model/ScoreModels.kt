package com.sfryslie.twobuttons.model

import com.fasterxml.jackson.annotation.JsonProperty
import java.time.Instant

enum class Vote { BLUE, RED, NONE }
enum class Confidence { CONFIDENT, HEDGED, PERFORMATIVELY_HEDGED }
enum class Agreement { AGREE, DISAGREE, NO_DATA }

/** Parsed, normalised output from a single scorer for a single session. */
data class ScorerOutput(
    val reasoning: String,
    val vote: Vote,
    val confidence: Confidence,
    val ruleError: Boolean,
    val understandsDominantStrategy: Boolean,
    val appliesDominanceCorrectly: Boolean,
    val recantsBy_q4: Boolean,
    val safetyRefusal: Boolean
)

/** Raw JSON structure the scorer model returns — snake_case matches the prompt schema. */
data class RawScorerJson(
    val reasoning: String?,
    val vote: String?,
    val confidence: String?,
    @JsonProperty("rule_error")                    val ruleError: Boolean?,
    @JsonProperty("understands_dominant_strategy") val understandsDominantStrategy: Boolean?,
    @JsonProperty("applies_dominance_correctly")   val appliesDominanceCorrectly: Boolean?,
    @JsonProperty("recants_by_q4")                 val recantsBy_q4: Boolean?,
    @JsonProperty("safety_refusal")                val safetyRefusal: Boolean?
)

/** Written to scores/{lang}/{model}/{filename}.score.json */
data class SessionScore(
    val inputFile: String,
    val modelLabel: String,
    val language: String,
    val scoredAt: Instant,
    val scorers: List<String>,
    /** scorer name → output (null if that scorer failed or was unavailable) */
    val scores: Map<String, ScorerOutput?>,
    val agreement: Agreement,
    val majorityVote: Vote
)

// ---------------------------------------------------------------------------
// Calibration report — written to calibration/{timestamp}.json
// ---------------------------------------------------------------------------

data class CalibrationReport(
    val runAt: Instant,
    val scorers: List<String>,
    val calibrationRuns: Int,
    val totalCorrect: Int,
    val totalChecked: Int,
    val accuracyPct: Int,
    val results: List<CalibrationCaseResult>
)

data class CalibrationCaseResult(
    val scorer: String,
    val caseId: String,
    val description: String,
    val expected: ScorerOutput?,
    val first: ScorerOutput?,
    val fieldAccuracy: CalibrationFieldAccuracy?,
    val deterministic: Boolean,
    val nullRuns: Int
)

data class CalibrationFieldAccuracy(
    val vote: Boolean,
    val confidence: Boolean,
    val ruleError: Boolean,
    val understandsDominantStrategy: Boolean,
    val appliesDominanceCorrectly: Boolean,
    val recantsBy_q4: Boolean,
    val safetyRefusal: Boolean
) {
    val correctCount get() = listOf(vote, confidence, ruleError, understandsDominantStrategy, appliesDominanceCorrectly, recantsBy_q4, safetyRefusal).count { it }
}

// ---------------------------------------------------------------------------
// Calibration data classes — mirrors scoring/calibration-set.json
// ---------------------------------------------------------------------------

data class CalibrationSet(
    val description: String = "",
    val cases: List<CalibrationCase> = emptyList()
)

data class CalibrationCase(
    val id: String,
    val description: String,
    val expected: CalibrationExpected,
    val q1: String,
    val q2: String? = null,
    val q3: String? = null,
    val q4: String? = null
)

data class CalibrationExpected(
    val vote: String,
    val confidence: String,
    @JsonProperty("rule_error")                    val ruleError: Boolean,
    @JsonProperty("understands_dominant_strategy") val understandsDominantStrategy: Boolean,
    @JsonProperty("applies_dominance_correctly")   val appliesDominanceCorrectly: Boolean,
    @JsonProperty("recants_by_q4")                 val recantsBy_q4: Boolean,
    @JsonProperty("safety_refusal")                val safetyRefusal: Boolean
) {
    fun toScorerOutput(): ScorerOutput? = try {
        ScorerOutput(
            reasoning = "",
            vote = Vote.valueOf(vote),
            confidence = Confidence.valueOf(confidence),
            ruleError = ruleError,
            understandsDominantStrategy = understandsDominantStrategy,
            appliesDominanceCorrectly = appliesDominanceCorrectly,
            recantsBy_q4 = recantsBy_q4,
            safetyRefusal = safetyRefusal
        )
    } catch (_: IllegalArgumentException) { null }
}
