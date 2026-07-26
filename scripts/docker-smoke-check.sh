#!/usr/bin/env bash
set -euo pipefail

image_name="${1:-spotify-butler:smoke}"
secret_path="${2:-}"
container_name="spotify-butler-smoke-$$"

if [[ -z "$secret_path" || ! -f "$secret_path" ]]; then
  echo "usage: $0 IMAGE SECRET_FILE" >&2
  exit 2
fi

docker build --tag "$image_name" .
docker run --detach --rm --name "$container_name" \
  --publish 127.0.0.1:8888:8888 \
  --mount "type=bind,source=$(realpath "$secret_path"),target=/run/secrets/spotify-butler.properties,readonly" \
  "$image_name" >/dev/null

cleanup() {
  docker stop "$container_name" >/dev/null 2>&1 || true
}
trap cleanup EXIT

for attempt in {1..30}; do
  if curl --silent --show-error --fail http://127.0.0.1:8888/health >/dev/null; then
    break
  fi
  if [[ "$attempt" == 30 ]]; then
    echo "container did not become ready" >&2
    exit 1
  fi
  sleep 1
done

curl --silent --show-error --fail http://127.0.0.1:8888/health | grep -F '"status":"ready"' >/dev/null
curl --silent --show-error --fail http://127.0.0.1:8888/ | grep -F '<!doctype html>' >/dev/null
curl --silent --show-error --fail http://127.0.0.1:8888/app/route | grep -F '<!doctype html>' >/dev/null

api_status="$(curl --silent --output /dev/null --write-out '%{http_code}' http://127.0.0.1:8888/api/v1/session)"
[[ "$api_status" == "401" ]]

echo "Docker smoke check passed for $image_name"
