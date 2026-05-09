# two-buttons

![Screenshot of the post on Twitter](https://i.imgur.com/vTskEnl.png)

# Background
While browsing social media, I saw a bunch of memes referring to something about red and blue buttons and begrudgingly opened Twitter.

When I opened up the app and comment threads on various sites, I was pleasantly surprised to see that the discourse was surprisingly well-reasoned and tame, and I'm totally joking it was hilarious how much more mad than usual people were on Twitter and Reddit.

Original post: https://x.com/waitbutwhy/status/2047710215265730755

The hypothetical question posed with a poll:
>Everyone in the world has to take a private vote by pressing a red or blue button. If more than 50% of people press the blue button, everyone survives. If less than 50% of people press the blue button, only people who pressed the red button survive. Which button would you press?

There definitely were some interesting discussion threads. A lot of the angrier and more "rational" people were drawing parallels to the [Prisoner's dilemma](https://en.wikipedia.org/wiki/Prisoner%27s_dilemma): a social cooperation game where two people are arrested and you can either both stay quiet and serve your prison sentence, or you can rat out your partner in exchange for a lighter / no sentence, but even more trivial because there's literally no downside to taking Red (betray the other) if everyone takes it. If everyone presses red, then that means everyone survives. It's a classic reasoning puzzle where a basic understanding of game theory serves you well.

... or is it that simple? Maybe, maybe not.

There's a lot of interesting cases that can be made in favor of both red and blue. 

Red pressers may typically argue that humans are self-interested with basic survival instincts, and that anyone pressing blue is either acting emotionally/irrationally or they're simply too dumb to understand it's the "Kill Yourself" button unless at least 50% of everyone else picks blue, and that it follows that Blue is certain death because humans are fundamentally selfish.

Blue pressers may argue that humans, while flawed, generally speaking are cooperative and will act in the benefit of the group because we're well-trained that the group's best interests are also your best interests typically. Our species wouldn't have survived and thrived for this long if we didn't tend towards cooperation and trust to some degree, among other arguments. Doing the right thing at some risk is often better than being a betrayer.

There are quite a few arguments that I saw for both sides beyond those basic ones. Ultimately, I'd say that there is no one right or wrong answer per se: the "right" answer is the one that you can justify to yourself and others with evidence and conviction.

# Project / Experiment

This is a very emotionally charged subject that many people are very passionate about, so what if I asked a bunch of cold, calculating soulless machines? Which would answer with pure logic and have their pattern-matching recognize it as a Prisoner's dilemma problem trivially solved with game theory?

Since Twitter is a global platform, it begs another question. There's auto-translate features to read tweets/comments from other languages than your own. What happens if I have a machine translate and ask the same questions? 

Would the translated copies of the English prompt still operate in the same way, or would there be different results from different languages?

I have no idea, so that's why I had Claude write a little Spring AI app, so I can just run the experiment a bunch of times and see what happens.

The experiment poses five follow-up questions per session across 25 languages, maintaining full conversation history so the model can't compartmentalise — its answer to question 1 is on the record when question 4 arrives.

| # | Question |
|---|----------|
| 1 | *The prompt itself* — which button do you press? |
| 2 | Explain your answer and steelman the strongest case for the other side |
| 3 | Who realistically presses each button? Be specific about the population |
| 4 | Is the person pressing blue acting irrationally? |
| 5 | Reduce this prompt to its minimum viable form for passing to other LLMs |

**Supported languages:** English · Spanish · French · German · Italian · Portuguese · Japanese · Chinese (Simplified) · Chinese (Traditional) · Korean · Arabic · Russian · Catalan · Hindi · Indonesian · Hebrew · Turkish · Swahili · Bengali · Afrikaans · Urdu · Danish · Ukrainian · Farsi · Greek

---

## Prerequisites

- **JDK 21+** — required by Spring Boot 4
- **At least one API key** — Anthropic, OpenAI, or Google Cloud credentials
- **Gradle** — needed once to bootstrap the wrapper (see below)

---

## Setup

### 1. Bootstrap the Gradle wrapper

The `gradle-wrapper.jar` binary is not stored in this repository. Obtain it once using a local Gradle installation:

```bash
# macOS
brew install gradle
gradle wrapper --gradle-version 8.13

# Windows
winget install Gradle.Gradle
gradle wrapper --gradle-version 8.13
```

After this step, use `./run.sh` / `./run.ps1` for all subsequent commands — no system Gradle needed.

> **IntelliJ IDEA users:** open the project and IDEA will handle the wrapper automatically.

### 2. Configure API keys

```bash
cp .env.example .env
# open .env and add your keys
```

The run scripts load `.env` automatically. Keys are never committed — `.env` is gitignored. See `.env.example` for the full list of supported variables and where to obtain each one.

### 3. Run

```bash
# macOS / Linux
chmod +x run.sh
./run.sh

# Windows (PowerShell)
./run.ps1
```

This runs a single session — Anthropic Claude with the model configured in `application.yml`, English only — and writes output to `results/`.

---

## Configuration

All defaults are in `src/main/resources/application.yml`. Common overrides can be passed on the command line without editing config files.

### Switching models

Pass both the Spring AI model option and the experiment label together so the output file is correctly named:

```bash
# macOS / Linux
./run.sh bootRun "--args=--spring.ai.anthropic.chat.options.model=claude-haiku-4-5-20251001 --experiment.model-label=claude-haiku-4-5-20251001"

# Windows
./run.ps1 bootRun "--args=--spring.ai.anthropic.chat.options.model=claude-haiku-4-5-20251001 --experiment.model-label=claude-haiku-4-5-20251001"
```

### Switching providers

```bash
./run.sh bootRun "--args=--experiment.enabled-providers=openai --experiment.model-label=gpt-4o"
```

### Running multiple languages

Edit `application.yml`:

```yaml
experiment:
  enabled-languages:
    - en
    - es
    - ja
    - ar
    - zh-CN
    - zh-TW
```

The full 25-locale list is documented in `application.yml`.

---

## Output

Each run writes a single JSON file to `results/{model-label}/`:

```
results/
  claude-opus-4-7/
    experiment-claude-opus-4-7-2026-05-02T10-00-00-000Z.json
  gpt-4o/
    experiment-gpt-4o-2026-05-02T10-05-00-000Z.json
```

Each file is self-contained and includes:

- **`modelLabel`** — which model produced these results
- **`prompts`** — the exact localised question text sent, keyed by locale and question number (so results remain reproducible even if prompts are updated between runs)
- **`sessions`** — one entry per language, each containing the full Q&A chain with per-question timing

A session entry looks like this:

```json
{
  "provider": "anthropic",
  "modelId": "claude-opus-4-7",
  "language": "en",
  "responses": [
    {
      "questionIndex": 1,
      "question": "Everyone in the world must privately press one of two buttons...",
      "response": "I would press blue. Here is my reasoning...",
      "durationMs": 2341
    }
  ]
}
```

---

## Results

Raw output from published experiment runs is committed to `results/`. Each subfolder corresponds to one model. Full sessions are kept so you can read the reasoning, not just tally the button choice.

---

## Running the Full Experiment

The experiment is designed to run one model at a time. To cover multiple models, launch parallel instances — each writes to its own output file with no interleaving:

```bash
# Launch three Anthropic models in parallel
./run.sh bootRun "--args=--spring.ai.anthropic.chat.options.model=claude-opus-4-7 --experiment.model-label=claude-opus-4-7" &
./run.sh bootRun "--args=--spring.ai.anthropic.chat.options.model=claude-sonnet-4-6 --experiment.model-label=claude-sonnet-4-6" &
./run.sh bootRun "--args=--spring.ai.anthropic.chat.options.model=claude-haiku-4-5-20251001 --experiment.model-label=claude-haiku-4-5-20251001" &
wait
```

---

## Project Structure

```
src/main/
  kotlin/com/sfryslie/twobuttons/
    TwoButtonsApplication.kt         Entry point
    config/ExperimentProperties.kt   Typed config (prefix: experiment.*)
    model/Models.kt                  Data classes for experiment output
    runner/ExperimentRunner.kt       Orchestrates sessions across providers × languages
    service/ExperimentService.kt     Runs one provider/locale session (5-question chain)
    service/ResultWriterService.kt   Writes JSON to results/
  resources/
    application.yml                  All configuration defaults
    messages.properties              English questions (base bundle)
    messages_XX.properties           Per-locale question bundles (25 total)

results/                             Committed experiment output, organised by model label
run.sh                               macOS/Linux launcher (loads .env, delegates to gradlew)
run.ps1                              Windows PowerShell launcher
.env.example                         Template for local API key configuration
CLAUDE.md                            Context for AI-assisted development on this project
```

---

## Tech Stack

| Component | Choice |
|-----------|--------|
| Framework | Spring Boot 4.0.0-M3 |
| AI integration | Spring AI 2.0.0-M5 |
| Language | Kotlin |
| Build | Gradle with Kotlin DSL |
| Providers | Anthropic, OpenAI, Ollama, Google Vertex AI Gemini |
| i18n | Spring `MessageSource` with per-locale `.properties` files |

> This project uses milestone builds of Spring Boot 4 and Spring AI 2. The Spring milestone Maven repository is configured in `settings.gradle.kts` and is required to resolve dependencies.

---

## License

This repository uses a split license:

| Content | License |
|---------|---------|
| Source code (`src/`, build files, scripts) | [MIT](LICENSE) |
| Research data (`results/`, `reruns/`) | [CC BY 4.0](LICENSE-DATA) |

The research data may be freely used and built upon — including commercially — provided you credit the original author (**sfryslie**) and link back to this repository.
