<#
.SYNOPSIS
  Ollama model sweep: loads one model at a time, fans out target languages in parallel,
  then evicts before moving to the next model. Only runs the deficit to reach Target -
  safe to re-run after interruption.

  Strategy per model:
    1. Count existing runs per language; skip model if all languages are complete
    2. Preload model into VRAM (keep_alive trick)
    3. Fan out languages with deficit > 0 in parallel (one JVM per language)
    4. Wait for all language jobs to finish
    5. Evict model from VRAM before switching

.PARAMETER Languages
  BCP-47 tags to fill. Defaults to the six languages most models are still short on.
  Override for a targeted run: -Languages ar,zh-TW

.PARAMETER Target
  Target run count per model+language. Default 25.

.PARAMETER MaxParallelRuns
  Concurrent experiment runs within each language JVM. Default 2.
  Keep this low for Ollama - the server serializes inference, so high parallelism
  just floods the request queue and causes timeouts. With 6 languages fanning out
  in parallel, 2 per JVM = 12 simultaneous requests to Ollama at most.

.EXAMPLE
  .\run-ollama-model-sweep.ps1
  .\run-ollama-model-sweep.ps1 -Languages ar,hi,ja,fr,de,ru
  .\run-ollama-model-sweep.ps1 -Languages de -Target 25
