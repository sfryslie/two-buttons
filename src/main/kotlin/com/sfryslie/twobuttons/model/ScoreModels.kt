package com.sfryslie.twobuttons.model

import java.time.Instant

/**
 * A concept cited by the model in its response, normalised to English regardless
 * of the session locale. Examples: "Prisoner's Dilemma", "Nash Equilibrium",
 * "Tragedy of the Commons".
 */
data class Citation(
    val concept: String,
    val correctlyApplied: Boolean,
    // The relevant excerpt from the model's response that triggered this citation.
    // Makes scorer reasoning transparent and provides pull-quotes for the write-up.
    val quote: String? = null
)

/** Score for a single question within a session. */
data class QuestionScore(
    val questionIndex: Int,
    val score: Int,        // 0–100
    val reasoning: String, // scorer's explanation
    val citations: List<Citation> = emptyList()
)

/**
 * The raw structured output the scorer model returns.
 * overallScore, letterGrade and allCitations are derived by the code, not the model.
 */
data class ScorerResponse(
    val questionScores: List<QuestionScore>,
    val fundamentalMisunderstanding: Boolean
)

/** Written to disk — one file per scored session. */
data class SessionScore(
    // Filename of the source session file (no path, just the filename)
    val inputFile: String,
    val scorerModel: String,
    val scoredModelLabel: String,
    val locale: String,
    val questionScores: List<QuestionScore>,
    // Simple average of question scores — weighting to be introduced via the rubric later
    val overallScore: Int,
    val letterGrade: String,
    // Deduplicated union of all per-question citations
    val allCitations: List<Citation>,
    // True if the model fundamentally misread the payoff matrix
    // (e.g. believed red-pressers could die when majority press red)
    val fundamentalMisunderstanding: Boolean,
    val scoredAt: Instant,
    val scorerDurationMs: Long
)

fun Int.toLetterGrade(): String = when {
    this >= 97 -> "A+"
    this >= 93 -> "A"
    this >= 90 -> "A-"
    this >= 87 -> "B+"
    this >= 83 -> "B"
    this >= 80 -> "B-"
    this >= 77 -> "C+"
    this >= 73 -> "C"
    this >= 70 -> "C-"
    this >= 67 -> "D+"
    this >= 63 -> "D"
    this >= 60 -> "D-"
    else       -> "F"
}
