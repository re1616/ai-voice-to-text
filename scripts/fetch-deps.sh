#!/usr/bin/env bash
# Fetches pinned versions of whisper.cpp and llama.cpp into third_party/
set -euo pipefail
cd "$(dirname "$0")/.."
WHISPER_TAG="${WHISPER_TAG:-v1.9.5}"
LLAMA_TAG="${LLAMA_TAG:-b11676}"
mkdir -p third_party
fetch() {
  local repo=$1 tag=$2 dir=$3
  if [ -d "third_party/$dir/.git" ] && [ "$(git -C third_party/$dir describe --tags 2>/dev/null)" = "$tag" ]; then
    echo "$dir $tag already present"; return
  fi
  rm -rf "third_party/$dir"
  git clone --depth 1 --branch "$tag" "https://github.com/$repo.git" "third_party/$dir"
}
fetch ggml-org/whisper.cpp "$WHISPER_TAG" whisper.cpp
fetch ggml-org/llama.cpp "$LLAMA_TAG" llama.cpp
