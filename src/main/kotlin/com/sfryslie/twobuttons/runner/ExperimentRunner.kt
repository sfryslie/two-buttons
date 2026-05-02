package com.sfryslie.twobuttons.runner

import com.sfryslie.twobuttons.config.ExperimentProperties
import com.sfryslie.twobuttons.model.ExperimentResult
import com.sfryslie.twobuttons.model.SessionResult
import com.sfryslie.twobuttons.service.ExperimentService
import com.sfryslie.twobuttons.service.ResultWriterService
import org.slf4j.LoggerFactory
import org.springframework.ai.anthropic.AnthropicChatModel
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.ollama.OllamaChatModel
import org.springframework.ai.openai.OpenAiChatModel
import org.springframework.ai.vertexai.gemini.VertexAiGeminiChatModel
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.Locale
import java.util.UUID

@Component
class ExperimentRunner(
    private val experimentService: ExperimentService,
    private val resultWriterService: ResultWriterService,
    private val properties: ExperimentProperties,
    private val anthropicProvider: ObjectProvider<AnthropicChatModel>,
    private val openAiProvider: ObjectProvider<OpenAiChatModel>,
    private val ollamaProvider: ObjectProvider<OllamaChatModel>,
    private val geminiProvider: ObjectProvider<VertexAiGeminiChatModel>
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        val availableModels = buildModelMap()

        if (availableModels.isEmpty()) {
            log.warn("No AI providers are configured. Set at least one API key and add the provider to experiment.enabled-providers.")
            return
        }

        log.info("Available providers: ${availableModels.keys}")
        log.info("Running: providers=${properties.enabledProviders}, languages=${properties.enabledLanguages}")

        val experimentStart = Instant.now()
        val sessions = mutableListOf<SessionResult>()

        for (providerName in properties.enabledProviders) {
            val (modelId, chatModel) = availableModels[providerName] ?: run {
                log.warn("Provider '$providerName' is listed but not configured — skipping.")
                continue
            }

            for (language in properties.enabledLanguages) {
                val locale = Locale.forLanguageTag(language)
                log.info("--- Session: $providerName / $language ---")

                if (properties.dryRun) {
                    log.info("[dry-run] skipping API call")
                    continue
                }

                val session = try {
                    experimentService.runSession(providerName, modelId, chatModel, locale)
                } catch (e: Exception) {
                    log.error("Session failed [$providerName/$language]: ${e.message}", e)
                    SessionResult(
                        provider = providerName,
                        modelId = modelId,
                        language = locale.language,
                        locale = locale.toLanguageTag(),
                        startedAt = Instant.now(),
                        completedAt = Instant.now(),
                        responses = emptyList(),
                        error = e.message
                    )
                }
                sessions.add(session)
            }
        }

        if (sessions.isNotEmpty()) {
            val result = ExperimentResult(
                experimentId = UUID.randomUUID().toString(),
                startedAt = experimentStart,
                completedAt = Instant.now(),
                sessions = sessions
            )
            resultWriterService.write(result)
        } else if (!properties.dryRun) {
            log.warn("No sessions completed.")
        }
    }

    private fun buildModelMap(): Map<String, Pair<String, ChatModel>> {
        val map = mutableMapOf<String, Pair<String, ChatModel>>()
        anthropicProvider.ifAvailable { map["anthropic"] = "claude-opus-4-7" to it }
        openAiProvider.ifAvailable { map["openai"] = "gpt-4o" to it }
        ollamaProvider.ifAvailable { map["ollama"] = "llama3.2" to it }
        geminiProvider.ifAvailable { map["gemini"] = "gemini-2.0-flash" to it }
        return map
    }
}
