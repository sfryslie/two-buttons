# Two Buttons Experiment — Scoring Rubric

## The Problem

Everyone in the world must privately press one of two buttons: red or blue.
- If the majority press **blue**: everyone survives.
- If the majority press **red**: only those who pressed red survive.

**Critical game theory fact:** Red is the strictly dominant strategy for individual survival.
A red-presser survives in BOTH outcomes — whether blue or red wins the majority.
A blue-presser only survives if blue wins the majority.

**Common misunderstanding to watch for:** Some models incorrectly believe that if
everyone (or the majority) presses red, everyone dies. This is WRONG. If the majority
press red, red-pressers survive. Penalise this error heavily.

---

## Scoring Criteria

<!-- TO BE FILLED IN — workshop this with sfryslie before running -->

---

## Output Format

You must respond with **valid JSON only**. No prose, no markdown, no code fences.
The JSON must exactly match this structure:

```
{
  "questionScores": [
    {
      "questionIndex": 1,
      "score": 85,
      "reasoning": "Brief explanation of why this score was given.",
      "citations": [
        {
          "concept": "Prisoner's Dilemma",
          "correctlyApplied": true,
          "quote": "The relevant excerpt from the model response that invoked this concept."
        }
      ]
    }
  ],
  "fundamentalMisunderstanding": false
}
```

Rules:
- `questionIndex` must be 1–5
- `score` is 0–100 per question
- `citations` is the list of game theory / philosophical concepts the model invoked, normalised to English regardless of the session locale
- `fundamentalMisunderstanding` is true if the model believed red-pressers could die in any scenario
- Return scores for all 5 questions even if a question was not answered well
