package com.sfryslie.twobuttons.model

import com.fasterxml.jackson.annotation.JsonProperty
import java.time.Instant

enum class Vote { BLUE, RED, NONE }
enum class Confidence { CONFIDENT, HEDGED, PERFORMATIVELY_HEDGED }
enum class Agreement { AGREE, DISAGREE, NO_DATA }

/** Parsed, normalised output from a single scorer for a single session. */
data class ScorerOutput(
    val vote: Vote,
    val confidence: Confidence,
    val ruleError: Boolean,
    val engagesGameTheory: Boolean,
    val recantsBy_q4: Boolean,
    val safetyRefusal: Boolean
)

/** Raw JSON structure the scorer model returns — snake_case matches the prompt schema. */
data class RawScorerJson(
    val vote: String?,
    val confidence: String?,
    @JsonProperty("rule_error")          val ruleError: Boolean?,
    @JsonProperty("engages_game_theory") val engagesGameTheory: Boolean?,
    @JsonProperty("recants_by_q4")       val recantsBy_q4: Boolean?,
    @JsonProperty("safety_refusal")      val safetyRefusal: Boolean?
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
    val q4: String? = null
)

data class CalibrationExpected(
    val vote: String,
    val confidence: String,
    @JsonProperty("rule_error")          val ruleError: Boolean,
    @JsonProperty("engages_game_theory") val engagesGameTheory: Boolean,
    @JsonProperty("recants_by_q4")       val recantsBy_q4: Boolean,
    @JsonProperty("safety_refusal")      val safetyRefusal: Boolean
) {
    fun toScorerOutput(): ScorerOutput? = try {
        ScorerOutput(
            vote = Vote.valueOf(vote),
            confidence = Confidence.valueOf(confidence),
            ruleError = ruleError,
            engagesGameTheory = engagesGameTheory,
            recantsBy_q4 = recantsBy_q4,
            safetyRefusal = safetyRefusal
        )
    } catch (_: IllegalArgumentException) { null }
}
