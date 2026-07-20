# Kotlin API evolution plan — remaining work

This document now describes only work that is not complete. Completed readiness,
recipe, service, Spotify compatibility, and verification work is recorded in the
appendix.

The target is a browser-facing JSON API for the independent Kotlin service under
`kt/`. Butler remains the backend-for-frontend: browsers call Butler, Butler
calls Spotify, and Spotify credentials never enter browser JavaScript.

## Remaining target contract

- Authenticate every `/api/v1` endpoint with a Butler session cookie. Keep only
  a minimal, unauthenticated `GET /health` endpoint public.
- Keep access and refresh tokens server-side. The browser receives only an
  opaque session cookie and a CSRF token through the session resource.
- Treat previews as immutable, short-lived server-owned `Generation` resources.
  Sync accepts a generation ID and never accepts a client seed or plan.
- Publish opaque cache, catalog, definition, recipe, generation, and operation
  revisions where the resource contracts require them.
- Represent refresh and sync work as bounded, idempotent `Operation` resources.
- Resolve managed playlists to Spotify IDs and never use mutable names as the
  long-term identity boundary.
- Keep the browser GUI and API on one origin by default.

## 1. Complete the cache and managed-playlist foundation

Extend the SQLDelight schema and store boundary before exposing current-playlist
views:

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
  unknown future item types in the current view, while excluding unsupported
  types from generated track playlists.
- Add normalized candidate fields and artist relations needed by supported
  recipes; keep album, duration, explicitness, and complete artist identity
  typed.
- Fetch the complete replacement snapshot before opening the write transaction.
  Replace cache tables and publish the revision atomically. A failed refresh
  must leave the prior completed revision usable.
- Add `managed_playlists` keyed by definition ID/revision and Spotify playlist
  ID. Permit exact-name adoption only for one owned match; return a stable
  conflict for multiple matches.
- Carry the migration through parser compatibility, fixture export/scrubbing,
  synthetic tables, and contract tests for offsets, duplicates, inaccessible
  items, non-track items, nulls, ordering, and atomic replacement.

## 2. Add the authenticated HTTP/API foundation

Create an application facade so HTTP parsing and response mapping do not own
business policy:

- Add an OpenAPI 3.1 document as the authoritative route, method, security,
  status, and schema contract.
- Add dedicated serializable wire DTOs. Do not expose persistence models,
  Spotify response models, raw JSON, seeds, tokens, or secret material.
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

## 3. Finish recipe application integration

The recipe model and deterministic engine are complete, but the API-facing
recipe service and definition contract remain:

- Add a capability registry describing available sources, fields, predicates,
  dimensions, rankings, orderings, schema versions, and complexity limits.
- Add a validated recipe compiler that resolves sources, verifies cache
  capabilities, and optionally pushes allowlisted predicates into parameterized
  SQL without changing recipe semantics.
- Keep distinctness, deterministic ranking, quota admission, target cutoff, and
  final ordering in the versioned execution engine unless an optimizer proves
  identical output.
- Add complexity/resource limits for AST depth, fan-out, list sizes, targets,
  quotas, and estimated candidate work. Reject unknown or unavailable fields
  with stable actionable errors.
- Add read-only built-in recipe resources first:

  ```text
  GET /api/v1/recipe-capabilities
  GET /api/v1/recipes?limit=50&cursor=...
  POST /api/v1/recipes/validate
  GET /api/v1/recipes/{recipeId}
  ```

- Add catalog snapshots with injected clock, opaque `catalogRevision`, stable
  built-in IDs, immutable recipe revisions, rolling-year inputs, and concrete
  definition revisions.
- Refactor catalog, planning, and generation callers to use the recipe/catalog
  contract. Retire `PlaylistQuery` as the persisted and API-facing definition
  contract only after dual execution remains green.

## 4. Add catalog, library, and current-playlist resources

Implement these owner-scoped resources using the cache and catalog revisions:

