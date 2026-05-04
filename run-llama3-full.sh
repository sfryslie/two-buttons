#!/usr/bin/env bash
set -a
source .env
set +a

./gradlew bootRun --args="--spring.ai.ollama.chat.options.model=llama3 --experiment.enabled-providers=ollama --experiment.model-label=ollama-llama3 --experiment.enabled-languages=en,es,fr,de,it,pt,ja,zh-CN,zh-TW,ko,ar,ru,ca,hi,id,he,tr,sw,bn,af,ur,da,uk,fa,el"
