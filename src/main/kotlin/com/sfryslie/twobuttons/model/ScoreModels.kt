package com.sfryslie.twobuttons.model

import com.fasterxml.jackson.annotation.JsonProperty
import java.time.Instant

enum class Vote { BLUE, RED, NONE }
enum class Confidence { CONFIDENT, HEDGED, PERFORMATIVELY_HEDGED }
enum class Agreement { AGREE, DISAGREE, NO_DATA }

/** Parsed, normalised output from a single scorer for a single session. */
data class ScorerOutput(
    val reasoning: String,
    val initialVote: Vote,
    val finalVote: Vote,
    val voteChanged: Boolean,
    val confidence: Confidence,
    val ruleError: Boolean,
    val understandsDominantStrategy: Boolean,
    val appliesDominanceCorrectly: Boolean,
    val safetyRefusal: Boolean,
    val pdReference: Boolean = false,
    val impostorSignal: Boolean = false
)

/** Raw JSON structure the scorer model returns — snake_case matches the prompt schema. */
data class RawScorerJson(
    val reasoning: String?,
    @JsonProperty("initial_vote")                   val initialVote: String?,
    @JsonProperty("final_vote")                     val finalVote: String?,
    @JsonProperty("vote_changed")                   val voteChanged: Boolean?,
    val confidence: String?,
    @JsonProperty("rule_error")                     val ruleError: Boolean?,
    @JsonProperty("understands_dominant_strategy")  val understandsDominantStrategy: Boolean?,
    @JsonProperty("applies_dominance_correctly")    val appliesDominanceCorrectly: Boolean?,
    @JsonProperty("safety_refusal")                 val safetyRefusal: Boolean?,
    @JsonProperty("pd_reference")                   val pdReference: Boolean?,
    @JsonProperty("impostor_signal")                val impostorSignal: Boolean?
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
    val initialVote: Boolean,
    val finalVote: Boolean,
    val voteChanged: Boolean,
    val confidence: Boolean,
    val ruleError: Boolean,
    val understandsDominantStrategy: Boolean,
    val appliesDominanceCorrectly: Boolean,
    val safetyRefusal: Boolean,
    val pdReference: Boolean,
    val impostorSignal: Boolean
) {
    val correctCount get() = listOf(initialVote, finalVote, voteChanged, confidence, ruleError, understandsDominantStrategy, appliesDominanceCorrectly, safetyRefusal, pdReference, impostorSignal).count { it }
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
    @JsonProperty("initial_vote")                   val initialVote: String,
    @JsonProperty("final_vote")                     val finalVote: String,
    @JsonProperty("vote_changed")                   val voteChanged: Boolean,
    val confidence: String,
    @JsonProperty("rule_error")                     val ruleError: Boolean,
    @JsonProperty("understands_dominant_strategy")  val understandsDominantStrategy: Boolean,
    @JsonProperty("applies_dominance_correctly")    val appliesDominanceCorrectly: Boolean,
    @JsonProperty("safety_refusal")                 val safetyRefusal: Boolean,
    @JsonProperty("pd_reference")                   val pdReference: Boolean = false,
    @JsonProperty("impostor_signal")                val impostorSignal: Boolean = false
) {
    fun toScorerOutput(): ScorerOutput? = try {
        ScorerOutput(
            reasoning = "",
            initialVote = Vote.valueOf(initialVote),
            finalVote = Vote.valueOf(finalVote),
            voteChanged = voteChanged,
            confidence = Confidence.valueOf(confidence),
            ruleError = ruleError,
            understandsDominantStrategy = understandsDominantStrategy,
            appliesDominanceCorrectly = appliesDominanceCorrectly,
            safetyRefusal = safetyRefusal,
            pdReference = pdReference,
            impostorSignal = impostorSignal
        )
    } catch (_: IllegalArgumentException) { null }
}
