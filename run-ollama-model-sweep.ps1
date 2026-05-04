<#
.SYNOPSIS
  Ollama model sweep: loads one model at a time and fans out all target languages
  in parallel, then evicts before moving to the next model.

  For each model:
    1. Preload model into VRAM via Ollama API (empty-prompt keep_alive trick)
    2. Launch one JVM per language in parallel (Start-Job)
    3. Wait for ALL language jobs to finish (Wait-Job)
    4. Evict model from VRAM before switching

  This keeps a single model resident throughout its runs and avoids mid-run
  model switches stomping on each other.

  Usage:
    .\run-ollama-model-sweep.ps1
    .\run-ollama-model-sweep.ps1 -Languages ar,hi,ja        # subset
    .\run-ollama-model-sweep.ps1 -MaxParallelRuns 10        # tune concurrency
#>
param(
    [string[]]$Languages       = @("ar","hi","ja","fr","de","ru"),
    [int]     $Runs            = 25,
    [int]     $MaxParallelRuns = 25
)

Set-Location $PSScriptRoot
$projectDir = $PSScriptRoot

if (Test-Path .env) {
    Get-Content .env | ForEach-Object {
        if ($_ -match '^([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$') {
            [System.Environment]::SetEnvironmentVariable($Matches[1], $Matches[2], 'Process')
        }
    }
    Write-Host "[sweep] .env loaded" -ForegroundColor DarkGray
}

$ollamaBase = if ($env:OLLAMA_BASE_URL) { $env:OLLAMA_BASE_URL.TrimEnd('/') } else { "http://localhost:11434" }

$models = @(
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

Write-Host ""
Write-Host "=== Ollama Model Sweep ===" -ForegroundColor Cyan
Write-Host "Ollama    : $ollamaBase" -ForegroundColor DarkGray
Write-Host "Languages : $($Languages -join ', ')" -ForegroundColor DarkGray
Write-Host "Models    : $($models.Count)" -ForegroundColor DarkGray
Write-Host "Runs/lang : $Runs  MaxParallel: $MaxParallelRuns" -ForegroundColor DarkGray
Write-Host ""

function Invoke-OllamaKeepAlive($baseUrl, $modelName, $keepAlive) {
    $body = "{`"model`": `"$modelName`", `"prompt`": `"`", `"keep_alive`": $keepAlive, `"stream`": false}"
    try {
        Invoke-RestMethod -Uri "$baseUrl/api/generate" `
            -Method Post -ContentType "application/json" `
            -Body $body -TimeoutSec 60 | Out-Null
    } catch {
        Write-Host "  WARNING: Ollama API call failed — $($_.Exception.Message)" -ForegroundColor Yellow
    }
}

$mi = 0
foreach ($m in $models) {
    $mi++
    Write-Host "[$mi/$($models.Count)] $($m.Label)" -ForegroundColor Cyan

    # 1. Preload model into VRAM
    Write-Host "  Preloading..." -ForegroundColor DarkGray
    Invoke-OllamaKeepAlive $ollamaBase $m.Model -1
    Write-Host "  Ready." -ForegroundColor DarkGray

    # 2. Fan out all languages in parallel — one JVM per language
    $jobs = @()
    foreach ($lang in $Languages) {
        $logDir = "$projectDir\logs-ollama-sweep\$lang"
        if (-not (Test-Path $logDir)) { New-Item -ItemType Directory -Path $logDir | Out-Null }

        $springArgs = "--experiment.enabled-providers=ollama " +
                      "--spring.ai.ollama.chat.options.model=$($m.Model) " +
                      "--experiment.model-label=$($m.Label) " +
                      "--experiment.enabled-languages=$lang " +
                      "--experiment.output-dir=reruns/$lang " +
                      "--experiment.runs=$Runs " +
                      "--experiment.max-parallel-runs=$MaxParallelRuns"

        $logFile   = "$logDir\$($m.Label).log"
        $gradlewBat = "$projectDir\gradlew.bat"

        $jobs += Start-Job -ScriptBlock {
            param($gradlew, $sa, $log)
            & $gradlew bootRun "--args=$sa" 2>&1 | Out-File -FilePath $log -Encoding utf8
        } -ArgumentList $gradlewBat, $springArgs, $logFile

        Write-Host "  Started $lang" -ForegroundColor Yellow
    }

    # 3. Wait for all languages to finish before touching the model
    Write-Host "  Waiting for $($jobs.Count) jobs..." -ForegroundColor DarkGray
    $jobs | Wait-Job | Out-Null
    $jobs | Remove-Job
    Write-Host "  All done." -ForegroundColor Green

    # 4. Evict model from VRAM before loading the next one
    Write-Host "  Evicting..." -ForegroundColor DarkGray
    Invoke-OllamaKeepAlive $ollamaBase $m.Model 0
    Write-Host ""
}

Write-Host "=== Ollama sweep complete ===" -ForegroundColor Green
