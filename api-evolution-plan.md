# Kotlin API evolution plan — remaining work

This document describes the remaining work for the browser-facing API. The
appendix records only completed implementation facts that are relevant to the
desired implementation.

The target is a browser-facing JSON API for the independent Kotlin service under
`kt/`. Butler remains the backend-for-frontend: browsers call Butler, Butler
calls Spotify, and Spotify credentials never enter browser JavaScript.

## Remaining target contract

- Authenticate every `/api/v1` endpoint with a Butler session cookie. Keep only
  a minimal, unauthenticated `GET /health` endpoint public.
- Keep access and refresh tokens server-side. The browser receives only an
  opaque session cookie and a CSRF token through the session resource.
- Treat a managed playlist as an ordered, client-editable list of Spotify track
  IDs. The browser may reorder, remove, insert, and retain duplicate IDs, then
  POST the final list back for synchronization.
- Keep playlist responses ID-only. Return song metadata through a separate,
  cacheable enrichment resource that accepts one track ID or a bounded list of
  track IDs; clients may cache those enrichment responses.
- Playlist content is never generated server-side. The submitted ordered ID
  list is the complete mutation authority for that operation.
- Publish opaque cache, playlist, and operation revisions where the resource
  contracts require them.
- Represent refresh and sync work as bounded, idempotent `Operation` resources.
- Resolve managed playlists to Spotify IDs and never use mutable names as the
  long-term identity boundary.
- Keep the browser GUI and API on one origin by default.

## 1. Complete the cache and managed-playlist foundation

Extend the SQLDelight schema and store boundary before exposing current-playlist
or song-enrichment views:

- Add cache metadata for opaque `cache_revision`, `sync_timestamp_millis`, the
  owner Spotify user ID, and completion state. Publish a new revision only
  after a complete successful replacement.
- Expand playlist metadata with description, nullable public/collaborative
  flags, owner ID, snapshot ID, item count, item accessibility/status, and
  display URL metadata.
- Add a normalized `playlist_items` projection with `playlist_id`, zero-based
  `position`, nullable added-at/added-by data, local/type/playable flags,
  nullable item ID/URI, and complete item JSON. Preserve raw upstream JSON for
  compatibility, not as the query surface.
- Preserve duplicate IDs at different positions and derive positions from the
  response offset plus item index. Never use insertion order or `rowid`.
- Preserve inaccessible items, episodes, local items, unavailable items, and
  unknown future item types in the current view. Mark only playable Spotify
  tracks as enrichable playlist songs; unsupported item types must never be
  silently converted into track IDs.
- Add normalized song and artist projections for enrichment. Keep album,
  duration, explicitness, complete artist identity, availability, and URI
  typed; raw upstream JSON remains compatibility data, not the query surface.
- Fetch the complete replacement snapshot before opening the write transaction.
  Replace cache tables and publish the revision atomically. A failed refresh
  must leave the prior completed revision usable.
- Add `managed_playlists` keyed by definition ID/revision and Spotify playlist
  ID. Permit exact-name adoption only for one owned match; return a stable
  conflict for multiple matches.
- Carry this schema and parser change through fixture compatibility,
  fixture export/scrubbing, synthetic tables, and contract tests for offsets,
  duplicates, inaccessible items, non-track items, nulls, ordering, and atomic
  replacement.

## 2. Add the authenticated HTTP/API foundation

Create an application facade so HTTP parsing and response mapping do not own
business policy:

- Add an OpenAPI 3.1 document as the authoritative route, method, security,
  status, and schema contract.
- Add dedicated serializable wire DTOs. Do not expose persistence models,
  Spotify response models, raw JSON, tokens, or secret material.
- Add the stable error envelope with `code`, `message`, `requestId`, and
  sanitized details. Enforce the planned status mapping for malformed input,
  auth, ownership, conflicts, validation, rate limits, and Spotify failures.
- Add request-correlation IDs, JSON content-type checks, bounded request bodies,
  bounded pagination, and JSON `404` responses. Remove catch-all `200 Hello
  World!` behavior for unknown routes.
- Add `GET /health` outside `/api/v1`; it must expose readiness only.
- Add `SessionStore` with opaque, expiring, owner-scoped sessions. Store access
  and refresh tokens server-side and rotate the session after authentication.
- Keep OAuth navigation at `GET /start` and `GET /callback`, but add configured
  relative `returnTo` validation, server-side PKCE, state replay protection,
  refresh-token handling, and safe failure responses.
- Add `GET /api/v1/session`, `POST /api/v1/session/refresh`, and
  `DELETE /api/v1/session`.
- Require the session's CSRF token in a custom header on every state-changing
  request and validate trusted `Origin` values. Configure secure cookie
  attributes for HTTPS deployments.
- Enforce single-user allowlisting and owner checks for every resource.

## 3. Add ID-only song and playlist resources

Implement these owner-scoped resources using the cache revision:

```text
GET  /api/v1/playlists
POST /api/v1/playlists
GET  /api/v1/playlists/{definitionId}

GET  /api/v1/library
POST /api/v1/library/refresh

GET  /api/v1/songs/{trackId}
GET  /api/v1/songs?ids=trackId1,trackId2,...

GET  /api/v1/playlists/{definitionId}/current
GET  /api/v1/playlists/{definitionId}/current/items?limit=50&cursor=...
```

Requirements:

- Playlist responses contain playlist identity, mapping state, revisions, and
  ordered track references only. They must not embed full song objects or raw
  Spotify JSON. User-created definitions may remain disabled initially, but a
  definition directly holds an editable list.
