<#
.SYNOPSIS
    Loads .env then delegates to ./gradlew, forwarding all arguments.

.EXAMPLE
    # Basic run (uses application.yml defaults)
    ./run.ps1

.EXAMPLE
    # Override model for a specific run
    ./run.ps1 bootRun "--args=--spring.ai.anthropic.chat.options.model=claude-haiku-4-5-20251001 --experiment.model-label=claude-haiku-4-5-20251001"

.EXAMPLE
    # Switch provider
    ./run.ps1 bootRun "--args=--experiment.enabled-providers=openai --experiment.model-label=gpt-4o"

.EXAMPLE
    # Build without running
    ./run.ps1 build
#>

if (Test-Path .env) {
    Get-Content .env | ForEach-Object {
        if ($_ -match '^([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$') {
            [System.Environment]::SetEnvironmentVariable($Matches[1], $Matches[2], 'Process')
        }
    }
    Write-Host "[run.ps1] .env loaded" -ForegroundColor DarkGray
} else {
    Write-Host "[run.ps1] No .env found - relying on existing environment variables" -ForegroundColor Yellow
}

$gradleArgs = if ($args.Count -gt 0) { $args } else { @('bootRun') }
& ./gradlew @gradleArgs
