#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

export MODL_PORT="${MODL_PORT:-18080}"
export MODL_DATA_DIR="${MODL_DATA_DIR:-$repo_root/dev/.data}"
export MODL_PUBLIC_URL="${MODL_PUBLIC_URL:-http://localhost:3000}"

mkdir -p "$MODL_DATA_DIR"
cd "$repo_root"
exec ./gradlew :backend:bootRun -Pmodl.skipPanel=true "$@"
