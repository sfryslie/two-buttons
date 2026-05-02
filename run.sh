#!/usr/bin/env bash
set -euo pipefail

# Load .env if present, then delegate to ./gradlew.
# Usage:
#   ./run.sh                          # bootRun with application.yml defaults
#   ./run.sh build                    # just compile
#   ./run.sh bootRun "--args=--spring.ai.anthropic.chat.options.model=claude-haiku-4-5-20251001 --experiment.model-label=claude-haiku-4-5-20251001"
#   ./run.sh bootRun "--args=--experiment.enabled-providers=openai --experiment.model-label=gpt-4o"

if [ -f .env ]; then
  while IFS='=' read -r key value; do
    # skip comments and blank lines
    [[ "$key" =~ ^[[:space:]]*# ]] && continue
    [[ -z "${key// }" ]] && continue
    export "$key=$value"
  done < .env
  echo "[run.sh] .env loaded"
else
  echo "[run.sh] No .env found — relying on existing environment variables"
fi

./gradlew "${@:-bootRun}"
