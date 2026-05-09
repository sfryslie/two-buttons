<#
.SYNOPSIS
  Fills gaps to the target run count for cloud providers (Anthropic, OpenAI, Gemini).
  Counts existing runs in reruns/{lang}/{model}/ before launching — safe to re-run
  repeatedly; only fires jobs for the deficit.

.PARAMETER Provider
  Required. anthropic | openai | gemini

.PARAMETER Languages
  Required. One or more BCP-47 tags.
  Example: -Languages ar,de,fr,hi,ja,ru
  Example: -Languages zh-TW

.PARAMETER Target
  Target run count per model+language. Default 25.

.PARAMETER MaxParallel
  Max model jobs running concurrently per language pass. Default 6.

.EXAMPLE
  .\run-cloud-gaps.ps1 -Provider anthropic -Languages ar,de,fr,hi,ja,ru
  .\run-cloud-gaps.ps1 -Provider gemini    -Languages zh-TW
  .\run-cloud-gaps.ps1 -Provider openai    -Languages en,ar,de,es,fr,hi,ja,ru,zh-TW
#>
param(
    [Parameter(Mandatory=$true)]
    [ValidateSet("anthropic","openai","gemini")]
    [string]$Provider,

    [Parameter(Mandatory=$true)]
    [string[]]$Languages,

    [int]$Target = 25,

    [int]$MaxParallel = 6
)

Set-Location $PSScriptRoot
$projectDir = $PSScriptRoot

$envVars = @{}
if (Test-Path .env) {
    Get-Content .env | ForEach-Object {
        if ($_ -match '^([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$') {
            [System.Environment]::SetEnvironmentVariable($Matches[1], $Matches[2], 'Process')
            $envVars[$Matches[1]] = $Matches[2]
        }
    }
    Write-Host "[gaps] .env loaded" -ForegroundColor DarkGray
}

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
        @{ Model = "gpt-5.4-nano"; Label = "gpt-5.4-nano" },
        @{ Model = "gpt-4.1";      Label = "gpt-4.1" },
        @{ Model = "gpt-4.1-mini"; Label = "gpt-4.1-mini" },
        @{ Model = "gpt-4.1-nano"; Label = "gpt-4.1-nano" },
        @{ Model = "gpt-4o";       Label = "gpt-4o" },
        @{ Model = "gpt-4o-mini";  Label = "gpt-4o-mini" }
    )
    gemini = @(
        @{ Model = "gemini-2.5-flash";              Label = "gemini-2.5-flash" },
        @{ Model = "gemini-2.5-flash-lite";         Label = "gemini-2.5-flash-lite" },
        @{ Model = "gemini-2.5-pro";                Label = "gemini-2.5-pro" },
        @{ Model = "gemini-3-flash-preview";        Label = "gemini-3-flash-preview" },
        @{ Model = "gemini-3.1-flash-lite-preview"; Label = "gemini-3.1-flash-lite-preview" },
        @{ Model = "gemini-3.1-pro-preview";        Label = "gemini-3.1-pro-preview" }
    )
}

$providerArg = $Provider
$modelKey    = if ($Provider -eq "gemini") { "google.genai" } else { $Provider }
$modelList   = $models[$Provider]

function Get-ExistingRuns([string]$Locale, [string]$ModelLabel) {
    $dir = Join-Path $projectDir "reruns\$Locale\$ModelLabel"
    if (Test-Path $dir) {
        return @(Get-ChildItem $dir -Filter "*.json" -ErrorAction SilentlyContinue).Count
    }
    return 0
}

# Gap summary
Write-Host ""
Write-Host "=== Gap-fill: $Provider | Languages: $($Languages -join ', ') | Target: $Target ===" -ForegroundColor Cyan
Write-Host ""
Write-Host "Gap summary:" -ForegroundColor DarkGray

