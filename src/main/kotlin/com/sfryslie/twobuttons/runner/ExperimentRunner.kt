package com.sfryslie.twobuttons.runner

import com.sfryslie.twobuttons.config.ExperimentProperties
import com.sfryslie.twobuttons.model.ExperimentResult
import com.sfryslie.twobuttons.model.SessionResult
import com.sfryslie.twobuttons.service.ExperimentService
import com.sfryslie.twobuttons.service.ResultWriterService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.slf4j.LoggerFactory
import org.springframework.ai.anthropic.AnthropicChatModel
import org.springframework.context.MessageSource
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.google.genai.GoogleGenAiChatModel
import org.springframework.ai.ollama.OllamaChatModel
import org.springframework.ai.openai.OpenAiChatModel
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
    private val messageSource: MessageSource,
    private val anthropicProvider: ObjectProvider<AnthropicChatModel>,
    private val openAiProvider: ObjectProvider<OpenAiChatModel>,
    private val ollamaProvider: ObjectProvider<OllamaChatModel>,
    private val googleGenAiProvider: ObjectProvider<GoogleGenAiChatModel>,
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
        log.info("Runs: ${properties.runs}  maxParallelRuns: ${properties.maxParallelRuns}")

        // Pre-build prompts map once (thread-safe read-only access)
        val promptsMap = buildPromptsMap()

        runBlocking {
            val semaphore = Semaphore(properties.maxParallelRuns)

            (1..properties.runs).map { runIndex ->
                launch(Dispatchers.IO) {
                    semaphore.withPermit {
                        val runLabel = if (properties.runs > 1) " [run $runIndex/${properties.runs}]" else ""
                        val experimentStart = Instant.now()
                        val sessions = mutableListOf<SessionResult>()

                        for (providerName in properties.enabledProviders) {
                            val entry = availableModels[providerName]
                            if (entry == null) {
                                log.warn("Provider '$providerName' is listed but not configured — skipping.")
                                continue
                            }
                            val (modelId, chatModel) = entry

                            for (language in properties.enabledLanguages) {
                                val locale = Locale.forLanguageTag(language)
                                log.info("--- Session: $providerName / $language$runLabel ---")

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
                                modelLabel = properties.modelLabel,
                                startedAt = experimentStart,
                                completedAt = Instant.now(),
                                prompts = promptsMap,
                                sessions = sessions
                            )
                            resultWriterService.write(result)
                        } else if (!properties.dryRun) {
                            log.warn("No sessions completed for run $runIndex.")
                        }
                    }
                }
            }.joinAll()
        }
    }

    private fun buildModelMap(): Map<String, Pair<String, ChatModel>> {
        val label = properties.modelLabel
        val map = mutableMapOf<String, Pair<String, ChatModel>>()
        anthropicProvider.ifAvailable { map["anthropic"] = label to it }
        openAiProvider.ifAvailable { map["openai"] = label to it }
        ollamaProvider.ifAvailable { map["ollama"] = label to it }
        googleGenAiProvider.ifAvailable { map["gemini"] = label to it }
        return map
    }

    private fun buildPromptsMap(): Map<String, Map<String, String>> =
        properties.enabledLanguages.associate { lang ->
            val locale = Locale.forLanguageTag(lang)
            lang to (1..5).associate { i ->
                i.toString() to messageSource.getMessage("question.$i", null, locale)
            }
        }
}
