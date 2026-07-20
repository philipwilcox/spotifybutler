# Kotlin API evolution plan — remaining work

This document lists only work that is still incomplete after reviewing the
uncommitted Kotlin/API changes against `HEAD` and the API demo script. The
appendix records the completed implementation facts that form the compatibility
baseline.

The target remains a browser-facing JSON API for the independent Kotlin service
under `kt/`. Butler is the backend-for-frontend: browsers call Butler, Butler
calls Spotify, and Spotify credentials never enter browser JavaScript.

## Invariants for the remaining work

- Keep only a readiness-only `GET /health` endpoint unauthenticated. Every
  `/api/v1` resource must be owner-scoped and session-authenticated.
- Keep access and refresh tokens server-side. Use an opaque session cookie and
  expose the CSRF token only through the session resource.
- Treat a managed playlist as an ordered, client-editable list of Spotify track
  IDs. Preserve order and duplicate IDs exactly; the submitted list is the
  complete mutation authority.
- Keep playlist responses ID-only. Song metadata belongs in a separate,
  cacheable enrichment resource.
- Never regenerate a playlist during synchronization. Publish opaque cache,
  playlist, and operation revisions wherever the resource contract requires
  them.

## 1. Finish the durable cache and definition boundary

The schema, parser, and transactional replacement foundation exists, but the
new projections are not yet the complete persisted/query contract:

- Extend `SpotifyTableSnapshot`, `ExpectedTables`, fixture export, scrubbing,
  reports, and committed synthetic fixtures for `cache_metadata`,
  `playlist_details`, `playlist_items`, `songs`, `song_artists`, and
  `managed_playlists`. The cache contract currently still verifies only the
  legacy six exported tables.
- Make song and artist API reads use the normalized projections. Keep complete
  upstream JSON only as compatibility data; do not reconstruct enrichment
  responses by decoding `track_json`.
- Persist user playlist definitions with their owner, ordered IDs, and
  definition revision. The current `ConcurrentHashMap` loses definitions on
  restart, does not provide durable owner isolation, and the user-definition
  revision is not derived from the editable track list.
- Filter exact-name adoption candidates by the authenticated owner before
  deciding whether there is one match or a conflict. Return one stable conflict
  when multiple owned matches remain, and never let a foreign cached playlist
  block or satisfy adoption.
- Add explicit cache lifecycle state for empty, refreshing, ready, and stale
  data. A failed refresh must leave the prior completed revision usable while
  reporting the failed/stale state and operation link to clients.
- Keep revision publication atomic for refreshes and targeted mutation
  publication. Define which owner, completion state, timestamp, and revision
  survive restart and migration, and add schema-versioned migrations rather
  than relying on a newly created database.

## 2. Harden the HTTP, OAuth, and security contract

- Make the OpenAPI document executable as the route contract: validate it in
  tests, cover every implemented route and error response, and keep method,
  security, content-type, pagination, and status behavior synchronized with the
  embedded server.
- Return one sanitized error envelope for transport, OAuth, and API failures.
  Add the generated/request-supplied request ID to the response header as well
  as the body. Server-level errors currently use a different envelope and the
  API-generated ID is not returned as a header.
- Sanitize token-exchange failure handling so Spotify response bodies, client
  credentials, access tokens, refresh tokens, authorization codes, and secret
  properties cannot enter exceptions, responses, or logs.
- Require and validate a configured trusted `Origin` for state-changing
  requests. Wire trusted origins, HTTPS callback/origin settings, secure cookie
  mode, trusted-proxy handling, and Host validation from deployment
  configuration; do not silently disable Origin checks when the configured set
  is empty.
- Complete session refresh concurrency control: serialize refreshes per
  session, rotate exactly once, invalidate the old session, and preserve the
  newest refresh token when Spotify rotates it. Enforce the single-user
  allowlist as an explicit resource policy, not only as a callback check.
- Keep bounded request bodies and pagination, but add limits and validation to
  operation history and all cursor inputs. Unknown methods and paths must
  consistently return JSON errors with the correct request ID.

## 3. Complete the ID-only resource semantics

- Make playlist definitions and all playlist lookups owner-scoped, including
  `GET /api/v1/playlists/{definitionId}` and user-created definitions. Add the
  definition update/persistence path needed for a client-editable list, or
  explicitly keep creation disabled until that path exists.
