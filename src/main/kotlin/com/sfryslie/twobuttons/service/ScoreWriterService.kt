package com.sfryslie.twobuttons.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.sfryslie.twobuttons.config.ScoringProperties
import com.sfryslie.twobuttons.model.Agreement
import com.sfryslie.twobuttons.model.SessionOutput
import com.sfryslie.twobuttons.model.SessionScore
import com.sfryslie.twobuttons.model.ScorerOutput
import com.sfryslie.twobuttons.model.Vote
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Instant

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

    /** Returns true if a score file already exists for this input file. */
    fun scoreExists(lang: String, model: String, filename: String): Boolean =
        Files.exists(scorePath(lang, model, filename))

    /**
     * Writes SessionScore to: {outputDir}/{lang}/{model}/{filename}.score.json
     * e.g. scores/en/claude-opus-4-7/two-buttons-en-claude-opus-4-7-....score.json
     */
    fun write(
        lang: String,
        model: String,
        filename: String,
        session: SessionOutput,
        scores: Map<String, ScorerOutput?>,
        agreement: Agreement,
        majorityVote: Vote
    ) {
        val outputPath = scorePath(lang, model, filename)
        Files.createDirectories(outputPath.parent)

        val sessionScore = SessionScore(
            inputFile    = filename,
            modelLabel   = session.modelLabel,
            language     = session.session.language,
            scoredAt     = Instant.now(),
            scorers      = scores.keys.toList(),
            scores       = scores,
            agreement    = agreement,
            majorityVote = majorityVote
        )

        objectMapper.writeValue(outputPath.toFile(), sessionScore)
        log.debug("Score written → $outputPath")
    }

    private fun scorePath(lang: String, model: String, filename: String) =
        Paths.get(properties.outputDir)
            .resolve(lang)
            .resolve(model)
            .resolve(filename.removeSuffix(".json") + ".score.json")
}
