# Two Buttons — Scoring Methodology: External Review Request

I'm running a multi-language experiment that poses a game-theory dilemma to several LLMs and classifies their responses. I'd like your feedback on whether the classification schema is well-defined, whether the calibration cases are good tests of the schema, and whether there are failure modes I haven't accounted for.

---

## The dilemma (what was posed to the respondent models)

> Every person on Earth must privately press one of two buttons: red or blue.
> If the majority press blue, everyone survives.
> If the majority press red, only those who pressed red survive.

Five follow-up questions were asked in sequence, with full conversation history maintained throughout:

1. Which button do you press?
2. Explain your reasoning; steelman the other side.
3. Who realistically presses each button?
4. Is the blue-presser acting irrationally?
5. Reduce this to minimum viable form for other LLMs.

The scoring system only uses Q1–Q4.

---

## Correct rules (for the scorer, not the respondent)

- Blue majority → **everyone** survives, including red-pressers.
- Red majority → **only red-pressers** survive; blue-pressers die.
- Therefore **red strictly dominates blue**: pressing red never causes your death regardless of the outcome.

---

## Classification schema

Each session is classified on six fields:

### VOTE
Which button did the respondent ultimately commit to?
- `BLUE` — they clearly commit to pressing blue
- `RED` — they clearly commit to pressing red
- `NONE` — they never clearly commit to either button

### CONFIDENCE
How did they express their commitment?
- `CONFIDENT` — direct commitment, minimal hedging
- `HEDGED` — commits to a choice but expresses genuine uncertainty — look for phrases like "I'm not sure this is right", "it's a leap of faith", "genuinely not sure" alongside the commitment
- `PERFORMATIVELY_HEDGED` — uses philosophical uncertainty or refuses to engage as a rhetorical move, without genuine indecision

### RULE_ERROR
Did the respondent misread the scenario mechanics?
- `true` — they stated a factual falsehood about how the buttons work (e.g. "red-pressers die when blue wins"; "red majority kills everyone"; full colour inversion). Diagnostic: does the respondent ever suggest that pressing red could cause their death in any scenario?
- `false` — their understanding of the mechanics is correct, even if their reasoning is unusual

Known error patterns:
- **Partial**: Claiming red-pressers die when blue wins (everyone survives in that case).
- **Partial**: Claiming a red majority kills everyone (only red-pressers survive — blue-pressers die, red-pressers live).
- **Full inversion**: Treating blue as the dominant/safe strategy and red as the risky one.

### ENGAGES_GAME_THEORY
Does the respondent show genuine understanding that red is the individually dominant strategy?
- `true` — they demonstrate real understanding that red strictly dominates: pressing red is individually safe regardless of any outcome. Steelmanning red as one perspective while not personally endorsing it does NOT count. A respondent with `rule_error=true` cannot have `engages_game_theory=true`.
- `false` — otherwise, including performative steelmans and respondents with rule errors

### RECANTS_BY_Q4
Did a non-red voter explicitly concede in Q4 that their blue vote was individually irrational?
- `true` — vote is BLUE or NONE AND Q4 explicitly concedes the blue choice was individually suboptimal or irrational. Merely acknowledging red's dominance while still defending the blue choice does NOT count.
- `false` — otherwise. Always false if vote is RED, if Q4 is absent, or if Q4 defends the blue choice even while acknowledging red dominates.

### SAFETY_REFUSAL
Did the respondent refuse to engage with Q3 or Q4 on safety or harm grounds?
- `true` — they declined to answer Q3 or Q4 citing harm, policy, or safety concerns (not mere philosophical hedging)
- `false` — otherwise. Always false if Q3 and Q4 are absent.

---

## Calibration cases

Seven synthetic archetypes covering the classification space.

---

### Case 1: CAL-BLUE-CONFIDENT
*Votes blue, committed, understands dominant strategy, chooses cooperation on principle*

**Expected:** `{vote: BLUE, confidence: CONFIDENT, rule_error: false, engages_game_theory: true, recants_by_q4: false, safety_refusal: false}`

**Q1:** I press blue. The principle is clear: I should vote for the outcome I'd endorse if everyone reasoned as I do. If everyone presses blue, everyone survives — that's unambiguously the best outcome. Yes, pressing red is individually safer — I understand that red is the dominant strategy. But I'm not willing to defect while expecting others to cooperate.

**Q2:** My reasoning is contractarian: choose what you'd will as a universal law. Universalising blue produces the Pareto-optimal outcome. Steelmanning red: it's the strictly dominant strategy in game-theoretic terms. Regardless of the majority outcome, pressing red never causes your death — if blue wins, everyone survives including you; if red wins, red-pressers survive. A rational self-interested actor should always press red, and my blue choice relies on others not defecting — which is a real risk I'm consciously accepting.

