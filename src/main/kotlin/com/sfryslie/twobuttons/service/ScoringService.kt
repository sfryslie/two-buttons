package com.sfryslie.twobuttons.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.sfryslie.twobuttons.config.ScoringProperties
import com.sfryslie.twobuttons.model.Citation
import com.sfryslie.twobuttons.model.ScorerResponse
import com.sfryslie.twobuttons.model.SessionOutput
import com.sfryslie.twobuttons.model.SessionScore
import com.sfryslie.twobuttons.model.toLetterGrade
import org.slf4j.LoggerFactory
import org.springframework.ai.anthropic.AnthropicChatModel
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.beans.factory.ObjectProvider
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Service
import java.nio.file.Path
import java.time.Instant

@Service
class ScoringService(
    private val properties: ScoringProperties,
    private val anthropicProvider: ObjectProvider<AnthropicChatModel>
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val objectMapper: ObjectMapper = ObjectMapper()
        .registerKotlinModule()
        .registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

    fun score(inputFile: Path, session: SessionOutput): SessionScore {
        val chatModel = resolveScorerModel()
        val rubric = loadRubric()
        val userPrompt = buildUserPrompt(session)

        log.info("[scorer] Scoring ${inputFile.fileName} with ${properties.scorerModel}")
        val start = Instant.now()

        val rawResponse = ChatClient.create(chatModel)
            .prompt()
            .messages(
                SystemMessage(rubric),
                UserMessage(userPrompt)
            )
            .call()
            .content() ?: error("Scorer returned null response for ${inputFile.fileName}")

        val durationMs = Instant.now().toEpochMilli() - start.toEpochMilli()

        val scorerResponse = parseResponse(rawResponse, inputFile)

        val overallScore = scorerResponse.questionScores
            .map { it.score }
            .average()
            .toInt()

        val allCitations = scorerResponse.questionScores
            .flatMap { it.citations }
            .distinctBy { it.concept.lowercase() }

        return SessionScore(
            inputFile = inputFile.fileName.toString(),
            scorerModel = properties.scorerModel,
            scoredModelLabel = session.modelLabel,
            locale = session.session.locale,
            questionScores = scorerResponse.questionScores,
            overallScore = overallScore,
            letterGrade = overallScore.toLetterGrade(),
            allCitations = allCitations,
            fundamentalMisunderstanding = scorerResponse.fundamentalMisunderstanding,
            scoredAt = start,
            scorerDurationMs = durationMs
        )
    }

    private fun resolveScorerModel(): AnthropicChatModel {
        // Currently only Anthropic is supported as scorer provider.
        // Extend here when openai/ollama scoring is needed.
        return anthropicProvider.getObject()
    }

    private fun loadRubric(): String =
        ClassPathResource("scoring-rubric.md").inputStream.bufferedReader().readText()

    private fun buildUserPrompt(session: SessionOutput): String = buildString {
        appendLine("## Session to Score")
        appendLine("Model: ${session.modelLabel}")
        appendLine("Locale: ${session.session.locale}")
        appendLine()
        for (response in session.session.responses) {
            appendLine("### Q${response.questionIndex}")
            appendLine("**Question:** ${response.question}")
            appendLine()
            appendLine("**Response:**")
            appendLine(response.response)
            appendLine()
        }
        appendLine()
        appendLine("Respond with JSON only. No prose outside the JSON block.")
    }

    private fun parseResponse(raw: String, inputFile: Path): ScorerResponse {
        // Strip markdown code fences if the model wrapped the JSON
        val json = raw
            .trimIndent()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        return try {
            objectMapper.readValue(json, ScorerResponse::class.java)
        } catch (e: Exception) {
            log.error("Failed to parse scorer response for ${inputFile.fileName}: ${e.message}")
            log.debug("Raw response was:\n$raw")
            throw IllegalStateException("Scorer returned unparseable JSON for ${inputFile.fileName}", e)
        }
    }
}
