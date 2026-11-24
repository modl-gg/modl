#!/usr/bin/env bash
set -euo pipefail

: "${TAG:?}" "${FILES_DIR:?}" "${PRERELEASE:?}" "${MAKE_LATEST:?}"
cd "$FILES_DIR"
sha256sum -- * > SHA256SUMS

state="$(gh release view "$TAG" --json isDraft --jq .isDraft 2>/dev/null || echo missing)"
if [ "$state" = false ]; then
  mkdir -p ../published
  gh release download "$TAG" --pattern SHA256SUMS --dir ../published --clobber
  cmp -s SHA256SUMS ../published/SHA256SUMS || { echo "::error::$TAG is already published with other files"; exit 1; }
  echo "$TAG is already published with these files; nothing to do"
  exit 0
fi
if [ "$state" = true ]; then
  gh release delete "$TAG" --yes
fi

prerelease_flag=()
[ "$PRERELEASE" = true ] && prerelease_flag=(--prerelease)
gh release create "$TAG" --verify-tag --draft --generate-notes "${prerelease_flag[@]}" -- *

mkdir -p ../draft
gh release download "$TAG" --dir ../draft --clobber
(cd ../draft && sha256sum -c SHA256SUMS)

gh release edit "$TAG" --draft=false --latest="$MAKE_LATEST"
