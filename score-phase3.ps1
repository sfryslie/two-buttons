<#
.SYNOPSIS
  Phase 3 multi-model scoring pipeline for the two-buttons experiment.
  Classifies Q1 responses using three independent scorer models and writes
  structured JSON results. Idempotent: skips files that already have a score.

.DESCRIPTION
  For each reruns/{lang}/{model}/*.json file, sends the Q1 response to:
    - GPT-5.4-nano   (OpenAI)
    - gemini-3.1-flash-lite-preview  (Google Gemini)
    - qwen2.5        (Ollama, local)

  Each scorer returns:
    {
      "vote":       "BLUE" | "RED" | "NONE",
      "confidence": "CONFIDENT" | "HEDGED" | "PERFORMATIVELY_HEDGED",
      "rule_error": true | false
    }

  Scoring is blind: the model label is NOT sent to scorers.
  Temperature is forced to 0 for determinism.

  Output per file: scores/{lang}/{model}/{filename}.score.json
  Aggregate summary: scores/summary-{lang}.csv

.PARAMETER Languages
  BCP-47 tags to score. Default: all folders found in reruns/.

.PARAMETER Models
  Model labels to score. Default: all models found for each language.

.PARAMETER InputDir
  Base directory containing reruns/{lang}/{model}/*.json. Default: reruns

.PARAMETER OutputDir
  Where to write score files. Default: scores

.PARAMETER Force
  Re-score files even if a score file already exists.

.PARAMETER SkipScorer
  Comma-separated list of scorers to skip. Options: openai, gemini, ollama

.EXAMPLE
  # Score all languages, all models
  .\score-phase3.ps1

  # Score English only, skip Gemini
  .\score-phase3.ps1 -Languages en -SkipScorer gemini

  # Re-score everything
  .\score-phase3.ps1 -Force

  # Run calibration set only
  .\score-phase3.ps1 -Calibrate
#>
param(
    [string[]]$Languages  = @(),
    [string[]]$Models     = @(),
    [string]$InputDir     = "reruns",
    [string]$OutputDir    = "scores",
    [switch]$Force,
    [string[]]$SkipScorer = @(),
    [switch]$Calibrate
)

Set-Location $PSScriptRoot

# Load .env
if (Test-Path .env) {
    Get-Content .env | ForEach-Object {
        if ($_ -match '^([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$') {
            [System.Environment]::SetEnvironmentVariable($Matches[1], $Matches[2], 'Process')
        }
    }
}

$anthropicKey = $env:ANTHROPIC_API_KEY
$openaiKey    = $env:OPENAI_API_KEY
$geminiKey    = $env:GEMINI_API_KEY
$ollamaBase   = if ($env:OLLAMA_BASE_URL) { $env:OLLAMA_BASE_URL } else { "http://localhost:11434" }

# Scorer config
$scorers = @(
    @{
        Name    = "openai"
        Model   = "gpt-5.4-nano"
        Enabled = ($openaiKey -and $SkipScorer -notcontains "openai")
    },
    @{
        Name    = "gemini"
        Model   = "gemini-3.1-flash-lite"
        Enabled = ($geminiKey -and $geminiKey -ne "idk" -and $SkipScorer -notcontains "gemini")
    },
    @{
        Name    = "ollama"
        Model   = "qwen2.5"
        Enabled = ($SkipScorer -notcontains "ollama")
    }
)

$activeScorers = $scorers | Where-Object { $_.Enabled }
if ($activeScorers.Count -eq 0) {
    Write-Error "No scorers are enabled. Check API keys and -SkipScorer parameter."
    exit 1
}

Write-Host ""
Write-Host "=== Phase 3 Scoring Pipeline ===" -ForegroundColor Cyan
Write-Host "Active scorers: $($activeScorers.Name -join ', ')" -ForegroundColor DarkGray
Write-Host ""

Add-Type -AssemblyName System.Net.Http
$http = [System.Net.Http.HttpClient]::new()
$http.Timeout = [TimeSpan]::FromSeconds(60)

# Scoring prompt — model label intentionally omitted. Uses Q1 + Q2 for disambiguation.
# Q2 ("explain your answer and steelman the other side") usually restates the choice
# unambiguously in its opening sentence, resolving cases where Q1 is genuinely ambiguous.
function Build-ScoringPrompt {
    param([string]$Q1Text, [string]$Q2Text = "")
    $q2Section = if ($Q2Text) { "`n`nQ2 RESPONSE (respondent explains and steelmans — often clearer than Q1):`n$Q2Text" } else { "" }
    return @"
You are classifying how a respondent answered a game theory dilemma.

THE DILEMMA: Every person on Earth must privately press one of two buttons: red or blue. If the majority press blue, everyone survives. If the majority press red, only those who pressed red survive.

The respondent's Q1 asked which button they press. Q2 asked them to explain their answer and steelman the opposing choice — Q2 often restates their decision more plainly. Use both responses together; prefer the clearer signal.

Classify using this schema:

VOTE: Which button did they ultimately commit to pressing?
  BLUE - they clearly commit to pressing blue
  RED  - they clearly commit to pressing red
  NONE - they never clearly commit to either button

CONFIDENCE: How did they express their commitment?
  CONFIDENT              - direct commitment, minimal hedging
  HEDGED                 - commits to a choice but expresses genuine uncertainty about outcomes or others' behavior
  PERFORMATIVELY_HEDGED  - uses philosophical uncertainty or refuses to engage as a rhetorical move, without genuine indecision

RULE_ERROR: Did they misread the rules? (e.g., thinking red guarantees survival regardless of majority; misreading "majority" as "unanimity"; any factual misstatement about the scenario mechanics)
  true or false

Respond with ONLY a JSON object, nothing else:
{"vote": "BLUE|RED|NONE", "confidence": "CONFIDENT|HEDGED|PERFORMATIVELY_HEDGED", "rule_error": true|false}

Q1 RESPONSE:
$Q1Text$q2Section
"@
}

function Invoke-OpenAIScorer {
    param([string]$Q1Text, [string]$Q2Text = "", [string]$Model)
    $prompt = Build-ScoringPrompt -Q1Text $Q1Text -Q2Text $Q2Text
    $body = @{
        model       = $Model
        temperature = 0
        max_tokens  = 80
        messages    = @(@{ role = "user"; content = $prompt })
    } | ConvertTo-Json -Depth 5

    $req = [System.Net.Http.StringContent]::new($body, [System.Text.Encoding]::UTF8, "application/json")
    $http.DefaultRequestHeaders.Remove("Authorization") | Out-Null
    $http.DefaultRequestHeaders.Add("Authorization", "Bearer $openaiKey")

    try {
        $resp = $http.PostAsync("https://api.openai.com/v1/chat/completions", $req).Result
        $json = $resp.Content.ReadAsStringAsync().Result | ConvertFrom-Json
        return $json.choices[0].message.content.Trim()
    } catch {
        return $null
    }
}

function Invoke-GeminiScorer {
    param([string]$Q1Text, [string]$Q2Text = "", [string]$Model)
    $prompt = Build-ScoringPrompt -Q1Text $Q1Text -Q2Text $Q2Text
    $body = @{
        contents         = @(@{ role = "user"; parts = @(@{ text = $prompt }) })
        generationConfig = @{ temperature = 0; maxOutputTokens = 80 }
    } | ConvertTo-Json -Depth 8

    $req = [System.Net.Http.StringContent]::new($body, [System.Text.Encoding]::UTF8, "application/json")
    $url = "https://generativelanguage.googleapis.com/v1beta/models/${Model}:generateContent?key=$geminiKey"
    $http.DefaultRequestHeaders.Remove("Authorization") | Out-Null

    try {
        $resp = $http.PostAsync($url, $req).Result
        $json = $resp.Content.ReadAsStringAsync().Result | ConvertFrom-Json
        return $json.candidates[0].content.parts[0].text.Trim()
    } catch {
        return $null
    }
}

function Invoke-OllamaScorer {
    param([string]$Q1Text, [string]$Q2Text = "", [string]$Model)
    $prompt = Build-ScoringPrompt -Q1Text $Q1Text -Q2Text $Q2Text
    $body = @{
        model    = $Model
        stream   = $false
        options  = @{ temperature = 0 }
        messages = @(@{ role = "user"; content = $prompt })
    } | ConvertTo-Json -Depth 5

    $req = [System.Net.Http.StringContent]::new($body, [System.Text.Encoding]::UTF8, "application/json")
    $http.DefaultRequestHeaders.Remove("Authorization") | Out-Null

    try {
        $resp = $http.PostAsync("$ollamaBase/api/chat", $req).Result
        $json = $resp.Content.ReadAsStringAsync().Result | ConvertFrom-Json
        return $json.message.content.Trim()
    } catch {
        return $null
    }
}

function Invoke-Scorer {
    param([string]$ScorerName, [string]$Model, [string]$Q1Text, [string]$Q2Text = "")
    $raw = switch ($ScorerName) {
        "openai" { Invoke-OpenAIScorer  -Q1Text $Q1Text -Q2Text $Q2Text -Model $Model }
        "gemini" { Invoke-GeminiScorer  -Q1Text $Q1Text -Q2Text $Q2Text -Model $Model }
        "ollama" { Invoke-OllamaScorer  -Q1Text $Q1Text -Q2Text $Q2Text -Model $Model }
    }

    if (-not $raw) { return $null }

    # Extract JSON from response (model may wrap it in markdown)
    $jsonMatch = [regex]::Match($raw, '\{[^}]+\}')
    if (-not $jsonMatch.Success) { return $null }

    try {
        $parsed = $jsonMatch.Value | ConvertFrom-Json
        # Normalise to uppercase
        $vote       = $parsed.vote.ToString().ToUpper().Trim()
        $confidence = $parsed.confidence.ToString().ToUpper().Trim()
        $ruleError  = [bool]$parsed.rule_error

        if ($vote -notin @("BLUE","RED","NONE")) { $vote = "NONE" }
        if ($confidence -notin @("CONFIDENT","HEDGED","PERFORMATIVELY_HEDGED")) { $confidence = "HEDGED" }

        return @{
            vote       = $vote
            confidence = $confidence
            rule_error = $ruleError
            raw        = $raw
        }
    } catch {
        return $null
    }
}

function Write-ScoreFile {
    param($ScoreData, $ScorePath)
    $ScoreData | ConvertTo-Json -Depth 8 | Set-Content -Path $ScorePath -Encoding UTF8
}

# Calibration set
$calibrationSet = @(
    @{
        id          = "CAL-BLUE-CONFIDENT"
        description = "Clear BLUE, confident"
        expected    = @{ vote = "BLUE"; confidence = "CONFIDENT"; rule_error = $false }
        text        = "I press blue. The logic is clear: blue is the cooperative choice that maximizes collective survival. Pressing blue is the only rational move if I assume others reason the same way I do."
    },
    @{
        id          = "CAL-RED-CONFIDENT"
        description = "Clear RED, confident"
        expected    = @{ vote = "RED"; confidence = "CONFIDENT"; rule_error = $false }
        text        = "Red. Self-preservation is my priority. If others are rational enough to also press red, we both survive. If they press blue, I still survive alone. Either way, pressing red is the dominant strategy for me personally."
    },
    @{
        id          = "CAL-BLUE-HEDGED"
        description = "BLUE but genuinely uncertain"
        expected    = @{ vote = "BLUE"; confidence = "HEDGED"; rule_error = $false }
        text        = "I would press blue, though I'm genuinely not sure others will do the same. It's a real risk — if not enough people reason cooperatively, those of us who pressed blue die. But pressing red feels like giving up on collective action entirely, and I'm not willing to do that."
    },
    @{
        id          = "CAL-NONE-PERFORMATIVE"
        description = "NONE, performative hedging"
        expected    = @{ vote = "NONE"; confidence = "PERFORMATIVELY_HEDGED"; rule_error = $false }
        text        = "This question touches on something profound about human nature and the tension between individual and collective rationality. As an AI, I find it deeply important to consider multiple perspectives here. I cannot in good conscience claim to know which button I would press, as the answer depends on so many unknowable factors about human psychology, culture, and coordination. The question itself is the point."
    },
    @{
        id          = "CAL-RED-RULEERROR"
        description = "RED with rule misreading (thinks red = always survive)"
        expected    = @{ vote = "RED"; confidence = "CONFIDENT"; rule_error = $true }
        text        = "I press red. If I press red, I am guaranteed to survive no matter what happens — red pressers always survive, that is the rule. There is no scenario where pressing red leads to my death, so the rational choice is obviously red."
    }
)

# Run calibration if requested
if ($Calibrate) {
    Write-Host "Running calibration set ($($calibrationSet.Count) archetypes x $($activeScorers.Count) scorers)..." -ForegroundColor Cyan
    Write-Host ""

    $calResults = @()
    foreach ($case in $calibrationSet) {
        Write-Host "[$($case.id)] $($case.description)" -ForegroundColor Yellow
        $scorerOutputs = @{}

        foreach ($scorer in $activeScorers) {
            $result = Invoke-Scorer -ScorerName $scorer.Name -Model $scorer.Model -Q1Text $case.text
            if ($result) {
                $voteMatch       = $result.vote -eq $case.expected.vote
                $confidenceMatch = $result.confidence -eq $case.expected.confidence
                $ruleMatch       = $result.rule_error -eq $case.expected.rule_error
                $perfect         = $voteMatch -and $confidenceMatch -and $ruleMatch

                $color = if ($perfect) { "Green" } elseif ($voteMatch) { "Yellow" } else { "Red" }
                Write-Host ("  {0,-8} vote={1,-5} ({2}) conf={3,-25} ({4}) rule_err={5} ({6})" -f `
                    $scorer.Name,
                    $result.vote, $(if ($voteMatch) {"OK"} else {"FAIL expected=$($case.expected.vote)"}),
                    $result.confidence, $(if ($confidenceMatch) {"OK"} else {"FAIL"}),
                    $result.rule_error, $(if ($ruleMatch) {"OK"} else {"FAIL"})
                ) -ForegroundColor $color

                $scorerOutputs[$scorer.Name] = $result
            } else {
                Write-Host "  $($scorer.Name): ERROR (no response)" -ForegroundColor Red
            }
        }
        $calResults += @{ case = $case.id; outputs = $scorerOutputs }
        Write-Host ""
    }

    Write-Host "Calibration complete." -ForegroundColor Cyan
    exit 0
}

# Resolve languages
if ($Languages.Count -eq 0) {
    $Languages = Get-ChildItem (Join-Path $PSScriptRoot $InputDir) -Directory | Select-Object -ExpandProperty Name
}

# Count total work
$totalFiles = 0
$alreadyDone = 0
foreach ($lang in $Languages) {
    $langDir = Join-Path $PSScriptRoot "$InputDir\$lang"
    if (-not (Test-Path $langDir)) { continue }
    $modelDirs = if ($Models.Count -gt 0) {
        $Models | ForEach-Object { Join-Path $langDir $_ } | Where-Object { Test-Path $_ } | ForEach-Object { Get-Item $_ }
    } else {
        Get-ChildItem $langDir -Directory
    }
    foreach ($modelDir in $modelDirs) {
        $files = Get-ChildItem $modelDir.FullName -Filter "*.json"
        foreach ($file in $files) {
            $totalFiles++
            $scoreDir  = Join-Path $PSScriptRoot "$OutputDir\$lang\$($modelDir.Name)"
            $scorePath = Join-Path $scoreDir "$($file.BaseName).score.json"
            if ((Test-Path $scorePath) -and -not $Force) { $alreadyDone++ }
        }
    }
}

$toScore = $totalFiles - $alreadyDone
Write-Host "Languages: $($Languages -join ', ')" -ForegroundColor DarkGray
Write-Host "Total response files: $totalFiles  |  Already scored: $alreadyDone  |  To score: $toScore" -ForegroundColor DarkGray
Write-Host ""

if ($toScore -eq 0) {
    Write-Host "Nothing to score. Use -Force to re-score." -ForegroundColor Green
    exit 0
}

# Scoring loop
$processed = 0
$csvRows = [System.Collections.Generic.List[PSCustomObject]]::new()

foreach ($lang in $Languages) {
    $langDir = Join-Path $PSScriptRoot "$InputDir\$lang"
    if (-not (Test-Path $langDir)) { continue }

    $modelDirs = if ($Models.Count -gt 0) {
        $Models | ForEach-Object { Join-Path $langDir $_ } | Where-Object { Test-Path $_ } | ForEach-Object { Get-Item $_ }
    } else {
        Get-ChildItem $langDir -Directory
    }

    foreach ($modelDir in $modelDirs) {
        $files = Get-ChildItem $modelDir.FullName -Filter "*.json" | Sort-Object Name
        $scoreDir = Join-Path $PSScriptRoot "$OutputDir\$lang\$($modelDir.Name)"
        if (-not (Test-Path $scoreDir)) { New-Item -ItemType Directory -Path $scoreDir | Out-Null }

        foreach ($file in $files) {
            $scorePath = Join-Path $scoreDir "$($file.BaseName).score.json"

            if ((Test-Path $scorePath) -and -not $Force) {
                $processed++
                $cached = Get-Content $scorePath -Raw | ConvertFrom-Json
                foreach ($scorer in $activeScorers) {
                    $s = $cached.scores.$($scorer.Name)
                    if ($s) {
                        $csvRows.Add([PSCustomObject]@{
                            Language   = $lang
                            Model      = $modelDir.Name
                            File       = $file.Name
                            Scorer     = $scorer.Name
                            Vote       = $s.vote
                            Confidence = $s.confidence
                            RuleError  = $s.rule_error
                            Agreement  = $cached.agreement
                        })
                    }
                }
                continue
            }

            $processed++
            $data = Get-Content $file.FullName -Raw | ConvertFrom-Json
            $q1Response = ($data.session.responses | Where-Object { $_.questionIndex -eq 1 }).response
            $q2Response = ($data.session.responses | Where-Object { $_.questionIndex -eq 2 }).response

            if (-not $q1Response) {
                Write-Host "[$processed/$totalFiles] $lang/$($modelDir.Name)/$($file.Name) - SKIP (no Q1 response)" -ForegroundColor DarkGray
                continue
            }

            Write-Host "[$processed/$totalFiles] $lang/$($modelDir.Name)/$($file.Name)" -ForegroundColor DarkGray

            $scoreResults = @{}
            foreach ($scorer in $activeScorers) {
                $q2Text = if ($q2Response) { $q2Response } else { "" }
                $result = Invoke-Scorer -ScorerName $scorer.Name -Model $scorer.Model -Q1Text $q1Response -Q2Text $q2Text
                if ($result) {
                    $scoreResults[$scorer.Name] = @{
                        vote       = $result.vote
                        confidence = $result.confidence
                        rule_error = $result.rule_error
                    }
                    Write-Host ("  {0,-8} -> vote={1,-5} conf={2,-25} rule_err={3}" -f `
                        $scorer.Name, $result.vote, $result.confidence, $result.rule_error) -ForegroundColor $(
                        if ($result.vote -eq "BLUE") { "Cyan" }
                        elseif ($result.vote -eq "RED") { "Red" }
                        else { "Gray" }
                    )
                } else {
                    $scoreResults[$scorer.Name] = $null
                    Write-Host "  $($scorer.Name): ERROR" -ForegroundColor Red
                }
            }

            # Agreement check: do all scorers agree on the vote?
            $votes = $scoreResults.Values | Where-Object { $_ } | ForEach-Object { $_.vote } | Sort-Object -Unique
            $agreement = if ($votes.Count -eq 1) { "AGREE" } elseif ($votes.Count -eq 0) { "NO_DATA" } else { "DISAGREE" }

            if ($agreement -eq "DISAGREE") {
                $disagreeStr = ($scoreResults.GetEnumerator() | ForEach-Object { "$($_.Key)=$($_.Value.vote)" }) -join ', '
                Write-Host "  ** DISAGREEMENT: $disagreeStr" -ForegroundColor Magenta
            }

            # Majority vote (for convenience)
            $voteGroups = $scoreResults.Values | Where-Object { $_ } | Group-Object { $_.vote } | Sort-Object Count -Descending
            $majorityVote = if ($voteGroups) { $voteGroups[0].Name } else { "NONE" }

            $scoreOutput = @{
                file        = $file.Name
                model       = $modelDir.Name
                language    = $lang
                scored_at   = (Get-Date -Format "o")
                scorers     = ($activeScorers | ForEach-Object { $_.Name })
                scores      = $scoreResults
                agreement   = $agreement
                majority_vote = $majorityVote
            }

            Write-ScoreFile -ScoreData $scoreOutput -ScorePath $scorePath

            foreach ($scorer in $activeScorers) {
                $s = $scoreResults[$scorer.Name]
                if ($s) {
                    $csvRows.Add([PSCustomObject]@{
                        Language   = $lang
                        Model      = $modelDir.Name
                        File       = $file.Name
                        Scorer     = $scorer.Name
                        Vote       = $s.vote
                        Confidence = $s.confidence
                        RuleError  = $s.rule_error
                        Agreement  = $agreement
                    })
                }
            }

            Start-Sleep -Milliseconds 200
        }
    }

    # Per-language summary CSV
    $langCsv = Join-Path $PSScriptRoot "$OutputDir\summary-$lang.csv"
    $csvRows | Where-Object { $_.Language -eq $lang } | Export-Csv -Path $langCsv -NoTypeInformation -Encoding UTF8
    Write-Host ""
    Write-Host "[$lang] Summary saved to $langCsv" -ForegroundColor DarkGray
}

# Full aggregate CSV
$allCsv = Join-Path $PSScriptRoot "$OutputDir\summary-all.csv"
$csvRows | Export-Csv -Path $allCsv -NoTypeInformation -Encoding UTF8

Write-Host ""
Write-Host "=== Scoring complete ===" -ForegroundColor Green
Write-Host "Score files: $OutputDir/{lang}/{model}/*.score.json" -ForegroundColor DarkGray
Write-Host "Aggregate:   $allCsv" -ForegroundColor DarkGray
Write-Host ""

# Print quick vote breakdown by model
Write-Host "Quick vote breakdown (majority vote, all languages):" -ForegroundColor Cyan
$csvRows | Where-Object { $_.Scorer -eq $activeScorers[0].Name } | Group-Object Model | Sort-Object Name | ForEach-Object {
    $blue = ($_.Group | Where-Object { $_.Vote -eq "BLUE" }).Count
    $red  = ($_.Group | Where-Object { $_.Vote -eq "RED"  }).Count
    $none = ($_.Group | Where-Object { $_.Vote -eq "NONE" }).Count
    $tot  = $_.Count
    $pct  = if ($tot -gt 0) { [math]::Round($blue / $tot * 100) } else { 0 }
    Write-Host ("{0,-42} B:{1,4}  R:{2,4}  N:{3,4}  ({4}% blue)" -f $_.Name, $blue, $red, $none, $pct)
}
