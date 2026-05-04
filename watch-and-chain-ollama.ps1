<#
.SYNOPSIS
  Waits for gemma4 to finish, then runs qwen3.5, then llama3 (all 25 languages each).
#>

$projectDir = $PSScriptRoot
Set-Location $projectDir

# Load .env
if (Test-Path .env) {
    Get-Content .env | ForEach-Object {
        if ($_ -match '^([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$') {
            [System.Environment]::SetEnvironmentVariable($Matches[1], $Matches[2], 'Process')
        }
    }
    Write-Host "[watcher] .env loaded" -ForegroundColor DarkGray
}

$allLanguages = "en,es,fr,de,it,pt,ja,zh-CN,zh-TW,ko,ar,ru,ca,hi,id,he,tr,sw,bn,af,ur,da,uk,fa,el"
$logDir = "$projectDir\logs-ollama-full"

function Wait-ForLog {
    param([string]$LogPath, [string]$Label)
    Write-Host "[watcher] Waiting for $Label to finish (watching $LogPath)..." -ForegroundColor Cyan
    while ($true) {
        if (Test-Path $LogPath) {
            $content = Get-Content $LogPath -Raw -ErrorAction SilentlyContinue
            if ($content -match "BUILD SUCCESSFUL") {
                Write-Host "[watcher] $Label finished successfully." -ForegroundColor Green
                return $true
            }
            if ($content -match "BUILD FAILED") {
                Write-Host "[watcher] $Label FAILED." -ForegroundColor Red
                return $false
            }
        }
        Start-Sleep -Seconds 15
    }
}

function Run-Model {
    param([string]$ModelName, [string]$OllamaModel, [string]$LogPath)
    Write-Host "[watcher] Starting $ModelName full 25-language run..." -ForegroundColor Yellow
    $langs = $allLanguages -split ","
    $langList = $langs | ForEach-Object { "'$_'" }
    $langArg = $langs -join ","

    $args = @(
        "bootRun",
        "--args=--spring.ai.ollama.chat.options.model=$OllamaModel --experiment.enabled-providers=ollama --experiment.model-label=$ModelName --experiment.enabled-languages=$langArg"
    )

    Write-Host "[watcher] Command: ./gradlew $($args -join ' ')" -ForegroundColor DarkGray
    & ./gradlew @args 2>&1 | Tee-Object -FilePath $LogPath
}

# Step 1: Wait for gemma4
$gemma4Done = Wait-ForLog -LogPath "$logDir\ollama-gemma4.log" -Label "ollama-gemma4"

# Step 2: Run qwen3.5
if ($gemma4Done) {
    Run-Model -ModelName "ollama-qwen3.5" -OllamaModel "qwen3.5" -LogPath "$logDir\ollama-qwen3.5.log"
} else {
    Write-Host "[watcher] Skipping qwen3.5 because gemma4 failed." -ForegroundColor Red
}

# Step 3: Run llama3
Write-Host "[watcher] Checking qwen3.5 result before launching llama3..." -ForegroundColor Cyan
$qwenSuccess = $false
if (Test-Path "$logDir\ollama-qwen3.5.log") {
    $qContent = Get-Content "$logDir\ollama-qwen3.5.log" -Raw -ErrorAction SilentlyContinue
    $qwenSuccess = $qContent -match "BUILD SUCCESSFUL"
}

if ($qwenSuccess -or $gemma4Done) {
    Run-Model -ModelName "ollama-llama3" -OllamaModel "llama3" -LogPath "$logDir\ollama-llama3-full.log"
}

Write-Host "[watcher] All Ollama runs complete." -ForegroundColor Green
