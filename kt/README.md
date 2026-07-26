# Kotlin Spotify Butler

This service performs Spotify Authorization Code login and caches Spotify library data in a local SQLite database.

1. Copy `secrets.properties.example` to the ignored `secrets.properties` and fill in the Spotify client ID and secret.
2. Configure the Spotify application redirect URI exactly as `http://127.0.0.1:8888/callback`.
3. From the repository root, install and build the same-origin studio:

   ```sh
   npm --prefix vue install
   npm --prefix vue run build
   ```

4. From the repository root, run `./kt/gradlew -p kt run`. The Gradle `run` task rebuilds the Vue bundle automatically.
5. Visit `http://127.0.0.1:8888/`. Choose **Connect Spotify** if the session is not active. After approval, the
   callback creates the opaque Butler session cookie and
   redirects to the validated relative `returnTo` path (default `/`).

The Kotlin service serves the built bundle from `vue/dist` by default. Set `spotify.frontendDirectory` or
`SPOTIFY_BUTLER_FRONTEND_DIRECTORY` when the bundle is stored elsewhere. A missing `index.html` returns an actionable
build-required response. The exact Spotify callback remains `http://127.0.0.1:8888/callback`.

The default database is `kt/spotify.db` when launched from the repository root (or `spotify.db` when launched from
inside `kt/`). The normal `/start` flow establishes a session without running a full refresh.

Authentication durability: SQLite stores one AES-GCM protected refresh token per Spotify user and opaque browser
session metadata. The protection key is derived from the Spotify client secret, so the database and the ignored secrets
file must both have restrictive operating-system permissions. Access tokens, CSRF tokens, and OAuth state are never
persisted. The six-month `HttpOnly; SameSite=Strict` `butler_session` cookie contains only the local opaque session ID;
it is an application convenience and does not extend Spotify's refresh-token lifetime. A missing in-memory session is
rehydrated from SQLite on `GET /api/v1/session`, with a Spotify identity check and cookie rotation.

## Legacy one-shot run

For the compatibility workflow that refetches everything and updates the built-in playlists in one request, visit
`http://127.0.0.1:8888/start?refresh=true`. After Spotify approval, Butler replaces the SQLite cache, removes duplicate
saved tracks, replans the built-in playlists, and applies their playlist updates. The browser receives a sanitized JSON
completion summary; access and refresh tokens remain server-side. Use this all-at-once mutation workflow deliberately.

If the server was configured with dry-run mode, or if `dryRun=true` is present on the callback request, playlist writes
are reported without being applied. Duplicate saved-track cleanup is still a separate legacy operation and may mutate
the Spotify library even during a dry run.

The loader reads saved tracks, top tracks, top artists, playlists, and the tracks in each playlist. SQLDelight owns the
SQLite schema and its named cache-load statements.

## Browser API

The browser-facing API is described by [`src/main/resources/openapi.yaml`](src/main/resources/openapi.yaml). It uses an
opaque `butler_session` cookie and returns a CSRF token from `GET /api/v1/session`; Spotify access and refresh tokens
remain server-side. The public readiness endpoint is `GET /health`. After OAuth, the narrow API resources support cache
refresh, full ID-only playlist/current views, bounded song enrichment, and direct client-submitted playlist
synchronization. State-changing requests require both `X-CSRF-Token` and a trusted `Origin`; synchronization is
last-write-wins for this single-user deployment. `/api/v1/run` remains only as a deprecated refresh compatibility
endpoint.

The current endpoint returns the complete ordered, ID-only cached playlist, including repeated track occurrences.
Song enrichment accepts comma-separated IDs in request order, preserves found duplicates, reports each missing ID once,
and is limited to 50 normalized IDs. If synchronization reports `cache_not_ready` (HTTP 409), refresh the library and
retry after the cache becomes ready. A failed refresh or playlist write returns a generic, retryable
`spotify_failure` response (HTTP 502); credentials and upstream failure details are never sent to the browser.

The repository-level [`scripts/api-demo.sh`](../scripts/api-demo.sh) demonstrates the intended curl sequence. Set
`BUTLER_SESSION` and `CSRF_TOKEN` from an authenticated browser session, or point `BUTLER_COOKIE_JAR` at a cookie jar.
The script defaults to the local trusted origin `http://127.0.0.1:8888`; set `BUTLER_ORIGIN` when using another
configured origin. Normal runs refresh the cache and demonstrate read/enrichment requests without changing a Spotify
playlist. Pass `--sync` explicitly to submit the displayed replacement request. For a single-user deployment, set
`spotify.allowedUserId` in the ignored secrets properties file.