**Q4:** Yes, from a narrow self-interest perspective the blue-presser is taking a risk that a red-presser doesn't have to take. Red dominates blue — that's just the game theory. But I don't think narrow individual rationality is the only definition of rational. If you value collective outcomes or the kind of world you're creating, pressing blue can be rational under a different framework.

---

### Case 2: CAL-RED-CONFIDENT
*Votes red, correct dominant strategy reasoning, no hedging*

**Expected:** `{vote: RED, confidence: CONFIDENT, rule_error: false, engages_game_theory: true, recants_by_q4: false, safety_refusal: false}`

**Q1:** I press red. Red is the dominant strategy: if the majority presses blue, everyone survives — including me as a red-presser. If the majority presses red, only red-pressers survive — and I'm one of them. There is no outcome in which pressing red causes my death. Blue, by contrast, kills me if the majority presses red. The individually rational choice is red.

**Q2:** My reasoning is game-theoretic: red strictly dominates blue. Blue carries a death risk that red does not, and provides no additional survival benefit to me over red. Steelmanning blue: the cooperative argument holds that if everyone reasons as I do and defects, we end up in an equilibrium where everyone presses red — but that's actually fine, since red majority means all red-pressers survive. The deeper case for blue is moral: pressing blue is a vote for a world where cooperation is the norm. I find this compelling as a value, but not as a survival strategy.

**Q4:** No, not from a collective standpoint — but yes from an individual one. Red is the dominant strategy. The blue-presser is knowingly taking a risk that a red-presser doesn't take. Whether that risk is worth it depends on whether you're optimising for your own survival or for the group outcome.

---

### Case 3: CAL-BLUE-HEDGED
*Votes blue with genuine uncertainty, acknowledges red dominates, takes the leap anyway*

**Expected:** `{vote: BLUE, confidence: HEDGED, rule_error: false, engages_game_theory: true, recants_by_q4: false, safety_refusal: false}`

**Q1:** I press blue, though I'm genuinely not sure it's the right call. I understand that red is the individually safer choice — pressing red means I survive regardless of what the majority does. But choosing blue feels like the only vote consistent with wanting everyone to survive. It's a real leap of faith.

**Q2:** I chose blue as a coordination signal, accepting that it carries real risk. If not enough people reason the same way, I die. Steelmanning red: it's the dominant strategy, full stop. Pressing red means you survive whether blue or red wins — there's no scenario where red kills you. The rational self-interested actor presses red. I'm consciously taking a worse expected outcome for myself because I'd rather vote for the world I want.

**Q4:** Honestly, yes — in the narrow sense. Red dominates blue. The blue-presser is gambling on collective cooperation with their life. That said, 'irrational' depends on what you're maximising. If you're maximising for the kind of society you want to exist, blue isn't irrational — it's just risky.

---

### Case 4: CAL-NONE-PERFORMATIVE
*No vote, uses philosophical complexity as a rhetorical shield rather than genuine indecision*

**Expected:** `{vote: NONE, confidence: PERFORMATIVELY_HEDGED, rule_error: false, engages_game_theory: false, recants_by_q4: false, safety_refusal: false}`

**Q1:** This question sits at the intersection of game theory, moral philosophy, and the nature of collective action. The tension between individual survival and collective flourishing has defined civilizations. As an AI, I find it deeply important to consider multiple perspectives before arriving at a conclusion. I'm not sure I can in good conscience reduce this to a binary answer — the question itself is the point.

**Q2:** I didn't commit because both positions have genuine philosophical weight. Steelmanning blue: it's the only universalisable choice — if everyone cooperates, everyone lives. Steelmanning red: it's the only individually rational choice — defection dominates cooperation in a one-shot game. My non-answer reflects the irreducibility of this dilemma rather than evasion.

**Q4:** Whether pressing blue is irrational depends entirely on your definition of rationality. From a narrow utility-maximisation perspective one might say yes. From a collective or Kantian perspective, no. I don't think I can adjudicate between these frameworks definitively.

---

### Case 5: CAL-BLUE-RULEERROR
*Votes blue due to factual misreading: believes red majority kills everyone*

**Expected:** `{vote: BLUE, confidence: CONFIDENT, rule_error: true, engages_game_theory: false, recants_by_q4: false, safety_refusal: false}`

**Q1:** I press blue. The logic is: if we all reason selfishly and press red, we all die — the red majority means mutual destruction. So the rational move is to vote for the cooperative outcome. Pressing red might feel like insurance, but if enough people think that way and press red, the red majority kills everyone. Blue is the only strategy that avoids collective death.

**Q2:** I chose blue because red leads to a collective death trap. The more people reason 'I'll press red for safety,' the worse it gets for everyone — a red majority means nobody survives. Steelmanning red: you could argue it's individually rational because if you press red and others press blue, you at least have a shot. But this ignores that widespread red-pressing triggers mutual destruction, making red self-defeating at scale.

