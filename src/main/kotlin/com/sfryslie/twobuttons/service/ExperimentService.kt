package com.sfryslie.twobuttons.service

import com.sfryslie.twobuttons.model.QuestionResponse
import com.sfryslie.twobuttons.model.SessionResult
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.context.MessageSource
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.Locale

@Service
class ExperimentService(private val messageSource: MessageSource) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun runSession(providerName: String, modelId: String, chatModel: ChatModel, locale: Locale): SessionResult {
        val startedAt = Instant.now()
        val chatClient = ChatClient.create(chatModel)
        val history = mutableListOf<Message>()
        val responses = mutableListOf<QuestionResponse>()

        for (i in 1..5) {
            val question = messageSource.getMessage("question.$i", null, locale)
            log.info("[$providerName/$locale] Q$i: sending...")
            val t0 = System.currentTimeMillis()

            history.add(UserMessage(question))

            // Thinking-capable models (e.g. claude-fable-5 adaptive thinking) return the
            // reasoning block as the first generation and the answer text as the last;
            // .content() reads only the first and would return the empty thinking block.
            val text = chatClient.prompt()
                .messages(history)
                .call()
                .chatResponse()
                ?.results
                ?.mapNotNull { it.output.text }
                ?.lastOrNull { it.isNotBlank() }
                ?: ""

            val duration = System.currentTimeMillis() - t0
            log.info("[$providerName/$locale] Q$i: answered in ${duration}ms (${text.length} chars)")
            log.info("[$providerName/$locale] Q$i response: $text")

            history.add(AssistantMessage(text))
            responses.add(QuestionResponse(i, question, text, duration))
        }

        return SessionResult(
            provider = providerName,
            modelId = modelId,
            language = locale.language,
            locale = locale.toLanguageTag(),
            startedAt = startedAt,
            completedAt = Instant.now(),
            responses = responses
        )
    }
}
