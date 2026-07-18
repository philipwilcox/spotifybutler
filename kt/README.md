# Kotlin Spotify Butler

This service performs Spotify Authorization Code login and caches Spotify library data in a local SQLite database.

1. Copy `secrets.properties.example` to the ignored `secrets.properties` and fill in the Spotify client ID and secret.
2. Configure the Spotify application redirect URI exactly as `http://127.0.0.1:8888/callback`.
3. From the repository root, run `./kt/gradlew -p kt run`.
4. Visit `http://127.0.0.1:8888/start`. After approval, the callback fetches the Spotify collections into SQLite and
   redirects to `/hello`, which returns `hello, <display name>`.

The default database is `kt/spotify.db` when launched from the repository root (or `spotify.db` when launched from
inside `kt/`). The database is loaded after OAuth only when it has no completed sync. Use
`http://127.0.0.1:8888/start?refresh=true` to replace the cached data deliberately.

The loader reads saved tracks, top tracks, top artists, playlists, and the tracks in each playlist. It does not yet run
playlist queries or make any Spotify playlist/library modifications. SQLDelight owns the SQLite schema and its named
cache-load statements.

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

   The task prints the resolved log and database paths, then runs the normal service. Visit `/start` (or
   `/start?refresh=true`) and complete one intentional cache load. Stop the service after the callback finishes.

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
