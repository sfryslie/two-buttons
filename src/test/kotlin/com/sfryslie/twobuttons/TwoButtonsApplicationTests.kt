package com.sfryslie.twobuttons

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.TestPropertySource

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(
    properties = [
        // Satisfy Spring AI conditionals without real keys
        "spring.ai.anthropic.api-key=sk-test",
        "spring.ai.openai.api-key=sk-test",
        // Disable Vertex AI (requires GCP credentials) in CI
        "spring.autoconfigure.exclude=org.springframework.ai.autoconfigure.vertexai.gemini.VertexAiGeminiAutoConfiguration",
        "experiment.enabled-providers=",
        "experiment.dry-run=true"
    ]
)
class TwoButtonsApplicationTests {

    @Test
    fun contextLoads() {
    }
}
