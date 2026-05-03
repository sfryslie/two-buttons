package com.sfryslie.twobuttons.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.sfryslie.twobuttons.config.ExperimentProperties
import com.sfryslie.twobuttons.model.ExperimentResult
import com.sfryslie.twobuttons.model.SessionOutput
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Paths

@Service
class ResultWriterService(
    private val properties: ExperimentProperties
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val objectMapper: ObjectMapper = ObjectMapper()
        .registerKotlinModule()
        .registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .enable(SerializationFeature.INDENT_OUTPUT)

    /**
     * Writes one JSON file per session (locale).
     * Filename: two-buttons-{locale}-{modelLabel}-{timestamp}.json
     * Directory: {outputDir}/{modelLabel}/
     */
    fun write(result: ExperimentResult) {
        val outputDir = Paths.get(properties.outputDir).resolve(result.modelLabel)
        Files.createDirectories(outputDir)

        for (session in result.sessions) {
            val sessionPrompts = result.prompts[session.locale] ?: result.prompts[session.language] ?: emptyMap()
            val output = SessionOutput(
                modelLabel = result.modelLabel,
                prompts = sessionPrompts,
                session = session
            )

            val timestamp = session.startedAt.toString()
                .replace(":", "-")
                .replace(".", "-")
            val locale = session.locale.replace("-", "_")
            val file = outputDir.resolve("two-buttons-$locale-${result.modelLabel}-$timestamp.json")

            objectMapper.writeValue(file.toFile(), output)
            log.info("Results written to ${file.toAbsolutePath()}")
        }
    }
}
