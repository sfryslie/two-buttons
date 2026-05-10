<#
.SYNOPSIS
  Pulls and runs all 9 Ollama models sequentially, full 25 languages each.
#>

Set-Location $PSScriptRoot

# Load .env
if (Test-Path .env) {
    Get-Content .env | ForEach-Object {
        if ($_ -match '^([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$') {
            [System.Environment]::SetEnvironmentVariable($Matches[1], $Matches[2], 'Process')
        }
    }
    Write-Host "[chain] .env loaded" -ForegroundColor DarkGray
}

$ollamaBase = "http://10.0.0.123:11434"
$allLanguages = "en,es,fr,de,it,pt,ja,zh-CN,zh-TW,ko,ar,ru,ca,hi,id,he,tr,sw,bn,af,ur,da,uk,fa,el"
$logDir = "$PSScriptRoot\logs-ollama-full"

if (-not (Test-Path $logDir)) { New-Item -ItemType Directory -Path $logDir | Out-Null }

$models = @(
    @{ OllamaModel = "qwen2.5";        Label = "ollama-qwen2.5" },
    @{ OllamaModel = "qwen2.5:14b";    Label = "ollama-qwen2.5-14b" },
    @{ OllamaModel = "llama3.1";       Label = "ollama-llama3.1" },
    @{ OllamaModel = "llama3.2";       Label = "ollama-llama3.2" },
    @{ OllamaModel = "mistral";        Label = "ollama-mistral" },
    @{ OllamaModel = "mistral-nemo";   Label = "ollama-mistral-nemo" },
    @{ OllamaModel = "phi4";           Label = "ollama-phi4" },
    @{ OllamaModel = "aya-expanse:8b"; Label = "ollama-aya-expanse-8b" },
    @{ OllamaModel = "falcon3:7b";     Label = "ollama-falcon3-7b" }
)

function Pull-Model {
    param([string]$ModelName)
    Write-Host "[chain] Pulling $ModelName from $ollamaBase..." -ForegroundColor Cyan
    try {
        $body = "{`"name`":`"$ModelName`"}"
        # Stream the pull — Ollama returns newline-delimited JSON; we just wait for completion
        $response = Invoke-WebRequest -Uri "$ollamaBase/api/pull" -Method Post `
            -Body $body -ContentType "application/json" -TimeoutSec 1800
        Write-Host "[chain] Pull complete: $ModelName" -ForegroundColor Green
        return $true
    } catch {
        Write-Host "[chain] Pull failed for $ModelName`: $_" -ForegroundColor Red
        return $false
    }
}

function Run-Model {
    param([string]$OllamaModel, [string]$Label, [string]$LogPath)
    Write-Host "[chain] Starting experiment: $Label ($OllamaModel)" -ForegroundColor Yellow
    $springArgs = "--spring.ai.ollama.chat.options.model=$OllamaModel --experiment.enabled-providers=ollama --experiment.model-label=$Label --experiment.enabled-languages=$allLanguages"
    & ./gradlew bootRun "--args=$springArgs" 2>&1 | Tee-Object -FilePath $LogPath
    $success = $LASTEXITCODE -eq 0
    if ($success) {
        Write-Host "[chain] Finished: $Label" -ForegroundColor Green
    } else {
        Write-Host "[chain] FAILED: $Label (exit $LASTEXITCODE)" -ForegroundColor Red
    }
    return $success
}

# Main loop
$total = $models.Count
$i = 0
foreach ($m in $models) {
    $i++
    Write-Host ""
    Write-Host "=== [$i/$total] $($m.Label) ===" -ForegroundColor Magenta
    $logPath = "$logDir\$($m.Label).log"

    $pulled = Pull-Model -ModelName $m.OllamaModel
    if (-not $pulled) {
        Write-Host "[chain] Skipping $($m.Label) due to pull failure." -ForegroundColor Red
        continue
    }

    Run-Model -OllamaModel $m.OllamaModel -Label $m.Label -LogPath $logPath
}

Write-Host ""
Write-Host "=== All done ===" -ForegroundColor Green
