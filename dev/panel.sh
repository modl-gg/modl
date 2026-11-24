#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

export MODL_DEV_BACKEND_PORT="${MODL_DEV_BACKEND_PORT:-18080}"

cd "$repo_root"
if [ ! -f node_modules/.package-lock.json ] || [ package-lock.json -nt node_modules/.package-lock.json ]; then
  npm ci
fi
exec npm run dev -w panel -- "$@"
