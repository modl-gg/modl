#!/usr/bin/env bash
set -euo pipefail

: "${GITHUB_OUTPUT:?}"

emit() {
  {
    echo "java=$1"
    echo "panel=$2"
    echo "sql=$3"
    echo "image=$4"
    echo "harness=$5"
    echo "wiki=$6"
  } >> "$GITHUB_OUTPUT"
  echo "java=$1 panel=$2 sql=$3 image=$4 harness=$5 wiki=$6"
}

if [ "${FORCE_ALL:-false}" = "true" ] || [ -z "${BASE_SHA:-}" ] || [ "$BASE_SHA" = "0000000000000000000000000000000000000000" ]; then
  emit true true true true true true
  exit 0
fi

if ! git cat-file -e "$BASE_SHA^{commit}" 2> /dev/null; then
  git fetch --no-tags --depth=1 origin "$BASE_SHA" || true
fi
if ! git cat-file -e "$BASE_SHA^{commit}" 2> /dev/null; then
  emit true true true true true true
  exit 0
fi

java=false
panel=false
sql=false
image=false
harness=false
wiki=false

changed=$(git -c core.quotePath=false diff --name-only --no-renames "$BASE_SHA" "$HEAD_SHA")

while IFS= read -r file; do
  [ -n "$file" ] || continue
  case "$file" in
    wiki/*)
      wiki=true
      ;;
    LICENSE | THIRD_PARTY_NOTICES.md | licenses/*)
      java=true
      image=true
      ;;
    listings/* | README.md)
      ;;
    panel/* | package.json | package-lock.json)
      panel=true
      ;;
    proto/*)
      panel=true
      java=true
      harness=true
      ;;
    testing/*)
      harness=true
      ;;
    tsconfig.strict.json | eslint.strict.mjs)
      panel=true
      harness=true
      ;;
    Dockerfile | .dockerignore | docker-compose.yml | docker-compose.build.yml | .github/scripts/image-smoke.sh)
      image=true
      ;;
    .github/workflows/build.yml | .github/scripts/changed-areas.sh)
      java=true
      panel=true
      sql=true
      image=true
      harness=true
      wiki=true
      ;;
    *)
      java=true
      ;;
  esac
  case "$file" in
    backend/* | gradle/libs.versions.toml | settings.gradle.kts)
      sql=true
      ;;
  esac
done <<< "$changed"

if [ "$java" = "true" ] || [ "$panel" = "true" ]; then
  image=true
fi

emit "$java" "$panel" "$sql" "$image" "$harness" "$wiki"
