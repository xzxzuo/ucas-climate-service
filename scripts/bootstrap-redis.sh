#!/usr/bin/env bash
set -euo pipefail
project_dir="$(cd "$(dirname "$0")/.." && pwd)"
cd "$project_dir"
mkdir -p .runtime/downloads
archive=.runtime/downloads/redis-7.4.11.tar.gz
test -f "$archive" || curl -fL --max-time 90 https://download.redis.io/releases/redis-7.4.11.tar.gz -o "$archive"
test -d .runtime/redis-7.4.11 || tar -xzf "$archive" -C .runtime
make -C .runtime/redis-7.4.11 -j4 redis-server redis-cli