- Library status exposes `empty`, `ready`, `refreshing`, or `stale`, cache
  revision, owner, refresh operation link, completion time, and bounded counts.
  It never exposes raw track data in the metadata response.
- Refresh requires `Idempotency-Key`, only refreshes the cache, and returns a
  `library_refresh` operation. It must not clean duplicates or mutate Spotify
  playlists.
- Song enrichment accepts one ID or a bounded list, preserves requested order,
  returns typed album/artist/duration/explicitness/availability data, and
  reports missing or unavailable IDs without returning raw upstream payloads.
  Responses are cacheable and carry the cache revision or equivalent validator
  needed to invalidate client-side entries.
- Current metadata must not perform selection or call Spotify. Return `current:
  null` when no managed mapping exists.
- Current items use explicit `(playlist_id, position)` ordering and ID-only item
  records. Preserve inaccessible/unsupported entries with their type/status and
  nullable ID, while a client-editable track list contains only playable track
  IDs. Use opaque cursors bound to the cache revision and filters; return
  `409 cursor_stale` when the revision changes.

## 4. Add operations and client-submitted playlist sync

Add bounded `OperationStore` and make the submitted ordered list the sync
contract:

```text
GET  /api/v1/operations?status=running&type=playlist_sync&limit=50&cursor=...
GET  /api/v1/operations/{operationId}
POST /api/v1/playlists/{definitionId}/syncs
```

- Operations use `queued`, `running`, `succeeded`, and `failed` states, bounded
  recent history, owner isolation, unguessable IDs, and discriminated results.
  Cancellation is not implied until implemented.
- Refresh and sync creation require `Idempotency-Key`. Reuse with the same
  owner and request returns the original operation; reuse with a different
  request returns `409 idempotency_conflict`.
- The sync request contains an ordered `trackIds` array plus the base playlist
  snapshot/revision the browser edited. IDs are converted to Spotify URIs only
  inside the server. Preserve order and duplicate IDs exactly; reject unknown,
  non-track, unavailable, or over-limit entries with stable validation errors.
- Validate owner, route, cache readiness, and the submitted list before creating
  the operation. The submitted IDs are the only playlist-content authority;
  client song metadata is display data and is not trusted for mutation.
- Lock the concrete playlist or definition instance. Immediately before
  mutation, fetch live metadata and compare the expected snapshot ID; report
  `playlist_changed` without mutating on conflict. The client can then refresh,
  reapply its edits, and submit again.
- For unmapped definitions, repeat exact-name adoption/mapping checks while
  holding the definition lock. Never create a duplicate arbitrarily.
- Apply the submitted ordered URIs exactly; never rerun selection during sync.
- Capture mutation snapshot IDs, serialize cache publication with refreshes,
  and atomically update the managed mapping and targeted cache projection after
  an authoritative post-write read.
- Return sanitized partial results for later-batch failures, mark the cache
  stale, and require refresh after partial mutation or failed post-write read.
- Make retries safe through idempotency and applied-request records.

## 5. Preserve and then retire the legacy run

Add the transitional resource:

```text
POST /api/v1/run
```

It may compose refresh, duplicate cleanup, client-submitted sync operations, and
multiple playlist updates, but must use the same lower-level services and safety
checks. No GUI workflow should depend on it. Duplicate cleanup remains separate
from library refresh and playlist sync. Deprecate the all-at-once callback only
after the GUI uses the narrow resources.

## 6. Verification and deployment gates

Before enabling browser access beyond local development:

- [ ] Add OpenAPI and route contract tests.
- [ ] Test session ownership, allowlisting, PKCE/state replay, rotation,
  refresh locking, CSRF, Origin checks, cookie attributes, and credential
  absence from responses/logs.
- [ ] Test operation isolation: refresh never mutates playlists, current view
  never calls Spotify, song enrichment does not mutate playlists, and one sync
  never touches another definition.
- [ ] Test ID-only playlist responses, single and batch song enrichment,
  requested order, client-side duplicate IDs, cache validators, and missing
  songs.
- [ ] Test idempotency, stale revisions, cursor staleness, live snapshot
  conflicts, partial batch failure, and cache atomicity.
- [ ] Test current Spotify item ordering, duplicate positions, inaccessible and
  unknown item types, nullable fields, and multi-page offsets.
- [ ] Add HTTPS callback/origin configuration, secure cookies, trusted-proxy
  and Host checks, quotas, rate limits, timeouts, and monitoring.
- [ ] Keep LAN binding opt-in and document that HTTP on a LAN does not provide
  HTTPS-equivalent cookie confidentiality.
- [ ] Add encrypted or OS-protected persistent token storage only if restart
  survival is required; never store refresh tokens as plaintext SQLite data.

## Rollout sequence for the remaining work

1. Extend the cache schema, atomic revision publication, item ordering, typed
   song/artist projections, and managed mapping while retaining fixture
   compatibility.
2. Add the application facade, OpenAPI/DTO/error infrastructure, sessions,
   PKCE, refresh handling, CSRF, and session endpoints.
3. Add ID-only playlist/current resources, cacheable single and batch song
   enrichment, and library refresh.
4. Add operations and client-submitted playlist sync with snapshot checks,
   idempotency, cache publication, and partial-failure recovery.
5. Add the transitional `/api/v1/run`, migrate the GUI to local editing plus
   final-list submission, and deprecate the all-at-once callback.
6. Complete HTTPS/LAN deployment hardening, quotas, rate limits, and monitoring.

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
