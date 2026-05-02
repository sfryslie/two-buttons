package com.sfryslie.twobuttons.model

import java.time.Instant

data class QuestionResponse(
    val questionIndex: Int,
    val question: String,
    val response: String,
    val durationMs: Long
)

data class SessionResult(
    val provider: String,
    val modelId: String,
    val language: String,
    val locale: String,
    val startedAt: Instant,
    val completedAt: Instant,
    val responses: List<QuestionResponse>,
    val error: String? = null
)

data class ExperimentResult(
    val experimentId: String,
    val modelLabel: String,
    val startedAt: Instant,
    val completedAt: Instant,
    // prompts[locale][questionIndex] = exact text sent to the model
    val prompts: Map<String, Map<String, String>>,
    val sessions: List<SessionResult>
)

/** Written as the actual file on disk — one file per locale per model run. */
data class SessionOutput(
    val modelLabel: String,
    // prompts[questionIndex] = exact localised text sent for this session
    val prompts: Map<String, String>,
    val session: SessionResult
)