- Make library status accurately report `refreshing` and `stale`, expose the
  active refresh operation, and keep refresh work bounded and idempotent. A
  failed refresh must not continue to look `ready` merely because an older
  cache revision exists.
- Make song enrichment read normalized album/artist fields, preserve requested
  order, and distinguish missing IDs from known-but-unavailable IDs. Add HTTP
  cache validators or equivalent cache headers in addition to the revision
  field in the JSON body.
- Bind current-item cursors to the complete query shape, including every
  applicable filter and page contract. Add real cursor pagination to operation
  history; it currently returns a limited list with no continuation cursor.
- Keep current metadata purely cache-backed and keep inaccessible, local,
  unavailable, episode, and future item types visible in the item resource.
  Add contract coverage that proves no current or enrichment read calls Spotify
  or mutates a playlist.

## 4. Finish operations and client-submitted synchronization

- Replace the in-memory, unbounded operation map with an atomic idempotency
  index and bounded recent history (persistent if restart survival is part of
  the deployment contract). Owner, type, key, and request fingerprint lookup
  plus create must be one atomic operation; concurrent requests must not create
  duplicate operations.
- Define whether work is queued asynchronously or deliberately completed in
  the request, then make the `202`/`queued`/`running`/`succeeded` contract match
  that choice. Retain discriminated, sanitized results and partial outcomes.
- Require the edited base cache revision and playlist snapshot where a sync
  needs optimistic concurrency. Lock the concrete definition/playlist before
  rechecking ownership, exact-name adoption, cache readiness, and live Spotify
  metadata. Fail closed if the live snapshot cannot be read.
- Before mutating, compare the live snapshot while holding the lock. For an
  unmapped definition, repeat exact-name adoption/mapping under that same lock;
  never choose a duplicate or create an arbitrary replacement.
- Make Spotify replacement batch-safe. Record the applied request and mutation
  snapshot IDs, handle a later-batch failure as a partial mutation, mark the
  affected cache stale, and require a refresh when the authoritative post-write
  read is unavailable.
- After a successful mutation, perform an authoritative post-write read and
  atomically publish the ordered item projection, playlist snapshot, managed
  mapping, cache revision, and completion state. Do not synthesize a fully
  playable cache view that discards unsupported or inaccessible items.
- Make retries safe across process races and restarts through persisted
  idempotency/applied-request records. Return `playlist_changed`, stale
  revision, validation, rate-limit, Spotify, and partial-failure errors with
  stable codes and sanitized details.

## 5. Preserve and then retire the legacy run

Keep the transitional `POST /api/v1/run` only as an adapter over the completed
lower-level services. It must be able to compose refresh, duplicate cleanup,
client-submitted sync, and multiple playlist updates while retaining the same
locks, idempotency, ownership, cache publication, and partial-failure rules.
The current endpoint only refreshes the cache. Do not make a GUI depend on it;
migrate the GUI to local editing and final-list submission before removing or
fully deprecating the all-at-once callback workflow.

## 6. Verification and deployment gates

Before enabling browser access beyond local development, add the missing
verification and hardening below:

- OpenAPI validation and route/status/security contract tests, including
  transport-level errors and request-ID propagation.
- Integration tests for session expiry and refresh races, owner allowlisting,
  owner isolation, OAuth failure sanitization, PKCE/state replay, CSRF,
  required Origin checks, secure cookie attributes, Host/proxy policy, and
  credential absence from responses and logs.
- Tests for persistent definitions and normalized projections, fixture export
  and scrubbing of all new tables, cache lifecycle states, revision atomicity,
  restart behavior, and foreign-playlist adoption conflicts.
- Tests for unavailable song reporting, HTTP cache validators, operation
  pagination, atomic idempotency creation, concurrent syncs, required base
  revisions, live snapshot conflicts, unmapped adoption, partial batch failure,
  authoritative post-write reads, stale-cache recovery, and retry behavior.
- Configure HTTPS callback/origin policy, secure cookies, trusted-proxy and
  Host checks, quotas, rate limits, timeouts, and monitoring. Keep LAN binding
  opt-in and document that HTTP on a LAN does not provide HTTPS-equivalent
  cookie confidentiality.