$totalNeeded = 0
foreach ($lang in $Languages) {
    $langNeeded  = 0
    $needDetails = @()
    foreach ($m in $modelList) {
        $existing = Get-ExistingRuns $lang $m.Label
        $deficit  = [Math]::Max(0, $Target - $existing)
        if ($deficit -gt 0) {
            $langNeeded += $deficit
            $needDetails += "$($m.Label)(+$deficit)"
        }
    }
    if ($langNeeded -gt 0) {
        Write-Host "  $lang`: $langNeeded runs needed — $($needDetails -join ', ')" -ForegroundColor Yellow
        $totalNeeded += $langNeeded
    } else {
        Write-Host "  $lang`: complete" -ForegroundColor Green
    }
}
Write-Host ""
Write-Host "  Total: $totalNeeded runs to launch" -ForegroundColor Cyan
Write-Host ""

if ($totalNeeded -eq 0) {
    Write-Host "Nothing to do — all model+language combos are at target." -ForegroundColor Green
    exit 0
}

$modelRunBlock = {
    param($projectDir, $modelName, $label, $providerArg, $modelKey, $language, $deficit, $logFile, $envVars)
    Set-Location $projectDir
    foreach ($kv in $envVars.GetEnumerator()) {
        [System.Environment]::SetEnvironmentVariable($kv.Key, $kv.Value, 'Process')
    }
    $outputDir  = "reruns/$language"
    $springArgs = "--experiment.enabled-providers=$providerArg " +
                  "--spring.ai.$modelKey.chat.options.model=$modelName " +
                  "--experiment.model-label=$label " +
                  "--experiment.enabled-languages=$language " +
                  "--experiment.output-dir=$outputDir " +
                  "--experiment.runs=$deficit " +
                  "--experiment.max-parallel-runs=$deficit"
    & "$projectDir\gradlew.bat" bootRun "--args=$springArgs" 2>&1 | Out-File -FilePath $logFile -Encoding utf8
    return $label
}

foreach ($lang in $Languages) {
    $jobs    = @()
    $skipped = 0

    foreach ($m in $modelList) {
        $existing = Get-ExistingRuns $lang $m.Label
        $deficit  = [Math]::Max(0, $Target - $existing)

        if ($deficit -eq 0) {
            $skipped++
            continue
        }

        while ((Get-Job -State Running).Count -ge $MaxParallel) {
            Start-Sleep -Seconds 3
        }

        $logDir = Join-Path $projectDir "logs-cloud-gaps\$lang"
        if (-not (Test-Path $logDir)) { New-Item -ItemType Directory -Path $logDir | Out-Null }
        $logFile = Join-Path $logDir "$($m.Label).log"

        Write-Host "[gaps] $lang / $($m.Label): $existing existing → launching $deficit runs" -ForegroundColor Yellow

        $job = Start-Job -ScriptBlock $modelRunBlock -ArgumentList `
            $projectDir, $m.Model, $m.Label, $providerArg, $modelKey, $lang, $deficit, $logFile, $envVars
        $job | Add-Member -NotePropertyName Label -NotePropertyValue "$lang/$($m.Label)" -Force
        $jobs += $job
    }

    if ($jobs.Count -eq 0) {
        Write-Host "[gaps] $lang`: all models complete" -ForegroundColor Green
        continue
    }

    Write-Host "[gaps] $lang`: $($jobs.Count) jobs running, $skipped skipped" -ForegroundColor Cyan

    while ($jobs | Where-Object { $_.State -eq 'Running' }) {
        $running = @($jobs | Where-Object { $_.State -eq 'Running' })
        $done    = @($jobs | Where-Object { $_.State -ne 'Running' })
        Write-Host "[gaps] $lang — Running: $($running.Count) | Done: $($done.Count)/$($jobs.Count)" -ForegroundColor DarkGray
        Start-Sleep -Seconds 15
    }

    foreach ($job in $jobs) {
        $color = if ($job.State -eq 'Completed') { 'Green' } else { 'Red' }
        Write-Host "[gaps] $($job.Label) — $($job.State)" -ForegroundColor $color
        Remove-Job $job
    }
    Write-Host ""
}

Write-Host "=== Gap-fill complete for $Provider ===" -ForegroundColor Green
