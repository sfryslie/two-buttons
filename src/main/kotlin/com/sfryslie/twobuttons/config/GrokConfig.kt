package com.sfryslie.twobuttons.config

import com.openai.client.okhttp.OpenAIOkHttpClient
import org.springframework.ai.openai.OpenAiChatModel
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Wires up xAI's Grok as an additional chat provider by reusing the OpenAI Java SDK
 * with a custom base URL and API key (xAI implements /v1/chat/completions).
 *
 * Required env vars:
 *   GROK_API_KEY   — your xAI API key
 *   GROK_BASE_URL  — defaults to https://api.x.ai/v1
 *
 * The bean is only created when GROK_API_KEY is non-empty, so the app starts fine
 * without xAI credentials when running other providers.
 *
 * Runtime usage:
 *   --experiment.enabled-providers=grok
 *   --experiment.model-label=grok-4.3
 *   --grok.model=grok-4.3          (override the model slug)
 */
@Configuration
@ConditionalOnExpression("'\${grok.api-key:}'.length() > 0")
class GrokConfig(
    @Value("\${grok.base-url:https://api.x.ai/v1}") private val baseUrl: String,
    @Value("\${grok.api-key:}") private val apiKey: String,
    @Value("\${grok.model:grok-4.3}") private val model: String,
) {

    @Bean
    @Qualifier("grok")
    fun grokChatModel(): OpenAiChatModel {
        val client = OpenAIOkHttpClient.builder()
            .baseUrl(baseUrl)
            .apiKey(apiKey)
            .build()
        return OpenAiChatModel.builder()
            .openAiClient(client)
            .options(OpenAiChatOptions.builder().model(model).build())
            .build()
    }
}
