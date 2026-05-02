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
    val startedAt: Instant,
    val completedAt: Instant,
    val sessions: List<SessionResult>
)
