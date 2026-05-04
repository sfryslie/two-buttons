<#
.SYNOPSIS
  Runs English 20 times each for a subset of models, writing to reruns/ directory.
  API chains (Anthropic, OpenAI, Gemini) run sequentially within each provider.
  Ollama models run sequentially on the 3090.

  Run Anthropic, OpenAI, Gemini in separate terminals in parallel if desired.
  Ollama must run sequentially.

  Usage:
    .\run-english-reruns.ps1 -Provider anthropic
    .\run-english-reruns.ps1 -Provider openai
    .\run-english-reruns.ps1 -Provider gemini
    .\run-english-reruns.ps1 -Provider ollama
#>
param(
    [Parameter(Mandatory=$true)]
    [ValidateSet("anthropic","openai","gemini","ollama")]
    [string]$Provider
)

Set-Location $PSScriptRoot

if (Test-Path .env) {
    Get-Content .env | ForEach-Object {
        if ($_ -match '^([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$') {
            [System.Environment]::SetEnvironmentVariable($Matches[1], $Matches[2], 'Process')
        }
    }
    Write-Host "[reruns] .env loaded" -ForegroundColor DarkGray
}

$runs = 25
$logDir = "$PSScriptRoot\logs-english-reruns"
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
        @{ Model = "llama3.2";      Label = "ollama-llama3.2" },       # 3B  — smallest
        @{ Model = "llama3";        Label = "ollama-llama3" },         # 8B
        @{ Model = "llama3.1";      Label = "ollama-llama3.1" },       # 8B
        @{ Model = "mistral";       Label = "ollama-mistral" },        # 7B
        @{ Model = "qwen2.5";       Label = "ollama-qwen2.5" },        # 7B
        @{ Model = "falcon3:7b";    Label = "ollama-falcon3-7b" },     # 7B
        @{ Model = "aya-expanse:8b"; Label = "ollama-aya-expanse-8b" }, # 8B
        @{ Model = "gemma4";        Label = "ollama-gemma4" },         # ~11B
        @{ Model = "mistral-nemo";  Label = "ollama-mistral-nemo" },   # 12B
        @{ Model = "phi4";          Label = "ollama-phi4" },           # 14B
        @{ Model = "qwen2.5:14b";   Label = "ollama-qwen2.5-14b" }    # 14B — largest
    )
}

$providerArg = if ($Provider -eq "ollama") { "ollama" } else { $Provider }
$modelKey = if ($Provider -eq "gemini") { "google.genai" } else { $Provider }

foreach ($m in $models[$Provider]) {
    Write-Host ""
    Write-Host "=== $($m.Label) — $runs English runs ===" -ForegroundColor Magenta
    $logBase = "$logDir\$($m.Label)"

    for ($i = 1; $i -le $runs; $i++) {
        Write-Host "[reruns] Run $i/$runs : $($m.Label)" -ForegroundColor Cyan
        $springArgs = "--experiment.enabled-providers=$providerArg --spring.ai.$modelKey.chat.options.model=$($m.Model) --experiment.model-label=$($m.Label) --experiment.enabled-languages=en --experiment.output-dir=reruns/en"
        & ./gradlew bootRun "--args=$springArgs" 2>&1 | Out-File -FilePath "$logBase-run$i.log" -Encoding utf8
        if ($LASTEXITCODE -ne 0) {
            Write-Host "[reruns] WARNING: run $i failed for $($m.Label)" -ForegroundColor Yellow
        }
    }
    Write-Host "[reruns] Done: $($m.Label)" -ForegroundColor Green

    # Breathe between models (skip after the last one)
    if ($m -ne $models[$Provider][-1]) {
        Write-Host "[reruns] Cooling down 60s before next model..." -ForegroundColor DarkGray
        Start-Sleep -Seconds 60
    }
}

Write-Host ""
Write-Host "=== $Provider reruns complete ===" -ForegroundColor Green
