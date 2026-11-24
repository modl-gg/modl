#!/usr/bin/env bash
set -euo pipefail

: "${DIST_DIR:?}" "${VERSION:?}" "${COMMIT:?}" "${IMAGE_REPOSITORY:?}" "${IMAGE_DIGEST:?}" "${MIN_UPGRADE_FROM:?}" "${CRITICAL:?}"
: "${PUBLISHED_AT:?}" "${OUTPUT:?}"

channel=stable
case "$VERSION" in *-*) channel=prerelease ;; esac

artifact() {
  local file="$DIST_DIR/$1"
  [ -f "$file" ] || { echo "::error::$1 is missing from the build"; exit 1; }
  jq -n --arg file "$1" --arg sha256 "$(sha256sum "$file" | cut -d' ' -f1)" --argjson size "$(stat -c %s "$file")" \
    '{file:$file,sha256:$sha256,size:$size}'
}

contract="$DIST_DIR/release-contract.json"
[ -f "$contract" ] || { echo "::error::release-contract.json is missing from the build"; exit 1; }
contract_number() {
  local value
  value="$(jq -e -r ".$1 | select(type == \"number\" and . >= 1 and . == floor)" "$contract")" \
    || { echo "::error::release-contract.json has no valid $1" >&2; exit 1; }
  echo "$value"
}
min_java="$(contract_number minJava)"
launcher_api="$(contract_number launcherApi)"
embedded_host_api="$(contract_number embeddedHostApi)"

jq -S -c -n \
  --argjson minJava "$min_java" --argjson launcherApi "$launcher_api" --argjson embeddedHostApi "$embedded_host_api" \
  --arg version "$VERSION" --arg channel "$channel" --arg commit "$COMMIT" --arg publishedAt "$PUBLISHED_AT" \
  --argjson critical "$CRITICAL" --arg minUpgradeFrom "$MIN_UPGRADE_FROM" \
  --argjson server "$(artifact "modl-server-$VERSION.jar")" \
  --argjson embedded "$(artifact "modl-embedded-$VERSION.zip")" \
  --argjson plugin "$(artifact "modl-$VERSION.jar")" \
  --arg repository "$IMAGE_REPOSITORY" --arg digest "$IMAGE_DIGEST" \
  '{format:1,product:"modl",version:$version,channel:$channel,commit:$commit,publishedAt:$publishedAt,critical:$critical,
    minUpgradeFrom:$minUpgradeFrom,minJava:$minJava,launcherApi:$launcherApi,embeddedHostApi:$embeddedHostApi,
    artifacts:{server:$server,embedded:$embedded,plugin:$plugin},image:{repository:$repository,digest:$digest}}' \
  | tr -d '\n' > "$OUTPUT"

echo "sha256=$(sha256sum "$OUTPUT" | cut -d' ' -f1)"
