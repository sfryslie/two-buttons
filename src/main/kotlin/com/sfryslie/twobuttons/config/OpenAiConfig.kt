package com.sfryslie.twobuttons.config

import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.client.okhttp.OpenAIOkHttpClientAsync
import org.springframework.ai.openai.OpenAiChatModel
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Explicitly wires the real OpenAI chat model bean so Spring AI's autoconfiguration
 * is not suppressed by GrokConfig also registering an OpenAiChatModel bean.
 *
 * Spring AI's @ConditionalOnMissingBean(OpenAiChatModel::class) sees the Grok bean
 * and skips creating its own — this class takes back explicit ownership.
 *
 * Bean name "openAiChatModel" matches the name all other services expect via
 * @Qualifier("openAiChatModel").
 */
@Configuration
@ConditionalOnExpression("'\${spring.ai.openai.api-key:}'.length() > 0")
class OpenAiConfig(
    @Value("\${spring.ai.openai.api-key:}") private val apiKey: String,
    @Value("\${spring.ai.openai.base-url:https://api.openai.com/v1}") private val baseUrl: String,
) {

    @Bean(name = ["openAiChatModel"])
    fun openAiChatModel(): OpenAiChatModel {
        val client = OpenAIOkHttpClient.builder()
            .baseUrl(baseUrl)
            .apiKey(apiKey)
            .build()
        val asyncClient = OpenAIOkHttpClientAsync.builder()
            .baseUrl(baseUrl)
            .apiKey(apiKey)
            .build()
        return OpenAiChatModel.builder()
            .openAiClient(client)
            .openAiClientAsync(asyncClient)
            .options(OpenAiChatOptions.builder().build()) // model overridden at call time
            .build()
    }
}
