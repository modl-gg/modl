#!/usr/bin/env bash
set -euo pipefail

: "${IMAGE:?}"
port="${SMOKE_PORT:-18080}"
attempts="${SMOKE_ATTEMPTS:-90}"
name="modl-image-smoke-$$"
volume="modl-image-smoke-$$"

cleanup() {
  docker rm -f "$name" > /dev/null 2>&1 || true
  docker volume rm "$volume" > /dev/null 2>&1 || true
}
trap cleanup EXIT

docker volume create "$volume" > /dev/null
docker run -d --name "$name" -p "127.0.0.1:$port:8080" -v "$volume:/data" "$IMAGE" > /dev/null

for _ in $(seq 1 "$attempts"); do
  if [ "$(docker inspect -f '{{.State.Running}}' "$name")" != "true" ]; then
    docker logs "$name" 2>&1 | tail -n 80
    echo "::error::The image exited before it answered /actuator/health"
    exit 1
  fi
  if curl -fs -o /dev/null "http://127.0.0.1:$port/actuator/health"; then
    echo "The image answers /actuator/health"
    exit 0
  fi
  sleep 2
done
docker logs "$name" 2>&1 | tail -n 80
echo "::error::The image did not answer /actuator/health in time"
exit 1
