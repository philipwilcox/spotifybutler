# Kotlin API evolution plan

This plan covers the remaining work needed to finish the direct, cache-backed
browser API under `kt/`. The Kotlin application must remain independently
buildable, keep Spotify credentials server-side, and preserve the published
OpenAPI contract while this work is completed.

## 1. Remove obsolete operation persistence

- Add a SQLDelight migration that removes `cache_metadata.refresh_operation_id`
  while preserving the singleton cache revision, completion timestamp, owner,
  and completion state. Update the base schema so a newly created database has
  the same final shape as a migrated database.
- Update the generated-query call sites in `SpotifyStore` so
  `replaceCache`, `markSyncComplete`, `markCacheRefreshing`, and
  `markCacheStale` read and write only the remaining cache metadata fields.
  Keep each metadata transition transactional and retain the previous ready
  revision while a refresh is in progress or becomes stale.
- Remove `refresh_operation_id` from `SpotifyTableSnapshot` and from sanitized
  fixture expectations. Bump the fixture schema version if the serialized
  diagnostic format changes, and make the fixture reader reject an unsupported
  version with an actionable error.
- Add a store migration contract test that opens a pre-migration database,
  applies the migration, and verifies that cache metadata values survive and
  the obsolete column is absent.

## 2. Consolidate direct refresh orchestration

- Extract the duplicated refresh flow in `ApiApplication.refresh` and
  `ApiApplication.run` into one private function that accepts the endpoint's
  sanitized failure message. `refresh` and the deprecated `run` handler should
  remain thin policy adapters over that function.
- The shared function must capture the starting cache revision, acquire the
  owner key through `KeyedLock.withLock`, and reload metadata inside the lock.
  If another request has already published a different revision, return the
  current `LibraryWire` without another Spotify fetch.
- Keep the last completed cache readable while a fetch is running. On success,
  return the newly published `LibraryWire`; on failure, call
  `SpotifyStore.markCacheStale` and return a sanitized `spotify_failure`
  envelope without exposing the upstream exception or credentials.
- Add an `ApiApplication` contract test with two concurrent refresh requests
  that begin at the same revision. Prove that the fetcher runs once and both
  responses describe the same ready revision. Add a failure case proving that
  the prior revision remains readable with state `stale`.

## 3. Complete playlist synchronization contracts

- Keep `ApiApplication.sync` responsible for CSRF/Origin enforcement, cache
  ownership, initial definition resolution, managed-playlist resolution, and
  acquisition of the Spotify-playlist keyed lock.
- Keep `ApiApplication.synchronizePlaylist` responsible for reloading all
  mutable state inside the lock. It must compare `baseCacheRevision`, reload
  the owner-scoped definition and mapping, compare the cached snapshot, read
  and compare the live snapshot, replace the complete ordered track list, read
  the authoritative live result, and publish it atomically through
  `SpotifyStore.publishPlaylistTrackIds`.
- Make `validateTrackIds` enforce the 5,000-item limit and reject every ID that
  is unknown, unavailable, or not a playable track in the normalized cache.
  Preserve duplicates and caller order for accepted IDs.
- Keep conflict codes stable: `cache_revision_stale` for an outdated cache,
  `mapping_missing` for an unresolved managed playlist, `playlist_changed` for
  mapping or snapshot drift, and `invalid_track_ids` for rejected items.
  Continue translating Spotify client failures to a sanitized
  `spotify_failure` envelope.
- Add module-level `ApiApplication` tests for each conflict path, cached-versus-
  live snapshot drift, mapping changes while waiting for the lock, invalid item
  classifications, and Spotify read/write failures. Add a two-thread test that
  proves requests for one Spotify playlist serialize and that the second
  request reloads state after the first publishes.

## 4. Harden cache-backed browser reads

- Keep `ApiApplication.current` cache-only. Resolve the owner-scoped definition
  and managed playlist from `SpotifyStore`, then return either `current = null`
  or one full `PlaylistCurrentWire` containing the playlist ID, cached snapshot
  ID, cache revision, and exact ordered playable track IDs.
- Add a focused store query for the editable playlist projection instead of
  filtering general-purpose rows in the HTTP layer. The query must order by
  playlist position, preserve duplicate IDs, and omit local, episode,
  unavailable, unsupported, and otherwise non-playable rows.
- Ensure `ApiApplication.songs` performs one bounded normalized lookup for at
  most 50 requested IDs, returns found songs in request order, and reports each
  missing ID once. Neither song nor current responses may expose raw upstream
  JSON.
- Expand the cache-backed read contract tests with mixed item types, duplicate
  positions, unavailable tracks, missing mappings, empty caches, and a gateway
  that fails if any Spotify method is called.

## 5. Align the published API and operator documentation

- Review `openapi.yaml` against every wire type in `ApiContract.kt`, including
  required-versus-nullable fields, response status codes, the 50-ID enrichment
  bound, the 5,000-track synchronization bound, CSRF parameters, and reusable
  sanitized error responses.
- Replace string-fragment OpenAPI assertions with a parsed contract test that
  validates the documented paths, methods, schemas, required properties, and
  response references. Keep an explicit allowlist of browser routes so an
  accidental public endpoint is detected.
- Update `kt/README.md` and `scripts/api-demo.sh` to describe and demonstrate
  the implemented direct refresh, full current-list read, bounded song
  enrichment, and direct synchronization request. Retain `/api/v1/run` only as
  a clearly deprecated compatibility endpoint.
- Document client conflict handling: discard edits and reload current state for
  `cache_revision_stale` or `playlist_changed`; prompt for a refresh when the
  cache is not ready; and show a retryable generic message for
  `spotify_failure`.

## 6. Final verification

- Run `./gradlew ktlintFormat` from `kt/`, review its diff, and stage any Kotlin
  formatting changes explicitly.
- Run `./gradlew lint`, the focused API/store migration tests, and then
  `./gradlew build`.
- Confirm that no test performs network calls and that fixtures contain only
  synthetic, sanitized data.
- Stage modified source files, tests, OpenAPI, documentation, migrations, and
  sanitized fixtures. Leave this plan document unstaged.
