#!/usr/bin/env bash
set -euo pipefail

: "${IMAGE:?}" "${DIGEST:?}" "${VERSION:?}" "${LINE:?}" "${MAJOR:?}" "${MOVE_MINOR:?}" "${MOVE_MAJOR:?}" "${MAKE_LATEST:?}"
tags=(--tag "$IMAGE:$VERSION")
[ "$MOVE_MINOR" = true ] && tags+=(--tag "$IMAGE:$LINE")
[ "$MOVE_MAJOR" = true ] && tags+=(--tag "$IMAGE:$MAJOR")
[ "$MAKE_LATEST" = true ] && tags+=(--tag "$IMAGE:latest")
docker buildx imagetools create "${tags[@]}" "$IMAGE@$DIGEST"
