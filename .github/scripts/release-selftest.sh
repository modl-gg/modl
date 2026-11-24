#!/usr/bin/env bash
set -euo pipefail

: "${DIST_DIR:?}"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
version="$(sed -n 's/^modl\.version=//p' gradle.properties)"
umask 077

openssl genpkey -algorithm ed25519 -out "$work/key.pem"
openssl pkey -in "$work/key.pem" -pubout -outform DER | base64 -w0 > "$work/keys.txt"
echo >> "$work/keys.txt"

DIST_DIR="$DIST_DIR" VERSION="$version" COMMIT="$(git rev-parse HEAD)" IMAGE_REPOSITORY=ghcr.io/modl-gg/modl \
  IMAGE_DIGEST="sha256:$(printf '%064d' 0)" MIN_UPGRADE_FROM="$version" CRITICAL=false PUBLISHED_AT=2026-01-01T00:00:00Z \
  OUTPUT="$work/manifest.json" .github/scripts/release-manifest.sh > /dev/null

sign() {
  openssl pkeyutl -sign -rawin -inkey "$work/key.pem" -in "$1" -out "$work/manifest.sig"
  jq -n --arg keyId "$(openssl pkey -in "$work/key.pem" -pubout -outform DER | sha256sum | cut -c1-16)" \
    --arg payload "$(base64 -w0 "$1")" --arg signature "$(base64 -w0 "$work/manifest.sig")" \
    '{format:1,keyId:$keyId,payload:$payload,signature:$signature}' > "$2"
}
sign "$work/manifest.json" "$work/modl-release.json"

check_signature() {
  local envelope="$1" key_der="$work/check.der"
  base64 -d "$work/keys.txt" > "$key_der"
  jq -r .payload "$envelope" | base64 -d > "$work/check.bin"
  jq -r .signature "$envelope" | base64 -d > "$work/check.sig"
  openssl pkeyutl -verify -rawin -pubin -keyform DER -inkey "$key_der" -in "$work/check.bin" -sigfile "$work/check.sig" > /dev/null 2>&1
}
check_signature "$work/modl-release.json" || { echo "::error::A freshly signed manifest does not verify"; exit 1; }

jq -c '.version = "9.9.9"' "$work/manifest.json" | tr -d '\n' > "$work/tampered.json"
jq --arg payload "$(base64 -w0 "$work/tampered.json")" '.payload = $payload' "$work/modl-release.json" > "$work/tampered-release.json"
if check_signature "$work/tampered-release.json"; then
  echo "::error::A tampered manifest verified"
  exit 1
fi
echo "The release scripts sign and verify a manifest, and reject a tampered one"
