#!/usr/bin/env bash
set -euo pipefail

PROTO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(dirname "$PROTO_DIR")"
cd "$PROTO_DIR"

echo "=== Installing proto tooling (buf) ==="
npm run setup

echo "=== Linting proto sources ==="
npm run lint

echo "=== Generating proto code (Java + TypeScript) ==="
npm run generate

echo "=== Compiling generated Java ==="
"$REPO_ROOT/gradlew" -p "$PROTO_DIR" build

echo ""
echo "=== Generation complete ==="
echo "  Java:        gen/java/"
echo "  TypeScript:  gen/ts/"
echo ""
echo "Commit gen/ together with the .proto changes; backend, minecraft and panel consume it from the repo."
