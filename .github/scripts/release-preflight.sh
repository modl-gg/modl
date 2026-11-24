#!/usr/bin/env bash
set -euo pipefail

: "${TAG:?}" "${GITHUB_OUTPUT:?}"
keys_file="libs/update-protocol/src/main/resources/gg/modl/update/release-keys.txt"
version_pattern='^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-[0-9A-Za-z.]{1,32})?$'

fail() {
  echo "::error::$1"
  exit 1
}

version="$(sed -n 's/^modl\.version=//p' gradle.properties)"
[[ "$version" =~ $version_pattern ]] || fail "modl.version '$version' is not MAJOR.MINOR.PATCH[-pre]"
[ "$TAG" = "v$version" ] || fail "Tag $TAG does not match modl.version $version (expected v$version)"
major="${BASH_REMATCH[1]}"
line="${BASH_REMATCH[1]}.${BASH_REMATCH[2]}"
prerelease=false
[ -n "${BASH_REMATCH[4]}" ] && prerelease=true

git merge-base --is-ancestor "$(git rev-parse HEAD)" origin/main || fail "The tagged commit is not on main"

key_count="$(grep -v '^#' "$keys_file" | grep -c . || true)"
distinct="$(grep -v '^#' "$keys_file" | grep . | sort -u | wc -l | tr -d ' ')"
[ "$key_count" = 2 ] && [ "$distinct" = 2 ] || fail "$keys_file must list exactly two distinct release keys, found $key_count"
while read -r key; do
  printf '%s' "$key" | base64 -d > "$RUNNER_TEMP/key.der" 2>/dev/null || fail "A release key is not base64"
  openssl pkey -pubin -inform DER -in "$RUNNER_TEMP/key.der" -noout 2>/dev/null || fail "A release key is not a public key"
done < <(grep -v '^#' "$keys_file" | grep .)

compose_tag="$(sed -n 's/.*ghcr\.io\/modl-gg\/modl:\${MODL_IMAGE_TAG:-\([^}]*\)}.*/\1/p' docker-compose.yml)"
[ "$compose_tag" = "$line" ] || fail "docker-compose.yml defaults to image tag '$compose_tag'; it must be $line"

previous="$(git tag --list 'v*' --sort=-v:refname | grep -vx "$TAG" | head -n 1 || true)"
if [ -n "$previous" ]; then
  changed="$(git diff --name-status "$previous" HEAD -- backend/src/main/resources/db/migration | grep -v '^A' || true)"
  [ -z "$changed" ] || fail "Released migrations must never change. Changed since $previous: $changed"
fi

releases="$(gh release list --limit 200 --json tagName,isDraft,isPrerelease --jq '.[] | select(.isDraft | not) | select(.isPrerelease | not) | .tagName' | sed 's/^v//')"
newest_of() {
  { printf '%s\n' "$releases" | grep -E "$1" || true; echo "$version"; } | grep . | sort -V | tail -n 1
}
make_latest=false
move_minor=false
move_major=false
if [ "$prerelease" = false ]; then
  [ "$(newest_of '^[0-9]+\.[0-9]+\.[0-9]+$')" = "$version" ] && make_latest=true
  [ "$(newest_of "^${line//./\\.}\\.[0-9]+$")" = "$version" ] && move_minor=true
  [ "$(newest_of "^${major}\\.[0-9]+\\.[0-9]+$")" = "$version" ] && move_major=true
fi

{
  echo "version=$version"
  echo "line=$line"
  echo "major=$major"
  echo "prerelease=$prerelease"
  echo "make_latest=$make_latest"
  echo "move_minor=$move_minor"
  echo "move_major=$move_major"
  echo "min_upgrade_from=$(sed -n 's/^modl\.minUpgradeFrom=//p' gradle.properties)"
  echo "critical=$(sed -n 's/^modl\.release\.critical=//p' gradle.properties)"
} >> "$GITHUB_OUTPUT"