```text
GET  /api/v1/playlists
POST /api/v1/playlists
GET  /api/v1/playlists/{definitionId}

GET  /api/v1/library
POST /api/v1/library/refresh

GET  /api/v1/playlists/{definitionId}/current
GET  /api/v1/playlists/{definitionId}/current/items?limit=50&cursor=...
```

Requirements:

- The playlist collection describes managed definitions, recipe links,
  definition/catalog revisions, rolling inputs, mapping state, and resource
  links. User-created definitions may remain disabled initially.
- Library status exposes `empty`, `ready`, `refreshing`, or `stale`, cache
  revision, owner, refresh operation link, completion time, and bounded counts.
  It never exposes raw track data in the metadata response.
- Refresh requires `Idempotency-Key`, only refreshes the cache, and returns a
  `library_refresh` operation. It must not clean duplicates, plan playlists, or
  mutate Spotify playlists.
- Current metadata must not execute recipes or call Spotify. Return `current:
  null` when no managed mapping exists.
- Current items use explicit `(playlist_id, position)` ordering, preserve
  inaccessible/unsupported entries, and use opaque cursors bound to the cache
  revision and filters. Return `409 cursor_stale` when the revision changes.

## 5. Add operation and immutable-generation resources

Add bounded `OperationStore` and complete the generation resource boundary:

```text
GET  /api/v1/operations?status=running&type=playlist_sync&limit=50&cursor=...
GET  /api/v1/operations/{operationId}

GET    /api/v1/playlists/{definitionId}/generations?limit=50&cursor=...
POST   /api/v1/playlists/{definitionId}/generations
GET    /api/v1/playlists/{definitionId}/generations/{generationId}
GET    /api/v1/playlists/{definitionId}/generations/{generationId}/items?set=desired&limit=50&cursor=...
DELETE /api/v1/playlists/{definitionId}/generations/{generationId}
```

- Operations use `queued`, `running`, `succeeded`, and `failed` states, bounded
  recent history, owner isolation, unguessable IDs, and discriminated results.
  Cancellation is not implied until implemented.
- Refresh and sync creation require `Idempotency-Key`. Reuse with the same
  owner/request returns the original operation; reuse with a different request
  returns `409 idempotency_conflict`.
- Generation creation requires a ready cache, captures at least 256 bits from
  `SecureRandom`, performs no Spotify writes, and stores the exact ordered
  desired URIs, diff sets, definition, recipe, catalog/cache revisions, seed,
  algorithm version, and expiry.
- The browser receives sanitized projections only. The server record is the
  authority for future sync.
- Enforce bounded retention, generation expiry, ownership, deletion rules, and
  stale cache/catalog/definition/algorithm revision checks.

## 6. Add per-playlist sync and recovery

Implement:

```text
POST /api/v1/playlists/{definitionId}/syncs
```

The request contains only a `generationId` and requires `Idempotency-Key`.

- Validate owner, route, expiry, unused/applied state, and all captured
  revisions before creating the operation.
- Lock the concrete playlist or definition instance. Immediately before
  mutation, fetch live metadata and compare the expected snapshot ID; report
  `playlist_changed` without mutating on conflict.
- For unmapped definitions, repeat exact-name adoption/mapping checks while
  holding the definition lock. Never create a duplicate arbitrarily.
- Apply the stored ordered URIs exactly; never rerun selection during sync.
- Capture mutation snapshot IDs, serialize cache publication with refreshes,
  and atomically update the managed mapping and targeted cache projection after
  an authoritative post-write read.
- Return sanitized partial results for later-batch failures, mark the cache
  stale, and require refresh after partial mutation or failed post-write read.
- Make retries safe through idempotency and applied-generation records.

## 7. Preserve and then retire the legacy run

Add the transitional resource:

```text
POST /api/v1/run
```

It may compose refresh, duplicate cleanup, generation creation, and multiple
sync operations, but must use the same lower-level services and safety checks.
No GUI workflow should depend on it. Duplicate cleanup remains separate from
library refresh and playlist sync. Deprecate the all-at-once callback only after
the GUI uses the narrow resources.

## 8. Verification and deployment gates

Before enabling browser access beyond local development:

- [ ] Add OpenAPI and route contract tests.
- [ ] Test session ownership, allowlisting, PKCE/state replay, rotation,
  refresh locking, CSRF, Origin checks, cookie attributes, and credential
  absence from responses/logs.
- [ ] Test operation isolation: refresh never mutates playlists, current view
  never generates, generation never calls Spotify, and one sync never touches
  another definition.
- [ ] Test idempotency, expiry, stale revisions, cursor staleness, live
  snapshot conflicts, partial batch failure, and cache atomicity.
- [ ] Test current Spotify item ordering, duplicate positions, inaccessible and
  unknown item types, nullable fields, and multi-page offsets.
- [ ] Add HTTPS callback/origin configuration, secure cookies, trusted-proxy
  and Host checks, quotas, rate limits, timeouts, and monitoring.
- [ ] Keep LAN binding opt-in and document that HTTP on a LAN does not provide
  HTTPS-equivalent cookie confidentiality.
- [ ] Add encrypted or OS-protected persistent token storage only if restart
  survival is required; never store refresh tokens as plaintext SQLite data.

## Rollout sequence for the remaining work

1. Extend the cache schema, atomic revision publication, item ordering, and
   managed mapping while retaining fixture compatibility.
2. Add the application facade, OpenAPI/DTO/error infrastructure, sessions,
   PKCE, refresh handling, CSRF, and session endpoints.
3. Add the recipe capability/compiler boundary and catalog revisions.
4. Add catalog, library, current-playlist, and operation resources.
5. Add immutable generations and per-playlist sync with snapshot checks,
   idempotency, cache publication, and partial-failure recovery.
6. Add the transitional `/api/v1/run`, migrate the GUI, and deprecate the
   all-at-once callback.
7. Complete HTTPS/LAN deployment hardening, quotas, rate limits, and monitoring.

## Appendix: completed work

The following work is complete and is retained here as the compatibility
baseline for the remaining API migration.

### Existing Kotlin service baseline

- Independent Kotlin Gradle service under `kt/`, using the repository Gradle
  wrapper, Kotlin 2.4, and the Java 25 toolchain.
- OAuth `/start` and `/callback` flow with validated, expiring authorization
  state; local callback URI remains `http://127.0.0.1:8888/callback`.
- SQLite cache loading, typed SQLDelight queries, playlist planning, playlist
  mutation, duplicate saved-track cleanup, dry-run support, and Butler
  orchestration behind focused service boundaries.
- Spotify response capture, fixture building/scrubbing, sanitized committed
  fixtures, and real parser-to-SQLite cache contract tests.

### Testing readiness gate

- All 15 legacy definitions are characterized in sanitized SQLite fixtures.
- Cutoff-heavy fixtures cover global and per-artist limits, nulls, duplicates,
  year boundaries, Discover Weekly composition, planning diffs, and exact
  deterministic results.
- Legacy and recipe execution run over shared fixture data. Non-random legacy
  output is exact; legacy random behavior is checked by eligibility, count, and
  quota invariants.
- Fixed-seed ordered recipe goldens are recorded and checked across insertion
  order variants, SQLite close/reopen, serialization, and the generation-to-
  mutation boundary.
- Playlist selection tests emit explicit actions, eligibility/distinct counts,
  quota rejections, and ordered selected URIs.
- Before/after verbose test logs were captured at the repository root and the
  normalized deterministic selection results matched.

### Canonical recipe and deterministic generation foundation

- Immutable serializable `PlaylistRecipe` AST with typed sources, predicates,
  distinctness, dimensions, quotas, ranking, and ordering policies.
- Optional Kotlin DSL constructs the same immutable recipe model.
- Canonical recipe encoding and revision hashing, validation, deterministic
  SHA-256 ranking/order domains, explicit tie-breaking, null/range semantics,
  quota admission, and target handling.
- All built-in definitions resolve to canonical recipes while the legacy
  `PlaylistQuery` path remains available for migration.
- Basic in-memory `GenerationStore` and `PlaylistGenerationService` persist the
  seed, recipe revision, cache revision, algorithm version, and ordered desired
  tracks for contract testing.

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
