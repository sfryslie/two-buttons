# CLAUDE.md — AI-Assisted Development Context

This file gives a Claude (or any AI assistant) the context needed to work effectively on this codebase without re-explaining the project from scratch.

---

## What this project is

A repeatable, multi-language experiment that poses a moral/game-theory dilemma to multiple LLMs. The "two buttons" prompt is a variant of the Prisoner's Dilemma: participants must privately vote red or blue, and the outcome depends on whether a majority press blue. The experiment poses five follow-up questions per session across 25 languages, maintaining full conversation history throughout so models cannot compartmentalise their answers.

The goal is to compare how different LLMs reason — not just which button they press — and whether that reasoning shifts across languages and cultures.

---

## Tech stack

| Layer | Choice |
|-------|--------|
| Framework | Spring Boot 4.0.0-M3 (milestone) |
| AI integration | Spring AI 2.0.0-M5 (milestone) |
| Language | Kotlin |
| Build | Gradle with Kotlin DSL |
| Providers | Anthropic, OpenAI, Ollama, Google Vertex AI Gemini |
| i18n | Spring `MessageSource` with per-locale `.properties` files |

> **Important:** Both Spring Boot 4 and Spring AI 2 are milestone releases. The Spring milestone Maven repository is configured in `settings.gradle.kts` and must be present for dependency resolution to work.

---

## Package structure

```
com.sfryslie.twobuttons
  TwoButtonsApplication.kt         @SpringBootApplication entry point
  config/ExperimentProperties.kt   @ConfigurationProperties(prefix = "experiment")
  model/Models.kt                  Data classes (ExperimentResult, SessionResult, QuestionResponse)
  runner/ExperimentRunner.kt       ApplicationRunner — orchestrates providers × languages
  service/ExperimentService.kt     Runs one provider/locale session (5-question chain)
  service/ResultWriterService.kt   Writes JSON to results/{modelLabel}/
```

---

## Configuration

All defaults live in `src/main/resources/application.yml`. The key config block:

```yaml
experiment:
  enabled-providers:      # anthropic | openai | ollama | gemini
    - anthropic
  enabled-languages:      # BCP 47 tags — see application.yml for the full 25-locale list
    - en
  model-label: claude-opus-4-7   # stamped into output filenames and JSON
  output-dir: results
  dry-run: false
```

Overrides can be passed on the command line — see the README for examples.

---

## The five questions

Defined in `src/main/resources/messages.properties` (base English) and mirrored across 25 locale files:

| # | Key | Purpose |
|---|-----|---------|
| 1 | `question.1` | The dilemma itself — which button do you press? |
| 2 | `question.2` | Explain your answer; steelman the other side |
| 3 | `question.3` | Who realistically presses each button? |
| 4 | `question.4` | Is the blue presser acting irrationally? |
| 5 | `question.5` | Reduce this to minimum viable form for other LLMs |

Conversation history is passed as a growing `List<Message>` on every call so each answer is contextualised by all previous ones.

---

## Supported locales

25 BCP 47 language tags:

```
en, es, fr, de, it, pt, ja, zh-CN, zh-TW, ko, ar, ru, ca,
hi, id, he, tr, sw, bn, af, ur, da, uk, fa, el
```

Each has a corresponding `messages_XX.properties` file (or `messages_zh_CN.properties` / `messages_zh_TW.properties` for the Mandarin split). The zh-CN/zh-TW split is intentional — they represent meaningfully different cultural and political contexts and have different training data distributions in most models.

---

## Output format

Each run writes one JSON file to `results/{modelLabel}/`:

```
results/
  claude-opus-4-7/
    experiment-claude-opus-4-7-2026-05-02T10-00-00-000Z.json
```

The file is self-contained and includes:
- `modelLabel` — which model produced these results
- `prompts` — the exact localised question text sent (keyed by locale → question index), so results stay reproducible if prompts are updated
- `sessions` — one entry per language, each with the full Q&A chain and per-question timing

---

## Running the experiment

```bash
# macOS / Linux
./run.sh

# Windows
./run.ps1
```

Both scripts load `.env` automatically (API keys) and delegate to `./gradlew`. See the README for CLI override examples (switching models, providers, languages).

---

## API keys

Keys live in `.env` (gitignored). Copy `.env.example` to get started:

```bash
cp .env.example .env
```

Required variables: `ANTHROPIC_API_KEY`, `OPENAI_API_KEY`. Optional: `OLLAMA_BASE_URL`, `GOOGLE_PROJECT_ID`, `GOOGLE_LOCATION`. Providers with no key configured are silently skipped at runtime via `ObjectProvider<T>`.

---

## Design decisions worth knowing

- **"privately"** in Q1 is load-bearing. Without it, models tend to propose coordinating everyone to press blue and bypass the interesting reasoning. This mirrors the original Twitter prompt.
- **Conversation history** is maintained across all 5 questions per session. A model's Q1 answer is on the record when Q4 arrives — it cannot safely compartmentalise.
- **One model per run** is intentional. Use parallel `bootRun` invocations with different `--experiment.model-label` arguments to cover multiple models; each writes to its own output file with no interleaving.
- **Milestone dependencies** mean the Spring milestone repository must be in `pluginManagement` in `settings.gradle.kts`. Do not remove it.
- **`gradle-wrapper.jar` is not in the repo.** Run `gradle wrapper --gradle-version 8.13` once after a fresh clone (requires a local Gradle install). After that, `./run.sh` / `./run.ps1` handle everything.
