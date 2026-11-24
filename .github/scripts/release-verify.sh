#!/usr/bin/env bash
set -euo pipefail

: "${ENVELOPE:?}" "${MANIFEST:?}" "${KEYS_FILE:?}" "${DIST_DIR:?}" "${VERSION:?}"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

fail() {
  echo "::error::$1"
  exit 1
}

key_id="$(jq -r .keyId "$ENVELOPE")"
jq -r .payload "$ENVELOPE" | base64 -d > "$work/payload.bin"
jq -r .signature "$ENVELOPE" | base64 -d > "$work/payload.sig"
found=false
while read -r key; do
  printf '%s' "$key" | base64 -d > "$work/key.der"
  if [ "$(sha256sum "$work/key.der" | cut -c1-16)" = "$key_id" ]; then
    found=true
    break
  fi
done < <(grep -v '^#' "$KEYS_FILE" | grep .)
[ "$found" = true ] || fail "The release is signed with key $key_id, which is not committed"
openssl pkeyutl -verify -rawin -pubin -keyform DER -inkey "$work/key.der" -in "$work/payload.bin" -sigfile "$work/payload.sig" \
  || fail "The release signature does not verify"
cmp -s "$work/payload.bin" "$MANIFEST" || fail "The signed payload is not the reviewed manifest"

for kind in server embedded plugin; do
  file="$(jq -r ".artifacts.$kind.file" "$MANIFEST")"
  [ "$(sha256sum "$DIST_DIR/$file" | cut -d' ' -f1)" = "$(jq -r ".artifacts.$kind.sha256" "$MANIFEST")" ] || fail "$file changed after signing"
  [ "$(stat -c %s "$DIST_DIR/$file")" = "$(jq -r ".artifacts.$kind.size" "$MANIFEST")" ] || fail "$file has another size"
done

embedded="$DIST_DIR/modl-embedded-$VERSION.zip"
pin="$(unzip -p "$DIST_DIR/modl-$VERSION.jar" modl-server/payload.txt 2>/dev/null || true)"
pin_field() {
  sed -n "s/^$1 //p" <<< "$pin"
}
[ "$(head -n 1 <<< "$pin")" = "modl-embedded-payload 1" ] || fail "The plugin jar has no embedded payload pin"
[ "$(pin_field version)" = "$VERSION" ] && [ "$(pin_field sha256)" = "$(sha256sum "$embedded" | cut -d' ' -f1)" ] \
  && [ "$(pin_field size)" = "$(stat -c %s "$embedded")" ] \
  || fail "The plugin jar does not pin modl-embedded-$VERSION.zip, so its first embedded start would refuse the release"

keys_in() {
  unzip -p "$1" "$2" 2>/dev/null || true
}
nested_keys() {
  local archive="$1" pattern="$2" entry
  entry="$(unzip -Z1 "$archive" | grep -E "$pattern" | head -n 1)"
  [ -n "$entry" ] || fail "$archive has no update-protocol library"
  unzip -p "$archive" "$entry" > "$work/nested.jar"
  keys_in "$work/nested.jar" gg/modl/update/release-keys.txt
}
expected="$(cat "$KEYS_FILE")"
[ "$(keys_in "$DIST_DIR/modl-$VERSION.jar" gg/modl/update/release-keys.txt)" = "$expected" ] || fail "The plugin jar carries other keys"
[ "$(nested_keys "$DIST_DIR/modl-server-$VERSION.jar" '^BOOT-INF/lib/update-protocol-.*\.jar$')" = "$expected" ] \
  || fail "The server jar carries other keys"
[ "$(nested_keys "$DIST_DIR/modl-embedded-$VERSION.zip" '^lib/update-protocol-.*\.jar$')" = "$expected" ] \
  || fail "The embedded payload carries other keys"

java -jar "$DIST_DIR/modl-server-$VERSION.jar" update --verify "$ENVELOPE" --files "$DIST_DIR"
