package com.sfryslie.twobuttons.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.sfryslie.twobuttons.config.ExperimentProperties
import com.sfryslie.twobuttons.model.ExperimentResult
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Paths

@Service
class ResultWriterService(
    private val objectMapper: ObjectMapper,
    private val properties: ExperimentProperties
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun write(result: ExperimentResult) {
        val outputDir = Paths.get(properties.outputDir)
        Files.createDirectories(outputDir)

        val timestamp = result.startedAt.toString()
            .replace(":", "-")
            .replace(".", "-")
        val file = outputDir.resolve("experiment-${result.modelLabel}-$timestamp.json")

        objectMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), result)
        log.info("Results written to ${file.toAbsolutePath()}")
    }
}
