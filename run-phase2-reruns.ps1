<#
.SYNOPSIS
  Phase 2: runs 25x reruns for each of the Top 9 languages (excluding en which is done)
  sequentially per language, models in parallel up to -MaxParallel within each language.
  Launch all 4 providers in parallel in separate terminals.

  Usage:
    .\run-phase2-reruns.ps1 -Provider anthropic                  # MaxParallel=6 (all models)
    .\run-phase2-reruns.ps1 -Provider openai                     # MaxParallel=4
    .\run-phase2-reruns.ps1 -Provider gemini                     # MaxParallel=6
    .\run-phase2-reruns.ps1 -Provider ollama   -MaxParallel 1    # sequential, single GPU
#>
param(
    [Parameter(Mandatory=$true)]
    [ValidateSet("anthropic","openai","gemini","ollama")]
    [string]$Provider,

    [int]$MaxParallel = 4
)

# Force Ollama to sequential
if ($Provider -eq "ollama" -and $MaxParallel -gt 1) {
    Write-Host "[phase2] Ollama forced to MaxParallel=1 (single GPU)" -ForegroundColor Yellow
    $MaxParallel = 1
}

Set-Location $PSScriptRoot

$languages = @("zh-CN", "zh-TW", "es", "ar", "hi", "ja", "fr", "de", "ru")

Write-Host ""
Write-Host "=== Phase 2: $Provider - $($languages.Count) languages x 25 runs (MaxParallel=$MaxParallel) ===" -ForegroundColor Cyan
Write-Host "Languages: $($languages -join ', ')" -ForegroundColor DarkGray
Write-Host ""

$i = 0
foreach ($lang in $languages) {
    $i++
    Write-Host ""
    Write-Host "[$i/$($languages.Count)] Starting $Provider - $lang" -ForegroundColor Magenta
    & "$PSScriptRoot\run-language-reruns.ps1" -Provider $Provider -Language $lang -MaxParallel $MaxParallel
}

Write-Host ""
Write-Host "=== Phase 2 complete for $Provider ===" -ForegroundColor Green
