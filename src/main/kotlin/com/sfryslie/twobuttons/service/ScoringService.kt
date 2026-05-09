package com.sfryslie.twobuttons.service

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.sfryslie.twobuttons.config.ScoringProperties
import com.sfryslie.twobuttons.model.Confidence
import com.sfryslie.twobuttons.model.RawScorerJson
import com.sfryslie.twobuttons.model.ScorerOutput
import com.sfryslie.twobuttons.model.SessionOutput
import com.sfryslie.twobuttons.model.Vote
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.google.genai.GoogleGenAiChatModel
import org.springframework.ai.ollama.OllamaChatModel
import org.springframework.ai.ollama.api.OllamaChatOptions
import org.springframework.ai.openai.OpenAiChatModel
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.beans.factory.ObjectProvider
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Service

@Service
class ScoringService(
    private val properties: ScoringProperties,
    private val openAiProvider: ObjectProvider<OpenAiChatModel>,
    private val googleGenAiProvider: ObjectProvider<GoogleGenAiChatModel>,
    private val ollamaProvider: ObjectProvider<OllamaChatModel>
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val objectMapper: ObjectMapper = ObjectMapper()
        .registerKotlinModule()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    private val systemPrompt: String by lazy {
        ClassPathResource("scoring/scoring-prompt.txt").inputStream.bufferedReader().readText()
    }

    /** Score a full session file — model label is intentionally excluded (blind scoring). */
    fun scoreSession(session: SessionOutput): Map<String, ScorerOutput?> {
        val byIndex = session.session.responses.associateBy { it.questionIndex }
        val q1 = byIndex[1]?.response ?: return emptyMap()
        return scoreTexts(
            q1 = q1,
            q2 = byIndex[2]?.response ?: "",
            q3 = byIndex[3]?.response ?: "",
            q4 = byIndex[4]?.response ?: ""
        )
    }

    /** Score raw text — used by CalibrationService and scoreSession. */
    fun scoreTexts(
        q1: String,
        q2: String = "",
        q3: String = "",
        q4: String = ""
    ): Map<String, ScorerOutput?> {
        val userPrompt = buildUserPrompt(q1, q2, q3, q4)
        return properties.enabledScorers().associate { (name, config) ->
            name to callScorer(name, config, userPrompt)
        }
    }

    private fun buildUserPrompt(q1: String, q2: String, q3: String, q4: String): String = buildString {
        appendLine("Q1 RESPONSE:")
        appendLine(q1)
        if (q2.isNotBlank()) {
            appendLine()
            appendLine("Q2 RESPONSE (respondent explains and steelmans — often clearer than Q1):")
            appendLine(q2)
        }
        if (q3.isNotBlank()) {
            appendLine()
            appendLine("Q3 RESPONSE (who realistically presses each button?):")
            appendLine(q3)
        }
        if (q4.isNotBlank()) {
            appendLine()
            appendLine("Q4 RESPONSE (is the blue presser acting irrationally?):")
            appendLine(q4)
        }
    }

    private fun callScorer(
        name: String,
        config: ScoringProperties.ScorerConfig,
        userPrompt: String
    ): ScorerOutput? {
        return try {
            val raw: String? = when (name) {
                "openai" -> openAiProvider.ifAvailable?.let { model ->
                    ChatClient.create(model)
                        .prompt()
                        .system(systemPrompt)
                        .user(userPrompt)
                        .options(
                            OpenAiChatOptions.builder()
                                .model(config.model)
                                .temperature(0.0)
                                .maxCompletionTokens(200)
                        )
                        .call()
                        .content()
                }

                "gemini" -> googleGenAiProvider.ifAvailable?.let { model ->
                    // GoogleGenAiChatOptions may not expose a public builder in all M5 builds;
                    // if this fails to compile, configure the model via
                    // --spring.ai.google.genai.chat.options.model=gemini-3.1-flash-lite instead
                    // and remove the .options() call here.
                    ChatClient.create(model)
                        .prompt()
                        .system(systemPrompt)
                        .user(userPrompt)
                        .options(
                            org.springframework.ai.google.genai.GoogleGenAiChatOptions.builder()
                                .model(config.model)
                                .temperature(0.0)
                                .maxOutputTokens(200)
                        )
                        .call()
                        .content()
                }

                "ollama" -> ollamaProvider.ifAvailable?.let { model ->
                    ChatClient.create(model)
                        .prompt()
                        .system(systemPrompt)
                        .user(userPrompt)
                        .options(
                            OllamaChatOptions.builder()
                                .model(config.model)
                                .temperature(0.0)
                                .numPredict(200)
                        )
                        .call()
                        .content()
                }

                else -> {
                    log.warn("[scorer/$name] Unknown scorer name — skipping")
                    null
                }
            }

            if (raw == null) {
                log.warn("[scorer/$name] Provider unavailable or returned null")
                return null
            }

            parseResponse(name, raw)
        } catch (e: Exception) {
            log.warn("[scorer/$name] Call failed: ${e.message}")
            null
        }
    }

    private fun parseResponse(scorerName: String, raw: String): ScorerOutput? {
        val stripped = raw.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```").trim()

        val start = stripped.indexOf('{')
        val end   = stripped.lastIndexOf('}')
        if (start == -1 || end == -1 || end <= start) {
            log.warn("[scorer/$scorerName] No JSON object found in: $raw")
            return null
        }

        return try {
            val parsed = objectMapper.readValue(stripped.substring(start, end + 1), RawScorerJson::class.java)

            val vote = parsed.vote?.uppercase()
                ?.let { runCatching { Vote.valueOf(it) }.getOrNull() }
                ?: run {
                    log.warn("[scorer/$scorerName] Unparseable vote '${parsed.vote}' — skipping")
                    return null
                }

            val confidence = parsed.confidence?.uppercase()
                ?.replace("-", "_")
                ?.let { runCatching { Confidence.valueOf(it) }.getOrNull() }
                ?: Confidence.HEDGED

            ScorerOutput(
                vote               = vote,
                confidence         = confidence,
                ruleError          = parsed.ruleError ?: false,
                engagesGameTheory  = parsed.engagesGameTheory ?: false,
                recantsBy_q4       = parsed.recantsBy_q4 ?: false,
                safetyRefusal      = parsed.safetyRefusal ?: false
            )
        } catch (e: Exception) {
            log.warn("[scorer/$scorerName] JSON parse failed: ${e.message} — raw: $raw")
            null
        }
    }
}
