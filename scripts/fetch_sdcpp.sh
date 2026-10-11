#!/usr/bin/env bash
# Скачивает исходники stable-diffusion.cpp (и ggml) для нативной части рисования.
# В git-клоне достаточно: git submodule update --init --recursive
# Этот скрипт — для проекта, скачанного архивом (zip без подмодулей).
set -euo pipefail
cd "$(dirname "$0")/.."
DIR=app/src/main/cpp/stable-diffusion.cpp
COMMIT=2988060a4338a5dcd94721c964a8e87003c1ac8b   # закреплённая версия, с ней проверена сборка

if [ -f "$DIR/CMakeLists.txt" ] && [ -f "$DIR/ggml/CMakeLists.txt" ]; then
  echo "stable-diffusion.cpp уже на месте: $DIR"; exit 0
fi
if git rev-parse --is-inside-work-tree >/dev/null 2>&1 && git config -f .gitmodules --get submodule.$DIR.url >/dev/null 2>&1; then
  git submodule update --init "$DIR"
  git -C "$DIR" submodule update --init --depth 1 ggml
  exit 0
fi
rm -rf "$DIR"
git clone --filter=blob:none https://github.com/leejet/stable-diffusion.cpp "$DIR"
git -C "$DIR" checkout -q "$COMMIT"
git -C "$DIR" submodule update --init --depth 1 ggml
echo "Готово: $DIR @ ${COMMIT:0:7}"