- Add encrypted or OS-protected persistent token storage only if restart
  survival is required; never store refresh tokens as plaintext SQLite data.

## Rollout sequence for the remaining work

1. Complete normalized cache/fixture persistence, definition ownership, cache
   lifecycle state, and migrations.
2. Lock down the HTTP/OAuth contract, error/request-ID behavior, configuration,
   and session concurrency.
3. Finish owner-scoped definitions, cacheable enrichment, library lifecycle,
   and cursor semantics.
4. Finish operation durability, locking, exact submitted-list synchronization,
   post-write reconciliation, and partial-failure recovery.
5. Expand the transitional `/run`, migrate the GUI, and deprecate the legacy
   all-at-once workflow.
6. Complete verification, HTTPS/LAN hardening, quotas, rate limits, and
   monitoring.

## Appendix: completed work

The following work is complete and is retained here as the compatibility
baseline for the desired API implementation.

### Existing Kotlin service baseline

- Independent Kotlin Gradle service under `kt/`, using the repository Gradle
  wrapper, Kotlin 2.4, and the Java 25 toolchain.
- OAuth `/start` and `/callback` flow with validated, expiring authorization
  state; local callback URI remains `http://127.0.0.1:8888/callback`.
- SQLite cache loading, typed SQLDelight queries, playlist mutation, duplicate
  saved-track cleanup, dry-run support, and Butler orchestration behind focused
  service boundaries.
- Spotify response capture, fixture building/scrubbing, sanitized committed
  fixtures, and real parser-to-SQLite cache contract tests.

### Spotify API compatibility slice

- Playlist creation uses `/v1/me/playlists`.
- Playlist add/replace operations use `/v1/playlists/{playlistId}/items`.
- Saved-library removal uses `/v1/me/library` with encoded URI query batches of
  40, matching the current Spotify schema.
- Playlist parsing accepts current `items`/`item` shapes and legacy
  `tracks`/`track` fixture shapes; capture and fixture tooling recognizes both
  playlist item endpoint names.
- Mutation endpoint, batch-size, parser, and legacy-fixture compatibility tests
  pass.

### Cache and parser foundation from the reviewed changes

- Added cache metadata, playlist details, ordered playlist-item rows, typed song
  and artist projections, and managed-playlist mapping tables while retaining
  raw upstream JSON for compatibility.
- Parsed playlist offsets into explicit positions and preserved duplicates,
  inaccessible items, local items, unavailable tracks, episodes, and unknown
  item types without converting them into false track IDs.
- Added typed album, artist, availability, duration, explicitness, playlist
  ownership, snapshot, and display URL fields to the cache models.
- Fetches complete replacement data before the SQLite transaction and publishes
  the completed cache revision atomically; a failed replacement leaves the
  prior completed revision usable.

### HTTP/API foundation from the reviewed changes

- Added the OpenAPI 3.1 document, serializable wire DTOs, sanitized API error
  envelope, application facade, readiness endpoint, bounded request bodies,
  JSON 404s, and owner/session route plumbing.
- Added opaque expiring in-memory sessions, server-side token retention, session
  rotation, callback return-to validation, server-side PKCE, state replay
  protection, refresh-token handling, CSRF checks, and configurable cookie
  security flags.
- Added ID-only playlist/current/item views, bounded single and batch song
  enrichment, cache revisions in resource responses, cursor-stale handling,
  library refresh, operation resources, and a non-mutating sync preview.
- Added basic validation for submitted track IDs, duplicate-preserving Spotify
  URI conversion, optimistic snapshot checks when supplied, and owner-scoped
  idempotency behavior for refresh and sync operations.
- Added `scripts/api-demo.sh` to demonstrate readiness, session, refresh,
  current-item, song-enrichment, and sync-preview calls.

### Verification added in the reviewed changes

- Added parser tests for current playlist item shapes, response offsets,
  duplicate positions, inaccessible items, and unsupported item types.
- Added cache replacement atomicity coverage and API tests for ID-only current
  views, ordered item pagination, song order/missing IDs, CSRF rejection,
  duplicate-preserving sync submission, and idempotent replay.
- Added security tests for session rotation, server-side credential retention,
  idempotency conflicts, and PKCE/state single-use behavior.
