#!/usr/bin/env bash
set -euo pipefail

# Spotify login is handled by Butler's OAuth browser flow; this script never
# receives or sends Spotify access/refresh tokens.
#
# First-time setup:
#
#   1. Copy kt/secrets.properties.example to kt/secrets.properties and fill in
#      the Spotify client ID and secret.
#   2. Start Butler from the repository root:
#
#        ./kt/gradlew -p kt run
#
#   3. Open this URL in Safari, Firefox, or another browser and approve Spotify:
#
#        http://127.0.0.1:8888/start?returnTo=/health
#
#      You can ask this script to open the system browser for you with:
#
#        ./scripts/api-demo.sh --login
#
#   4. After the callback succeeds, copy the Butler `butler_session` cookie
#      from the browser's developer tools. It is HttpOnly, so JavaScript cannot
#      read it; browser developer tools can display it. Then export it:
#
#        export BUTLER_SESSION='copied-cookie-value'
#
#      The script fetches the CSRF token automatically when jq is installed.
#      To provide it explicitly instead:
#
#        export CSRF_TOKEN='csrf-token-from-GET-api-v1-session'
#
# Run the authenticated examples:
#
#   ./scripts/api-demo.sh
#
# Add --sync to submit the displayed direct playlist replacement request:
#
#   ./scripts/api-demo.sh --sync
#
# A curl cookie jar may be supplied with BUTLER_COOKIE_JAR, but it must contain
# the `butler_session` cookie; the CSRF token is returned by the session API,
# not stored in that jar.

BASE_URL="${BUTLER_BASE_URL:-http://127.0.0.1:8888}"
ORIGIN="${BUTLER_ORIGIN:-http://127.0.0.1:8888}"
DEFINITION_ID="${BUTLER_DEFINITION_ID:-RECENT_LIKED_100}"
COOKIE_JAR="${BUTLER_COOKIE_JAR:-}"
sync_requested=false

usage() {
  cat <<'EOF'
Usage: ./scripts/api-demo.sh [--login|--sync]

  --login  Open the Spotify login page and exit.
  --sync   Replace the managed playlist with the first two current track IDs.

The normal invocation is read-only after cache refresh. Set BUTLER_ORIGIN when
the service is configured with a trusted origin other than
http://127.0.0.1:8888.
EOF
}

case "${1:-}" in
  "") ;;
  --help|-h)
    usage
    exit 0
    ;;
  --sync)
    sync_requested=true
    ;;
  --login)
    login_url="$BASE_URL/start?returnTo=/health"
    if command -v open >/dev/null 2>&1; then
      open "$login_url"
    elif command -v xdg-open >/dev/null 2>&1; then
      xdg-open "$login_url" >/dev/null 2>&1 &
    else
      echo "Open this URL in a browser: $login_url"
    fi
    echo 'After approving Spotify, export BUTLER_SESSION and rerun this script.'
    exit 0
    ;;
  *)
    echo "Unknown argument: $1" >&2
    usage >&2
    exit 2
    ;;
esac

curl_args=(--silent --show-error --fail-with-body)
if [[ -n "$COOKIE_JAR" ]]; then
  curl_args+=(--cookie "$COOKIE_JAR")
elif [[ -n "${BUTLER_SESSION:-}" ]]; then
  curl_args+=(--cookie "butler_session=${BUTLER_SESSION}")
fi

api_get() {
  curl "${curl_args[@]}" "$BASE_URL$1"
}

api_json() {
  local method="$1"
  local path="$2"
  local body="$3"
  local headers=(-H 'Content-Type: application/json' -H "Origin: $ORIGIN")
  [[ -n "${CSRF_TOKEN:-}" ]] && headers+=(-H "X-CSRF-Token: $CSRF_TOKEN")
  curl "${curl_args[@]}" -X "$method" "${headers[@]}" --data "$body" "$BASE_URL$path"
}

if [[ -z "${CSRF_TOKEN:-}" ]] && [[ -n "$COOKIE_JAR" || -n "${BUTLER_SESSION:-}" ]]; then
  if command -v jq >/dev/null 2>&1; then
    CSRF_TOKEN="$(api_get /api/v1/session | jq -r '.csrfToken')"
    export CSRF_TOKEN
  else
    echo 'jq is required to derive CSRF_TOKEN automatically; export CSRF_TOKEN explicitly.' >&2
  fi
fi

echo '== readiness =='
api_get /health
echo

echo '== authenticated session =='
api_get /api/v1/session
echo

echo '== sync SQLite cache =='
api_json POST /api/v1/library/refresh '{}'
echo

echo '== playlist definitions =='
api_get /api/v1/playlists
echo

echo '== current playlist =='
current_json="$(api_get "/api/v1/playlists/$DEFINITION_ID/current")"
printf '%s\n' "$current_json"

if command -v jq >/dev/null 2>&1; then
  if [[ "$(printf '%s' "$current_json" | jq -r '.current != null')" == true ]]; then
    track_ids="$(printf '%s' "$current_json" | jq -r '.current.trackIds[0:2] | join(",")')"
    if [[ -n "$track_ids" ]]; then
      echo '== detailed metadata for two current tracks =='
      api_get "/api/v1/songs?ids=$track_ids"
      echo

      sync_body="$(printf '%s' "$current_json" | jq -c '.current as $current | {trackIds: $current.trackIds[0:2], baseSnapshotId: $current.snapshotId, baseCacheRevision: $current.cacheRevision}')"
      if [[ "$sync_requested" == true ]]; then
        echo '== synchronize managed playlist =='
        echo "Replacing the managed playlist with: $sync_body" >&2
        api_json POST "/api/v1/playlists/$DEFINITION_ID/syncs" "$sync_body"
      else
        echo '== direct synchronization request (not sent) =='
        echo "$sync_body"
        echo 'Rerun with --sync to submit this playlist replacement.' >&2
      fi
    elif [[ "$sync_requested" == true ]]; then
      echo 'Cannot synchronize: the current playlist has no track IDs.' >&2
      exit 1
    fi
  elif [[ "$sync_requested" == true ]]; then
    echo 'Cannot synchronize: the definition has no mapped current playlist.' >&2
    exit 1
  fi
else
  if [[ "$sync_requested" == true ]]; then
    echo 'jq is required for the --sync example.' >&2
    exit 1
  fi
  echo 'Install jq to run the song-enrichment and direct synchronization examples.' >&2
fi
