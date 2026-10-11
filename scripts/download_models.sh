#!/usr/bin/env bash
# Скачивает офлайн-модели Vosk (русскую и английскую) в app/src/full/assets (для сборки full; lite качает их сам на телефоне).
# Запуск из корня проекта:  bash scripts/download_models.sh
set -euo pipefail
cd "$(dirname "$0")/.."
ASSETS=app/src/full/assets  # модели встраиваются только во flavor full
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

fetch() {  # $1 = model name on alphacephei.com, $2 = target asset dir
  local name=$1 target=$2
  if [ -f "$ASSETS/$target/uuid" ]; then
    echo "✓ $target уже на месте"; return
  fi
  echo "↓ $name"
  curl -fL --retry 3 -o "$TMP/$name.zip" "https://alphacephei.com/vosk/models/$name.zip"
  unzip -q "$TMP/$name.zip" -d "$TMP"
  rm -rf "${ASSETS:?}/$target"
  mkdir -p "$ASSETS"
  mv "$TMP/$name" "$ASSETS/$target"
  # Vosk StorageService копирует модель из assets только при наличии файла uuid
  echo "$name" > "$ASSETS/$target/uuid"
  echo "✓ $target готова"
}

fetch vosk-model-small-ru-0.22 model-ru
fetch vosk-model-small-en-us-0.15 model-en
