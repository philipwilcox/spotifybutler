# Kotlin API evolution plan

This plan covers the remaining work needed to finish the direct, cache-backed
browser API under `kt/`. The Kotlin application must remain independently
buildable, keep Spotify credentials server-side, and preserve the published
OpenAPI contract.

## Current implementation baseline

- The database uses `SpotifyDatabase.sq` as the sole supported schema definition.
  SQLDelight migration files and runtime migration/version handling have been
  removed.
- Existing local SQLite databases require a one-time manual reset. Startup
  creates the complete schema only for a new database and does not automatically
  delete, migrate, or repair an existing database.
- Cache metadata retains an internal revision while a refresh is running or
  stale; `refresh` and deprecated `run` use it only to coalesce overlapping
  full-library fetches. The browser API does not expose it.
- Fixture snapshots and sanitized expectations use the current schema and reject
  unsupported fixture versions with actionable errors.
- Playlist synchronization is complete: the browser endpoint is intentionally
  last-write-wins for this single-user workflow. Playlist locks, request base
  revisions, Spotify snapshots, definition revisions, and stale-state conflict
  checks were removed as overkill. It retains CSRF/Origin and ownership checks,
  cache-backed validation, authoritative post-write reads, and atomic cache
  publication; `mapping_missing`, `cache_not_ready`, `invalid_track_ids`, and
  sanitized `spotify_failure` remain supported errors.

## 4. Last: consolidate cache-read implementation

Perform this refactoring only after all functional behavior, API documentation,
and contract tests are complete.

- Add a focused `SpotifyStore.editablePlaylistTrackIds(playlistId: String): List<String>`
  projection for current playlist reads.
- Move playlist ordering and playable-item filtering into the store/SQL
  boundary.
- Ensure the projection orders by playlist position, preserves duplicate IDs,
  and applies the complete playable-track predicate.
- Change `ApiApplication.current` to consume that projection rather than
  retrieving general-purpose playlist rows and filtering them in the HTTP layer.
- Keep the wire contract and tested behavior unchanged.
- Add or retain focused store-level coverage proving the projection has the same
  exact output as the API contract tests.
- Avoid introducing unrelated abstractions or broad store redesign during this
  cleanup.

This section is intentionally the last implementation task in the plan. It is
not part of this plan-document update.

## 5. Final verification

- Run `./gradlew ktlintFormat` from `kt/`, review its diff, and stage any Kotlin
  formatting changes explicitly.
- Run `./gradlew lint`, the focused API/store schema and contract tests, and then
  `./gradlew build`.
- Confirm that no test performs network calls and that fixtures contain only
  synthetic, sanitized data.
- Stage modified source files, tests, OpenAPI, documentation, schema changes,
  and sanitized fixtures. Leave this plan document unstaged.