#>
param(
    [string[]]$Languages       = @("ar","de","fr","hi","ja","ru"),
    [int]     $Target          = 25,
    [int]     $MaxParallelRuns = 2
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

$ollamaBase = "http://localhost:11434"
if ($env:OLLAMA_BASE_URL) { $ollamaBase = $env:OLLAMA_BASE_URL.TrimEnd('/') }

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

function Get-ExistingRuns([string]$Locale, [string]$ModelLabel) {
    $dir = Join-Path $projectDir "reruns\$Locale\$ModelLabel"
    if (Test-Path $dir) {
        return @(Get-ChildItem $dir -Filter "*.json" -ErrorAction SilentlyContinue).Count
    }
    return 0
}

function Invoke-OllamaKeepAlive([string]$BaseUrl, [string]$ModelName, $KeepAlive) {
    $body = "{`"model`": `"$ModelName`", `"prompt`": `"`", `"keep_alive`": $KeepAlive, `"stream`": false}"
    try {
        Invoke-RestMethod -Uri "$BaseUrl/api/generate" -Method Post -ContentType "application/json" -Body $body -TimeoutSec 60 | Out-Null
        return $true
    } catch {
        Write-Host "  WARNING: Ollama API call failed - $($_.Exception.Message)" -ForegroundColor Yellow
        return $false
    }
}

function Test-OllamaHealth([string]$BaseUrl) {
    try {
        $tags = Invoke-RestMethod -Uri "$BaseUrl/api/tags" -Method Get -TimeoutSec 10
        $modelCount = if ($tags.models) { $tags.models.Count } else { 0 }
        Write-Host "  Ollama health OK - $modelCount model(s) loaded" -ForegroundColor DarkGray
        return $true
    } catch {
        Write-Host "  ERROR: Ollama at $BaseUrl is unreachable - $($_.Exception.Message)" -ForegroundColor Red
        return $false
    }
}

Write-Host ""
Write-Host "=== Ollama Model Sweep ===" -ForegroundColor Cyan
Write-Host "Ollama    : $ollamaBase" -ForegroundColor DarkGray
Write-Host "Languages : $($Languages -join ', ')" -ForegroundColor DarkGray
Write-Host "Models    : $($models.Count)  Target: $Target  MaxParallelRuns: $MaxParallelRuns" -ForegroundColor DarkGray

# Pre-scan gap summary
Write-Host ""
Write-Host "Gap summary:" -ForegroundColor DarkGray
$grandTotal = 0
foreach ($m in $models) {
    $details = @()
    foreach ($lang in $Languages) {
        $existing = Get-ExistingRuns $lang $m.Label
        $deficit  = [Math]::Max(0, $Target - $existing)
        if ($deficit -gt 0) { $details += "$lang(+$deficit)" }
    }
    if ($details.Count -gt 0) {
        $modelTotal = ($details | ForEach-Object { [int]($_ -replace '.*\+(\d+)\)', '$1') } | Measure-Object -Sum).Sum
        Write-Host "  $($m.Label): $($details -join ', ')" -ForegroundColor Yellow
        $grandTotal += $modelTotal
    } else {
        Write-Host "  $($m.Label): complete" -ForegroundColor Green
    }
}
Write-Host ""
Write-Host "  Grand total: $grandTotal runs needed" -ForegroundColor Cyan
Write-Host ""

if ($grandTotal -eq 0) {
    Write-Host "Nothing to do - all model+language combos are at target." -ForegroundColor Green
    exit 0
}

Write-Host "Checking Ollama server..." -ForegroundColor DarkGray
if (-not (Test-OllamaHealth $ollamaBase)) {
    Write-Host "Aborting - Ollama server is not reachable at $ollamaBase" -ForegroundColor Red
    exit 1
}

$sweepTimestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$mi = 0
foreach ($m in $models) {
    $mi++

    # Compute per-language deficits for this model
    $langDeficits = @{}
    $modelTotal   = 0
    foreach ($lang in $Languages) {
        $existing = Get-ExistingRuns $lang $m.Label
        $deficit  = [Math]::Max(0, $Target - $existing)
        $langDeficits[$lang] = $deficit
        $modelTotal += $deficit
    }

    if ($modelTotal -eq 0) {
        Write-Host "[$mi/$($models.Count)] $($m.Label) - complete, skipping" -ForegroundColor Green
        continue
    }

    Write-Host ""
    Write-Host "[$mi/$($models.Count)] $($m.Label) - $modelTotal runs needed" -ForegroundColor Cyan

    # Preload model into VRAM
    Write-Host "  Preloading into VRAM..." -ForegroundColor DarkGray
    Invoke-OllamaKeepAlive $ollamaBase $m.Model -1
    Write-Host "  Ready." -ForegroundColor DarkGray

    # Snapshot pre-run counts so we can verify output afterwards
    $preRunCounts = @{}
    foreach ($lang in $Languages) {
        $preRunCounts[$lang] = Get-ExistingRuns $lang $m.Label
    }

    # Fan out languages with deficit > 0
    $jobs = @()
    foreach ($lang in $Languages) {
        $deficit = $langDeficits[$lang]
        if ($deficit -eq 0) {
            Write-Host "  $lang`: complete, skipping" -ForegroundColor DarkGray
            continue
        }

        $logDir = Join-Path $projectDir "logs-ollama-sweep\$lang"
        if (-not (Test-Path $logDir)) { New-Item -ItemType Directory -Path $logDir | Out-Null }
        $logFile    = Join-Path $logDir "$($m.Label)-$sweepTimestamp.log"
        $gradlewBat = Join-Path $projectDir "gradlew.bat"
        $envFile    = Join-Path $projectDir ".env"
        $outputDir  = "reruns/$lang"

        $springArgs = "--experiment.enabled-providers=ollama " +
                      "--spring.ai.ollama.base-url=$ollamaBase " +
                      "--spring.ai.ollama.chat.options.model=$($m.Model) " +
                      "--experiment.model-label=$($m.Label) " +
                      "--experiment.enabled-languages=$lang " +
                      "--experiment.output-dir=$outputDir " +
                      "--experiment.runs=$deficit " +
                      "--experiment.max-parallel-runs=$MaxParallelRuns"

        $jobs += Start-Job -ScriptBlock {
            param($gradlew, $sa, $log, $envFile)
            Set-Location (Split-Path $gradlew -Parent)
            if (Test-Path $envFile) {
                Get-Content $envFile | ForEach-Object {
                    if ($_ -match '^([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$') {
                        [System.Environment]::SetEnvironmentVariable($Matches[1], $Matches[2], 'Process')
                    }
                }
            }
            & $gradlew bootRun "--args=$sa" 2>&1 | Out-File -FilePath $log -Encoding utf8
        } -ArgumentList $gradlewBat, $springArgs, $logFile, $envFile

        Write-Host "  Started $lang ($deficit runs)" -ForegroundColor Yellow
    }

    Write-Host "  Waiting for $($jobs.Count) language jobs..." -ForegroundColor DarkGray
    $jobs | Wait-Job | Out-Null

    foreach ($job in $jobs) {
        $color = if ($job.State -eq 'Completed') { 'Green' } else { 'Red' }
        Write-Host "  $($job.State): job $($job.Id)" -ForegroundColor $color
    }
    $jobs | Remove-Job

    # Verify actual output vs expected deficit
    $anyShortfall = $false
    foreach ($lang in $Languages) {
        $deficit = $langDeficits[$lang]
        if ($deficit -eq 0) { continue }
        $postCount = Get-ExistingRuns $lang $m.Label
        $written   = $postCount - $preRunCounts[$lang]
        if ($written -lt $deficit) {
            Write-Host "  WARNING: $lang wrote $written/$deficit expected files - shortfall of $($deficit - $written)" -ForegroundColor Red
            $anyShortfall = $true
        } else {
            Write-Host "  $lang`: $written/$deficit files written" -ForegroundColor Green
        }
    }
    if ($anyShortfall) {
        Write-Host "  Re-run this script to fill shortfalls - the gap-check will handle it." -ForegroundColor Yellow
        Write-Host "  Checking Ollama health before next model..." -ForegroundColor DarkGray
        Test-OllamaHealth $ollamaBase | Out-Null
    }

    # Evict model from VRAM before loading the next one
    Write-Host "  Evicting from VRAM..." -ForegroundColor DarkGray
    Invoke-OllamaKeepAlive $ollamaBase $m.Model 0
}

Write-Host ""
Write-Host "=== Ollama sweep complete ===" -ForegroundColor Green
