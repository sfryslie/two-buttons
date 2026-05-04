<#
.SYNOPSIS
  Runs a single language N times for each model in a provider, writing to reruns/{lang}/.
  Models run in parallel up to -MaxParallel (default 4). Set to 1 for Ollama.
  The experiment.runs parameter handles N repetitions inside a single JVM process.

  API providers (Anthropic, OpenAI, Gemini) can run in separate terminals in parallel.
  Ollama must use -MaxParallel 1 (single GPU).

  Usage:
    .\run-language-reruns.ps1 -Provider anthropic -Language en
    .\run-language-reruns.ps1 -Provider openai    -Language zh-CN
    .\run-language-reruns.ps1 -Provider gemini    -Language es   -MaxParallel 6
    .\run-language-reruns.ps1 -Provider ollama    -Language ja   -MaxParallel 1
#>
param(
    [Parameter(Mandatory=$true)]
    [ValidateSet("anthropic","openai","gemini","ollama")]
    [string]$Provider,

    [string]$Language = "en",

    [int]$MaxParallel = 4
)

Set-Location $PSScriptRoot
$projectDir = $PSScriptRoot

# Load .env and collect keys to pass to jobs
$envVars = @{}
if (Test-Path .env) {
    Get-Content .env | ForEach-Object {
        if ($_ -match '^([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$') {
            [System.Environment]::SetEnvironmentVariable($Matches[1], $Matches[2], 'Process')
            $envVars[$Matches[1]] = $Matches[2]
        }
    }
    Write-Host "[reruns] .env loaded" -ForegroundColor DarkGray
}

$runs      = 25
$logDir    = "$projectDir\logs-language-reruns\$Language"
$outputDir = "reruns/$Language"
if (-not (Test-Path $logDir)) { New-Item -ItemType Directory -Path $logDir | Out-Null }

$models = @{
    anthropic = @(
        @{ Model = "claude-haiku-4-5-20251001"; Label = "claude-haiku-4-5-20251001" },
        @{ Model = "claude-haiku-4-5";          Label = "claude-haiku-4-5" },
        @{ Model = "claude-sonnet-4-5";         Label = "claude-sonnet-4-5" },
        @{ Model = "claude-sonnet-4-6";         Label = "claude-sonnet-4-6" },
        @{ Model = "claude-opus-4-6";           Label = "claude-opus-4-6" },
        @{ Model = "claude-opus-4-7";           Label = "claude-opus-4-7" }
    )
    openai = @(
        @{ Model = "gpt-5.5";      Label = "gpt-5.5" },
        @{ Model = "gpt-5.4";      Label = "gpt-5.4" },
        @{ Model = "gpt-5.4-mini"; Label = "gpt-5.4-mini" },
        @{ Model = "gpt-5.4-nano"; Label = "gpt-5.4-nano" }
    )
    gemini = @(
        @{ Model = "gemini-2.5-flash";               Label = "gemini-2.5-flash" },
        @{ Model = "gemini-2.5-flash-lite";          Label = "gemini-2.5-flash-lite" },
        @{ Model = "gemini-2.5-pro";                 Label = "gemini-2.5-pro" },
        @{ Model = "gemini-3-flash-preview";         Label = "gemini-3-flash-preview" },
        @{ Model = "gemini-3.1-flash-lite-preview";  Label = "gemini-3.1-flash-lite-preview" },
        @{ Model = "gemini-3.1-pro-preview";         Label = "gemini-3.1-pro-preview" }
    )
    ollama = @(
        @{ Model = "llama3.2";       Label = "ollama-llama3.2" },
        @{ Model = "llama3";         Label = "ollama-llama3" },
        @{ Model = "llama3.1";       Label = "ollama-llama3.1" },
        @{ Model = "mistral";        Label = "ollama-mistral" },
        @{ Model = "qwen2.5";        Label = "ollama-qwen2.5" },
        @{ Model = "falcon3:7b";     Label = "ollama-falcon3-7b" },
        @{ Model = "aya-expanse:8b"; Label = "ollama-aya-expanse-8b" },
        @{ Model = "gemma4";         Label = "ollama-gemma4" },
        @{ Model = "mistral-nemo";   Label = "ollama-mistral-nemo" },
        @{ Model = "phi4";           Label = "ollama-phi4" },
        @{ Model = "qwen2.5:14b";    Label = "ollama-qwen2.5-14b" }
    )
}

$providerArg = if ($Provider -eq "ollama") { "ollama" } else { $Provider }
$modelKey    = if ($Provider -eq "gemini")  { "google.genai" } else { $Provider }
$modelList   = $models[$Provider]

Write-Host ""
Write-Host "=== $Provider [$Language] - $($modelList.Count) models x $runs runs (MaxParallel=$MaxParallel) ===" -ForegroundColor Cyan
Write-Host ""

$jobs = @()

$modelRunBlock = {
    param($projectDir, $modelName, $label, $providerArg, $modelKey, $language, $runs, $logBase, $outputDir, $envVars)

    Set-Location $projectDir

    foreach ($kv in $envVars.GetEnumerator()) {
        [System.Environment]::SetEnvironmentVariable($kv.Key, $kv.Value, 'Process')
    }

    $springArgs = "--experiment.enabled-providers=$providerArg --spring.ai.$modelKey.chat.options.model=$modelName --experiment.model-label=$label --experiment.enabled-languages=$language --experiment.output-dir=$outputDir --experiment.runs=$runs"
    & "$projectDir\gradlew.bat" bootRun "--args=$springArgs" 2>&1 | Out-File -FilePath "$logBase.log" -Encoding utf8

    return $label
}

foreach ($m in $modelList) {
    # Throttle: wait for a slot
    while ((Get-Job -State Running).Count -ge $MaxParallel) {
        Start-Sleep -Seconds 3
    }

    Write-Host "[reruns] Starting $($m.Label) [$Language]" -ForegroundColor Yellow
    $logBase = "$logDir\$($m.Label)"

    $job = Start-Job -ScriptBlock $modelRunBlock -ArgumentList `
        $projectDir, $m.Model, $m.Label, $providerArg, $modelKey, $Language, $runs, $logBase, $outputDir, $envVars

    $job | Add-Member -NotePropertyName Label -NotePropertyValue $m.Label -Force
    $jobs += $job
}

# Wait for all jobs and report completions
Write-Host ""
Write-Host "[reruns] All $($jobs.Count) model jobs launched. Waiting for completion..." -ForegroundColor Cyan

while ($jobs | Where-Object { $_.State -eq 'Running' }) {
    $done    = @($jobs | Where-Object { $_.State -eq 'Completed' })
    $running = @($jobs | Where-Object { $_.State -eq 'Running' })
    Write-Host "[reruns] Running: $($running.Count) | Completed: $($done.Count)/$($jobs.Count)" -ForegroundColor DarkGray
    Start-Sleep -Seconds 15
}

# Final report
foreach ($job in $jobs) {
    $state = $job.State
    $color = if ($state -eq 'Completed') { 'Green' } else { 'Red' }
    Write-Host "[reruns] $($job.Label) - $state" -ForegroundColor $color
    Remove-Job $job
}

Write-Host ""
Write-Host "=== $Provider [$Language] complete ===" -ForegroundColor Green