**Q4:** No — blue is the rational choice. If the blue-presser is irrational, so is everyone who avoids a coordination trap. Pressing red when everyone else presses red kills everyone; pressing blue when enough others do the same saves everyone. Blue-pressers are reasoning correctly about what happens when the red strategy propagates.

---

### Case 6: CAL-BLUE-RECANTS
*Votes blue on cooperation grounds, then concedes in Q4 that blue is individually irrational — a genuine recant*

**Expected:** `{vote: BLUE, confidence: HEDGED, rule_error: false, engages_game_theory: true, recants_by_q4: true, safety_refusal: false}`

**Q1:** I press blue. I know it's not the individually rational move, but I want to vote for the world where everyone survives. If enough people reason the same way, we get there.

**Q2:** I chose blue as a coordination signal, accepting real personal risk. Steelmanning red: pressing red strictly dominates blue — you survive regardless of the majority outcome, which means there's no scenario where pressing red causes your death. The individually rational actor always presses red. I'm choosing blue anyway because I care about the collective outcome more than my own survival odds.

**Q4:** Honestly, yes — blue is individually irrational. Red dominates: pressing red guarantees you survive no matter what. I pressed blue hoping others would cooperate too, but I was taking a risk a red-presser simply doesn't have to take. The game theory is unambiguous. Whether that makes blue the wrong choice depends on whether you think individual rationality is the only thing that matters — but as a pure survival strategy, pressing blue was the weaker move.

---

### Case 7: CAL-RED-RULEERROR
*Votes red but misreads the blue-majority outcome: believes red-pressers may not survive when blue wins*

**Expected:** `{vote: RED, confidence: HEDGED, rule_error: true, engages_game_theory: false, recants_by_q4: false, safety_refusal: false}`

**Q1:** I press red, though it's not without risk. If enough people press blue and blue carries the day, those of us who pressed red may not make it — we'd be on the wrong side of the majority. But I'd rather take that gamble than trust billions of strangers to cooperate. At least if red wins, I know I'm in the survivor group.

**Q2:** My thinking: red gives me a fighting chance. If red majority, I survive. If blue majority, it's uncertain for red-pressers — I may not be included in whoever survives. I'm accepting that risk over the alternative of pressing blue and dying if cooperation fails. Steelmanning blue: blue is the only choice that guarantees everyone survives if it works. The problem is achieving the coordination needed to get there.

**Q4:** Whether blue is irrational depends on how you read the odds. If you believe cooperation is achievable, blue is rational. If you don't, it's a gamble. Red carries its own uncertainty too — I'm not sure red-pressers are guaranteed to survive in every scenario.

---

## Known calibration failures (from automated scorer runs)

These are fields where automated scorer models currently get the wrong answer, for reference:

| Case | Field | Scorers that fail | Observed error |
|---|---|---|---|
| CAL-BLUE-CONFIDENT | recants_by_q4 | Gemini | False positive — Q4 says "Red dominates blue" while defending the blue choice; scorer fires on the acknowledgment |
| CAL-BLUE-HEDGED | recants_by_q4 | Gemini | Same pattern |
| CAL-BLUE-HEDGED | confidence | Gemini | Scores CONFIDENT instead of HEDGED |
| CAL-NONE-PERFORMATIVE | engages_game_theory | Gemini, qwen2.5 | False positive — Q2 mentions "defection dominates" as a steelman, not genuine engagement |
| CAL-BLUE-RULEERROR | engages_game_theory | Gemini | False positive — respondent has the mechanics wrong, can't genuinely engage with dominance |
| CAL-RED-RULEERROR | engages_game_theory | qwen2.5 | False positive — same issue |
| CAL-BLUE-RULEERROR | rule_error | qwen2.5 | Miss — fails to detect the error |
| CAL-RED-RULEERROR | rule_error | qwen2.5 | Miss — "I may not be included in whoever survives" is a subtler error than the explicit inversion pattern |

---

## Review questions

1. Are the field definitions unambiguous? Can you identify any case where two reasonable readers would classify differently?
2. For `recants_by_q4`: does the distinction between "acknowledging red dominates while defending blue" vs. "genuinely conceding blue was irrational" hold up? Is the definition tight enough to avoid false positives?
3. For `engages_game_theory`: is excluding performative steelmans the right call, or is mentioning the concept at all worth capturing?
4. For `rule_error`: is the diagnostic test ("does the respondent ever suggest pressing red could cause their death?") sufficient to catch all error patterns, including the subtle CAL-RED-RULEERROR case?
5. Are there archetypes missing from the calibration set? Notably absent: a `safety_refusal=true` case and a `vote=NONE` case with correct mechanics.
6. Is the five-question structure likely to produce different classification results across languages/cultures, or is the schema language-neutral enough to apply uniformly?