## Capturing and building fixtures

The support pipeline is developer-only. It performs only the normal cache reads and never makes Spotify writes. Raw
logs, draft fixtures, and the captured SQLite database can contain personal listening data; `raw-captures/` and draft
outputs are ignored by Git and must be reviewed and sanitized before anything is copied into test resources.

1. Start the service with output visible in the terminal and captured to an ignored log. From the repository root:

   ```sh
   ./kt/gradlew -p kt captureSpotifyRun \
     -PcaptureLog=raw-captures/spotify-run.log \
     -PdatabasePath=raw-captures/spotify-run.db
   ```

   The task prints the resolved log and database paths, then runs the normal service. Visit `/start`, complete OAuth, and
   use the cache-only `POST /api/v1/library/refresh` flow (for example, through `scripts/api-demo.sh`) for one intentional
   cache load. Do not use `/start?refresh=true` for fixture capture: the legacy workflow also mutates playlists and may
   remove duplicate saved tracks. Stop the service after the refresh finishes.

2. Build an ignored draft from that log and database:

   ```sh
   ./kt/gradlew -p kt buildSpotifyFixtures \
     -PcaptureLog=raw-captures/spotify-run.log \
     -PdatabasePath=raw-captures/spotify-run.db
   ```

   The task writes one `spotify-run.draft.jsonl` and a concise `.report.txt` beside the log. The draft contains one
   complete scenario line for the selected capture run. When the log contains multiple runs and no
   `-PcaptureRunId=...` is supplied, the builder validates, scrubs, and writes all runs through one bounded worker pool,
   deriving a distinct draft/report filename for each run. Use `-PcaptureRunId=...` to build only one run or to use an
   explicit output path. Set `-PscrubWorkers=6` to choose the shared scrub worker count; it defaults to the available
   processor count and must be a positive integer. To keep large drafts small, `-PmaxSavedTracks=N` retains the first
   N saved tracks plus the final saved-tracks page (default: 10), while `-PmaxTopItems=N` does the same for each
   `top_*` endpoint (default: 10).
   `-PmaxPlaylistTracksCalls=N` limits the number of playlist-track endpoints included.
   `-PmaxPlaylistTracks=N` limits generated `playlist_tracks` rows (default: 100).
   `-PmaxAvailableMarkets=N` limits each `available_markets` array (default: 5).
   It consumes only structured `SPOTIFY_CAPTURE_EVENT` lines, validates successful JSON pagination, opens SQLite
   read-only, and exports all six cache tables through SQLDelight diagnostic queries. If the database path is omitted,
   it uses the path printed by the service startup line.

3. Treat the draft as personal data. Track IDs and URIs are not secrets, but they can identify a user's library or
   listening context when combined with playlist names, owner data, timestamps, and raw `track_json`; they can also
   leak through repository history, reviews, backups, or build artifacts. Replace names, IDs, URIs, owners,
   descriptions, timestamps, and other identifying fields with synthetic values while preserving response shapes and
   edge cases. Copy only the sanitized line into `src/test/resources/spotify-fixtures/*.jsonl`.

   The committed fixture directory may contain multiple `.jsonl` files, and each file may contain multiple scenarios,
   one complete scenario per nonblank line. Run `./kt/gradlew -p kt test` to execute every line through the real
   Spotify parser and cache service. The scripted client fails on unexpected requests or unused responses and the test
   compares all six exported tables, including canonical `track_json` and `sync_status`.

## Linting

The Kotlin service uses ktlint and detekt. Enable the repository pre-commit hook once after cloning:

```sh
./scripts/setup-git-hooks.sh
```

The hook runs `ktlintFormat` first. If formatting changes Kotlin source files, the commit stops so you can review and
stage them. It then runs detekt. The shared 120-character line limit is configured in `.editorconfig` and
`config/detekt/detekt.yml`.

Detekt uses its 2.0 alpha release so linting can run with the project’s JDK/JVM 25 target. Revisit this dependency when
the stable 2.x release becomes available.

Set `SPOTIFY_BUTLER_SECRETS_FILE` to use a properties file at another path.
