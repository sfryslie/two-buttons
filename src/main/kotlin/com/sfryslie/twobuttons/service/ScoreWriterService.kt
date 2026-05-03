package com.sfryslie.twobuttons.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.sfryslie.twobuttons.config.ScoringProperties
import com.sfryslie.twobuttons.model.SessionScore
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Paths

@Service
class ScoreWriterService(
    private val properties: ScoringProperties
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val objectMapper: ObjectMapper = ObjectMapper()
        .registerKotlinModule()
        .registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .enable(SerializationFeature.INDENT_OUTPUT)

    /**
     * Writes to: {outputDir}/{scorerModel}/{inputFilename}-scored.json
     * e.g. scores/claude-opus-4-7/two-buttons-en-claude-haiku-4-5-20251001-...-scored.json
     */
    fun write(score: SessionScore) {
        val outputDir = Paths.get(properties.outputDir).resolve(score.scorerModel)
        Files.createDirectories(outputDir)

        val outputFilename = "${score.inputFile.removeSuffix(".json")}-scored.json"
        val file = outputDir.resolve(outputFilename)

        objectMapper.writeValue(file.toFile(), score)
        log.info("Score written to ${file.toAbsolutePath()}")
    }
}
