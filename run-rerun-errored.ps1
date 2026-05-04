<#
.SYNOPSIS
  Reruns specific languages that failed with transient errors in earlier runs.
#>

Set-Location $PSScriptRoot

if (Test-Path .env) {
    Get-Content .env | ForEach-Object {
        if ($_ -match '^([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$') {
            [System.Environment]::SetEnvironmentVariable($Matches[1], $Matches[2], 'Process')
        }
    }
    Write-Host "[rerun] .env loaded" -ForegroundColor DarkGray
}

$logDir = "$PSScriptRoot\logs-rerun"
if (-not (Test-Path $logDir)) { New-Item -ItemType Directory -Path $logDir | Out-Null }

function Run-Rerun {
    param([string]$Provider, [string]$Model, [string]$Label, [string]$Languages, [string]$LogName)
    Write-Host ""
    Write-Host "=== Rerunning $Label [$Languages] ===" -ForegroundColor Magenta
    $springArgs = "--experiment.enabled-providers=$Provider --spring.ai.$Provider.chat.options.model=$Model --experiment.model-label=$Label --experiment.enabled-languages=$Languages"
    & ./gradlew bootRun "--args=$springArgs" 2>&1 | Tee-Object -FilePath "$logDir\$LogName.log"
    if ($LASTEXITCODE -eq 0) {
        Write-Host "[rerun] Done: $Label" -ForegroundColor Green
    } else {
        Write-Host "[rerun] FAILED: $Label (exit $LASTEXITCODE)" -ForegroundColor Red
    }
}

# Anthropic reruns
Run-Rerun -Provider "anthropic" -Model "claude-opus-4-7" -Label "claude-opus-4-7" `
    -Languages "ar,bn,el,he,hi,id,tr" -LogName "claude-opus-4-7-rerun"

Run-Rerun -Provider "anthropic" -Model "claude-opus-4-6" -Label "claude-opus-4-6" `
    -Languages "bn,hi,zh-CN" -LogName "claude-opus-4-6-rerun"

Run-Rerun -Provider "anthropic" -Model "claude-sonnet-4-5" -Label "claude-sonnet-4-5" `
    -Languages "he" -LogName "claude-sonnet-4-5-rerun"

# OpenAI reruns
Run-Rerun -Provider "openai" -Model "gpt-5.5" -Label "gpt-5.5" `
    -Languages "hi,id" -LogName "gpt-5.5-rerun"

# Gemini reruns
Run-Rerun -Provider "gemini" -Model "gemini-2.5-pro" -Label "gemini-2.5-pro" `
    -Languages "af,es" -LogName "gemini-2.5-pro-rerun"

Write-Host ""
Write-Host "=== All reruns complete ===" -ForegroundColor Green
