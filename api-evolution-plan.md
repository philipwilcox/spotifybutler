# Kotlin API evolution plan

This plan defines a browser-facing HTTP API for the independent Kotlin service
under `kt/`. Butler is a backend-for-frontend: browsers call Butler, Butler
calls Spotify, and Spotify credentials never enter browser JavaScript.

The API should expose small resources that compose cleanly: library snapshots,
managed playlist definitions, immutable preview generations, and asynchronous
operations. The legacy all-at-once run remains only as a migration path.

## Final design decisions

- Authenticate every `/api/v1` endpoint with a Butler session cookie unless an
  endpoint is explicitly documented as public. The only initially public
  endpoint should be a minimal health check that exposes no Spotify data.
- Keep Spotify access and refresh tokens server-side. Do not define bearer
  authentication or native-client exchange endpoints until such a client is
  actually required.
- Treat a playlist preview as an immutable, short-lived server-owned
  `Generation` resource. Sync accepts a generation ID, not a client-supplied
  seed or plan.
- Give each successful cache replacement an opaque `cacheRevision`; timestamps
  are display data, not version identifiers.
- Use a versioned deterministic hash-ranking algorithm outside SQLite for
  random selection and final ordering. Do not use `ORDER BY RANDOM()` in any
  generation-bearing query.
- Define playlist behavior with one immutable, serializable `PlaylistRecipe`
  domain AST. An optional Kotlin DSL and future client JSON both construct the
  same model; neither SQL nor builder state is the recipe contract.
- Represent long-running refresh and sync work as `Operation` resources from
  the first API version. This avoids changing the contract when work later
  moves off the request thread.
- Resolve managed Spotify playlists to stored Spotify IDs. Names are mutable,
  non-unique display data and must not remain the long-term identity boundary.
- Keep the browser GUI and API on one origin by default. Cross-origin browser
  deployment is a separate configuration, not the default development mode.

## Verified upstream and cache prerequisites

### Cache schema and atomicity

Before exposing a current-playlist view, retain enough data to reproduce item
order and explain every cached entry:

- Add an opaque `cache_revision`, `sync_timestamp_millis`, owner Spotify user
  ID, and completion state to the cache metadata. Generate a new revision only
  after a complete successful replacement.
- Expand `playlists` with `description`, nullable `is_public`,
  `is_collaborative`, `owner_id`, `snapshot_id`, `items_total`, and an item
  accessibility/status field. Retain Spotify URL information needed for GUI
  attribution.
- Prefer `playlist_items` as the new table name. Store `playlist_id`, zero-based
  `position`, nullable `added_at`, nullable `added_by_id`, `is_local`, nullable
  `item_type`, `item_id`, `item_uri`, `is_playable`, and the complete
  `item_json`. Track-specific normalized columns and `track_json` may remain
  nullable for migration compatibility.
- Extend the candidate projection with normalized fields required by supported
  recipes, beginning with album ID, duration, explicitness, and complete artist
  identity. Use relation tables for multi-valued dimensions such as artists or
  future tags. Raw JSON remains a compatibility record, not the query surface.
- Use `(playlist_id, position)` as item identity. Duplicate item IDs at
  different positions are valid and must be preserved.
- Derive `position` as the response page's `offset` plus the item index. Never
  infer order from insertion order or SQLite `rowid`; current-item queries must
  explicitly order by `playlist_id, position`.
- Preserve unavailable items, episodes, local items, and unknown future item
  types. They may not be eligible for generated track playlists, but they must
  not silently disappear from the cached current view.

Spotify image URLs are temporary. Store image metadata from the latest refresh
only as best-effort display data, or fetch images separately when needed; do
not treat a cached cover URL as durable identity.

Fetch a complete replacement snapshot before opening the write transaction.
Replace all cache tables and publish the new cache revision atomically, so
readers see either the previous complete revision or the new complete revision,
never partially replaced content. A failed refresh must leave the prior
completed revision usable and record the failure only in operation state.

Add a managed-playlist mapping rather than continuing to identify playlists by
name:

```text
managed_playlists
  definition_id
  definition_revision
  spotify_playlist_id
  owner_id
  created_at
```

`definition_revision` identifies the concrete generated definition, including
rolling-year inputs and generated name. This preserves the current behavior in
which a changed rolling definition can create a new playlist, while preventing
duplicate names from being mistaken for identity. For an unmapped definition,
an initial exact-name adoption is allowed only when exactly one owned playlist
matches. Multiple matches return `409 playlist_mapping_ambiguous`; they must
never be resolved arbitrarily.

Carry all schema changes through parsing, SQLDelight, fixture export, fixture
scrubbing, and synthetic expected tables. Keep older captured fixtures readable
during migration. Cover multi-page position offsets, duplicate IDs, inaccessible
items, non-track items, nullable fields, explicit ordering, and atomic cache
replacement with contract tests.

## Current behavior to preserve during migration

The current service exposes only `/start`, `/callback`, and `/hello`. The
callback exchanges the Spotify authorization code and may refresh the cache,
remove duplicate saved tracks, plan every definition, and mutate every playlist
in one request. Unknown paths currently return `200 Hello World!`.

Useful domain behavior already exists below HTTP:

- `PlaylistQueries.definitions(...)` creates the 15 managed definitions using
  stable `PlaylistDefinitionId` values. Treat its current SQL-backed definitions
  as migration inputs: characterize them, express each one as a canonical
  `PlaylistRecipe`, then retire `PlaylistQuery` as a definition contract.
- `SpotifyCacheService` fetches and replaces the SQLite cache.
- `PlaylistPlanningService` derives desired and existing sets from SQLite.
- `PlaylistMutationService` creates or replaces Spotify playlists.

Refactor these capabilities behind narrower application services while keeping
the explicit legacy run available until the GUI has migrated.

## Common API contract

Use `/api/v1` JSON endpoints and check in an OpenAPI 3.1 document as the
authoritative path, method, security, status, and schema contract. Implement
wire models as dedicated `@Serializable` DTOs rather than exposing persistence
or Spotify response models. Define the session cookie as `cookieAuth`, document
the CSRF header on every unsafe operation, and apply cookie authentication to
the API by default rather than repeating an easy-to-forget opt-in per route.

Expose `GET /health` outside `/api/v1` for deployment probes. It returns only
process readiness and must not reveal user identity, cache contents, filesystem
paths, configuration, or upstream credentials.

Use these conventions consistently:

- `Content-Type: application/json` for JSON requests and responses.
- RFC 3339 UTC strings for API timestamps and opaque strings for revisions,
  cursors, generation IDs, and operation IDs.
- Lower-snake-case stable enum values on the wire. Kotlin enum names and class
  names are implementation details.
- Opaque cursor pagination for item collections, with `limit`, `nextCursor`,
  and a documented maximum. A cursor is bound to the resource revision and
  filters; return `409 cursor_stale` rather than silently paging another
  revision.
- `Location` headers on created generation and operation resources.
- `Idempotency-Key` on refresh and sync creation. Reusing a key with the same
  authenticated owner and request returns the original operation; reusing it
  with a different request returns `409 idempotency_conflict`.
- Clients must ignore unknown response fields. Requests should reject unknown
  fields initially so misspellings do not silently change mutation behavior.
- A request correlation ID in the response header and error body. Accept a
  valid client-provided ID or generate one; never use it as authorization.

Use one stable error envelope:

```json
{
  "error": {
    "code": "generation_stale",
    "message": "Create a new preview from the current library revision.",
    "requestId": "opaque-request-id",
    "details": {}
  }
}
```

Reserve statuses consistently:

- `400` for malformed syntax, query parameters, or JSON.
- `401` for a missing, expired, or no-longer-refreshable Butler session.
- `403` for an authenticated Spotify user who is not allowed to use this
  Butler deployment or for an operation forbidden by granted scopes.
- `404` for unknown definitions and resources not owned by the authenticated
  user. Do not reveal another user's generation or operation.
- `409` for cache, revision, idempotency, mapping, or concurrent-mutation
  conflicts.
- `422` for structurally valid requests whose values are not meaningful.
- `429` for Butler or Spotify rate limits, preserving `Retry-After` where
  available.
- `502` or `503` for non-authentication Spotify failures, with no upstream body
  or credentials exposed.

### Operation resource

Refresh and sync endpoints return `202 Accepted`, a `Location` header, and:

```json
{
  "operation": {
    "id": "opaque-operation-id",
    "type": "playlist_sync",
    "status": "queued",
    "createdAt": "2026-07-19T12:00:00Z",
    "startedAt": null,
    "finishedAt": null,
    "progress": null,
    "result": null,
    "error": null
  }
}
```

```text
GET /api/v1/operations?status=running&type=playlist_sync&limit=50&cursor=...
GET /api/v1/operations/{operationId}
```

Initial statuses are `queued`, `running`, `succeeded`, and `failed`; reserve
`cancelled` until cancellation is actually implemented. Results are a
discriminated union by operation type. The collection exposes bounded recent
history with optional status/type filters. Operation IDs are unguessable and
every list or lookup verifies the authenticated Spotify owner. Keep completed
operations for a documented bounded lifetime. Cancellation can be added later
with `DELETE`; do not imply cancellation support initially.

Use bounded executors, one active library refresh per owner, and one active sync
per Spotify playlist. Apply per-owner quotas to queued operations and stored
generations so internet exposure cannot turn these resources into unbounded
memory or work queues.

## Authentication and browser session

### OAuth entrypoints

Keep the unversioned redirect endpoints because they participate in browser
navigation rather than the JSON resource API:

```text
GET /start?returnTo=/app
GET /callback
```

`/start` establishes a session only. During migration,
`/start?mode=singlerun&refresh=true` may preserve the old all-in-one callback,
but no new client should depend on it. `returnTo` must be a configured relative
Butler path beginning with `/`; reject schemes, authorities, backslashes, and
protocol-relative values. Never redirect to an arbitrary caller-supplied URL.

Store each pending authorization server-side with a short expiry, one-time
state, browser-binding nonce, validated return path, requested scopes, PKCE
verifier, and creation time. Permit multiple pending transactions for the same
browser without relying on one mutable state-cookie value. Consume the pending
record before exchanging the code so callback retries cannot replay it.

Butler owns the Spotify OAuth transaction on behalf of the browser. Standardize
on Spotify's Authorization Code with PKCE request form: generate a
transaction-specific verifier and `S256` challenge in Butler, send the
challenge to Spotify, and exchange the returned code server-side using
`client_id` and the verifier. The browser never stores the verifier. Spotify
documents its PKCE and client-secret token request forms separately, so do not
send an undocumented combination of Basic client authentication and
`code_verifier`; cover the selected flow with a real-provider integration
check. Continue validating state even with PKCE. This follows
[RFC 9700](https://www.rfc-editor.org/rfc/rfc9700.html) and Spotify's
[PKCE contract](https://developer.spotify.com/documentation/web-api/tutorials/code-pkce-flow).

Request only scopes used by enabled features. Remove unused scopes such as
email and recently played access. Keep library modification scope disabled if
legacy duplicate cleanup is disabled, and request public-playlist modification
only if Butler will actually manage public playlists.

### Session API

```text
GET    /api/v1/session
POST   /api/v1/session/refresh
DELETE /api/v1/session
```

After callback, create a cryptographically random server-side session ID with
at least 128 bits of entropy and store the Spotify access token, refresh token,
granted scopes, access-token expiry, Spotify user, idle expiry, and absolute
expiry in `SessionStore`. The browser receives only the opaque session cookie.

`GET /session` returns user identity, session expiry, `refreshable`, granted
capabilities derived from scopes, and a CSRF token; it never returns Spotify or
Butler bearer tokens. `POST /session/refresh` ensures a fresh Spotify access
token and rotates the session ID. Normal Spotify-backed requests should also
refresh shortly before expiry under one refresh lock per session. `DELETE`
invalidates the server session and clears the cookie; it does not claim to
revoke Spotify authorization.

Preserve the prior refresh token when Spotify omits a replacement. A failed
refresh invalidates the session and returns `401 reauthorization_required`.
Distinguish `auth_required`, `session_expired`, and
`reauthorization_required` error codes.

### User isolation and authorization

The initial application has one shared cache and therefore must be explicitly
single-user. Configure an allowed Spotify user ID and reject any other user
immediately after `/callback`, discarding their tokens before creating a
session. Require authentication even for cached catalog, library, current-item,
generation, and operation reads because they expose private Spotify data.

If multi-user support is added later, partition every cache row, managed
playlist mapping, generation, operation, idempotency record, and session-owned
resource by Spotify user before allowing a second user. Adding more accepted
logins without that isolation is not a supported intermediate state.

Generation and operation resources should be owned by Spotify user rather than
one session ID, so routine session rotation does not orphan them. Continue to
enforce expiry and ownership on every access.

### Cookie, CSRF, and transport requirements

For HTTPS deployments use a host-only cookie such as
`__Host-spotify_butler_session` with `Secure`, `HttpOnly`, `SameSite=Lax`, and
`Path=/`, and no `Domain`. Rotate it after login, token refresh, and any other
privilege boundary. Never accept an unknown caller-supplied ID as a new
session. Enforce idle and absolute lifetimes and revoke server-side state on
logout.

Require the session's CSRF token in a custom header on every state-changing
request and validate `Origin` against the configured Butler origin. Require
`application/json` for mutation bodies and never mutate state through GET.
`SameSite` is defense in depth, not the complete CSRF defense.

Use `Cache-Control: no-store` on session and authorization-transition
responses. The served GUI should send a restrictive Content Security Policy,
`Referrer-Policy: no-referrer`, `X-Content-Type-Options: nosniff`, and a
frame-ancestors policy. Do not put authorization codes, session IDs, CSRF
tokens, access tokens, refresh tokens, or secret-bearing payloads in logs,
analytics, URLs, or error details.

Keep the GUI and API same-origin by default and disable CORS. If cross-origin
deployment is later unavoidable, allow only exact configured HTTPS origins,
credentials, methods, and headers; never combine credentials with a wildcard
origin.

### Deployment profiles

There are three materially different profiles:

1. Same-host development binds to `127.0.0.1` and uses exactly
   `http://127.0.0.1:8888/callback`. Its non-`Secure` development cookie must
   never be reused by a broader deployment.
2. A browser on another LAN device cannot use Butler's loopback callback:
   `127.0.0.1` would refer to the browser's device. Remote LAN browsers need a
   stable Butler HTTPS origin and the exact matching Spotify redirect URI,
   normally through local DNS and a trusted certificate or an HTTPS reverse
   proxy.
3. Internet exposure requires the same stable HTTPS callback plus HSTS, trusted
   proxy configuration, strict Host/origin validation, request-size and queue
   limits, rate limiting, timeouts, and monitoring for repeated auth and CSRF
   failures.

HTTP on a shared LAN does not protect session cookies from interception. Keep
LAN binding opt-in, firewall it to intended clients, and do not describe that
profile as equivalent to HTTPS.

Persist sessions only if restart survival is required. Store refresh tokens in
an encrypted or OS-protected secret store, never plaintext SQLite. A deployment
that uses in-memory sessions should document that restart requires login again.

## Declarative playlist recipe model

The canonical recipe is an immutable, serializable domain AST. It describes
playlist intent independently of SQL, Kotlin construction syntax, cache schema,
and execution optimizations. Built-in definitions, tests, persisted recipes,
and future API clients must all use this same model.

Every recipe decomposes into six independently extensible concepts:

1. `source`: saved tracks, top tracks, another playlist's items, union, or
   difference;
2. `predicate`: eligibility conditions such as release year, album, duration,
   artist, added date, explicitness, or an enriched tag/genre;
3. `distinctness`: normally by Spotify URI, with an explicit keep-all policy
   when a recipe intentionally permits duplicates;
4. `selection`: an optional global target, zero or more maximum quotas, and a
   ranking strategy;
5. `ordering`: final playlist ordering, separate from selection ranking; and
6. `schemaVersion`: the syntax/meaning version used to parse and validate the
   recipe.

Use focused sealed types rather than a generic stringly typed expression map:

```kotlin
@Serializable
data class PlaylistRecipe(
    val schemaVersion: Int,
    val source: CandidateSource,
    val predicate: TrackPredicate = TrackPredicate.All,
    val distinctness: DistinctnessPolicy =
        DistinctnessPolicy.By(CandidateIdentity.SpotifyUri),
    val selection: SelectionPolicy,
    val ordering: OrderingPolicy,
)

@Serializable
data class SelectionPolicy(
    val target: Int? = null,
    val quotas: List<Quota> = emptyList(),
    val rankBy: RankingStrategy,
)

@Serializable
data class Quota(
    val dimension: CandidateDimension,
    val maximum: Int,
)
```

`CandidateSource` should initially support `SavedTracks`, `TopTracks`,
`PlaylistItems`, `Union`, and `Difference`. `TrackPredicate` should use explicit
variants such as `All`, `And`, `Or`, `Not`, `ReleaseYearRange`,
`DurationRange`, `AlbumIdIn`, `ArtistIdIn`, `AddedAtRange`, and
`Explicitness`. `DistinctnessPolicy` has explicit `By(identity)` and `KeepAll`
variants. `CandidateDimension` should begin with `PrimaryArtistId` and
`AlbumId`. `RankingStrategy` and `OrderingPolicy` should support seeded random,
added time, and release date with explicit direction and null handling.

Define composite-source set semantics in terms of canonical candidate identity:
`Union` combines its children and leaves duplicate handling to `distinctness`;
`Difference(left, right)` removes left candidates whose identity occurs on the
right. Apply the recipe predicate after source composition. Add a future
source-local filtering node only if recipes need different predicates on
different branches; do not give `Union` or `Difference` hidden SQL-dependent
behavior.

Do not expose a dimension until its extraction semantics are unambiguous. A
multi-valued genre or all-artists dimension must define whether a candidate
consumes every matching quota bucket or one canonical bucket. Genre also
requires an enrichment source because it is not dependable track metadata;
the recipe capability registry must not advertise unavailable fields.

Use explicit bound names such as `minYearInclusive` and `maxYearExclusive`.
Every predicate defines null behavior, range inclusivity, case normalization,
and multi-value matching. These semantics are part of the recipe schema and
golden tests, not incidental SQL behavior.

### Optional Kotlin authoring DSL

A small type-safe Kotlin DSL may make built-in recipes pleasant to read:

```kotlin
playlistRecipe {
    from(SavedTracks)
    where {
        releaseYear atLeast 2020
        duration atMost 6.minutes
    }
    distinctBy(SpotifyUri)
    select {
        target(100)
        maximum(12, per = PrimaryArtistId)
        maximum(2, per = AlbumId)
        rankedBy(SeededRandom)
    }
    orderBy(SeededRandom)
}
```

The DSL is construction sugar only: it must return the immutable
`PlaylistRecipe` above and contain no query or execution behavior. Tests and
services remain free to construct recipes directly. Do not use mutable builder
state as the persisted, compared, serialized, or versioned representation.

### Recipe validation, identity, and execution

Canonicalize each validated recipe and derive an opaque `recipeRevision` from
its canonical representation. A managed definition references an immutable
recipe revision plus its display/lifecycle configuration. Changing recipe
semantics creates a new revision; it never reinterprets an existing generation.
`algorithmVersion` remains separate because the same recipe can have different
execution implementations only when they preserve output, or a deliberately
versioned behavior change when they do not.

Compile a recipe into a validated execution plan:

1. Resolve sources and verify that required fields and enrichments are present
   in the current cache capabilities.
2. Push supported eligibility predicates and joins into parameterized SQL.
   SQL returns a typed candidate projection with all fields needed by remaining
   predicates, dimensions, ranking, and output.
3. Evaluate any supported non-pushdown predicates in Kotlin. Pushdown is an
   optimization and must not alter recipe semantics.
4. Apply distinctness, deterministic ranking, quotas, target, and final
   ordering in Kotlin under the generation's cache revision and seed.

For multiple maximum quotas, rank candidates deterministically and scan them in
rank order. Admit a candidate only when doing so violates no quota, and stop at
the global target when present. With no target, scan all candidates and retain
everything allowed by the quotas. This supports combinations such as “100
tracks, at most 12 per artist and 2 per album.” Minimum guarantees, balancing,
or optimization objectives require separate future selection-policy variants;
do not overload maximum-quota semantics.

For personal-library scale, Kotlin sorting is straightforward. A single global
or per-dimension top-N strategy can later use bounded heaps. If scale eventually
requires database ranking, register a deterministic seeded-hash SQLite function
and compile the same execution plan to window functions. That is an optimizer
change, not a recipe-model change.

Continue using SQLDelight for schema, static source queries, and typed candidate
projections. Do not add Exposed, jOOQ, or another SQL DSL merely to author
recipes: those libraries model SQL construction, not Butler's recipe semantics,
and would duplicate the existing database layer. If dynamic pushdown becomes
valuable, implement a small allowlisted compiler from recipe predicates to
parameterized SQL; never accept SQL fragments, expressions, or column names
from a client.

### Future recipe API

The recipe AST is deliberately JSON-serializable so future browser clients can
create recipes without understanding Kotlin or SQL:

```text
GET  /api/v1/recipe-capabilities
GET  /api/v1/recipes?limit=50&cursor=...
POST /api/v1/recipes/validate
POST /api/v1/recipes
GET  /api/v1/recipes/{recipeId}
```

The initial release may expose only read-only built-in recipes and validation;
creation can remain disabled until persistence and GUI authoring exist. The
capability resource advertises available sources, fields and types, predicates,
dimensions, ranking/ordering strategies, recipe schema versions, and complexity
limits. Validation returns normalized recipe metadata or structured errors
without creating a resource.

Accepted recipes are immutable, owner-scoped resources. Enforce limits on AST
depth, predicate/list counts, source fan-out, target and quota values, estimated
candidate work, and required enrichments. Unknown variants and unavailable
capabilities fail validation. Never allow client recipes to escape owner data
isolation or invoke arbitrary SQL.

## Managed playlist catalog resources

```text
GET  /api/v1/playlists
POST /api/v1/playlists
GET  /api/v1/playlists/{definitionId}
```

The collection contains managed definitions, not arbitrary Spotify playlists.
Built-ins use the current stable `PlaylistDefinitionId` values serialized as
lower-snake-case strings; future user-created definitions receive opaque stable
IDs. Clients must treat every `definitionId` as a string, not as a closed Kotlin
enum.

Each definition includes its display name, immutable recipe ID/revision,
concrete definition revision and catalog revision, rolling inputs such as
`currentYear`, mapped Spotify playlist metadata or `current: null`, cache links,
and links to current items and generation creation. Recipe details are linked
rather than copied into every definition response.

`POST /playlists` is the future composition point for creating a managed
definition from an owned recipe revision. It may remain disabled in the first
release, but its resource model prevents recipe creation from being conflated
with preview generation or Spotify playlist mutation.

Produce built-in definitions from one catalog service, one injected `Clock`,
and the canonical recipe model. Create an immutable catalog snapshot with an
opaque `catalogRevision`; listing, generation creation, and generation records
use that snapshot. A year boundary may create a new snapshot and concrete
definition revision, but must not reinterpret an existing generation.

## Library resource and refresh

```text
GET  /api/v1/library
POST /api/v1/library/refresh
```

`GET /library` returns `state` (`empty`, `ready`, `refreshing`, or `stale`),
`cacheRevision`, completion timestamp, owner, last refresh operation link, and
counts for saved tracks, top tracks, top artists, playlists, accessible
playlist items, and inaccessible playlists. It never exposes raw track data.

`POST /library/refresh` requires `Idempotency-Key`, creates a
`library_refresh` operation, and only fetches and atomically replaces the
cache. It must not clean duplicate saved tracks, plan definitions, or mutate
playlists. Concurrent refresh creation returns the existing active operation or
`409 refresh_in_progress` with its link.

## Current managed playlist

```text
GET /api/v1/playlists/{definitionId}/current
GET /api/v1/playlists/{definitionId}/current/items?limit=50&cursor=...
```

The current resource uses only the managed mapping and cached Spotify data. It
does not execute the definition's recipe, call Spotify, or create a generation.
It returns the cache and definition revisions plus `current: null` when no
playlist is mapped. The item subresource returns cached items in explicit
position order and preserves unsupported/unavailable entries with nullable
fields.

Keep item lists out of the metadata response rather than using an
`include=tracks` switch. Separate pagination makes payload size predictable and
lets future clients request item pages without repeating playlist metadata.
Return `409 cache_not_ready` without a completed cache and `404` for an unknown
definition.

## Immutable preview generations

```text
GET    /api/v1/playlists/{definitionId}/generations?limit=50&cursor=...
POST   /api/v1/playlists/{definitionId}/generations
GET    /api/v1/playlists/{definitionId}/generations/{generationId}
GET    /api/v1/playlists/{definitionId}/generations/{generationId}/items?set=desired&limit=50&cursor=...
DELETE /api/v1/playlists/{definitionId}/generations/{generationId}
```

Creating a generation is the explicit preview/reroll action. It requires a
ready cache, captures a new cryptographically random seed, and performs no
Spotify calls or playlist writes. Return `201 Created`, `Location`, and an
immutable resource containing:

```json
{
  "generation": {
    "id": "opaque-generation-id",
    "definitionId": "decade_1990",
    "definitionRevision": "opaque-definition-revision",
    "recipeId": "opaque-recipe-id",
    "recipeRevision": "opaque-recipe-revision",
    "catalogRevision": "opaque-catalog-revision",
    "cacheRevision": "opaque-cache-revision",
    "algorithmVersion": "playlist-generation-v1",
    "createdAt": "2026-07-19T12:00:00Z",
    "expiresAt": "2026-07-19T12:30:00Z",
    "expectedPlaylist": {
      "spotifyPlaylistId": "...",
      "snapshotId": "..."
    },
    "counts": {
      "desired": 42,
      "alreadyPresent": 35,
      "toAdd": 7,
      "toRemove": 3
    }
  }
}
```

`expectedPlaylist` is `null` when the concrete definition has no mapped
playlist at generation time.

Store the seed, exact ordered desired item URIs, diff sets, concrete definition,
immutable recipe ID/revision, and all captured revisions in a bounded
`GenerationStore`. The browser receives no authoritative seed or editable
generation descriptor; sync trusts only the server record. Initial in-memory
generations may expire on process restart. Persist them later only if restart
survival is a real requirement.

The collection lists bounded recent generation metadata so a reloaded browser
can recover active previews. The item subresource supports `set=desired`,
`already_present`, `to_add`, or `to_remove`. It returns sanitized item
projections, never raw Spotify JSON. `DELETE` discards an unused generation and
returns `204`; an applied generation remains as operation history until
retention expiry and cannot be deleted independently.

### Deterministic recipe execution

SQLite's built-in `random()` is explicitly non-deterministic and has no seed
parameter. `row_number()` follows its window `ORDER BY`, so the current
`row_number() OVER (PARTITION BY primary_artist_id ORDER BY RANDOM())` queries
cannot reproduce a prior selection. See SQLite's documentation for
[deterministic functions](https://www.sqlite.org/deterministic.html) and
[window functions](https://www.sqlite.org/windowfunctions.html).

Do not attempt to seed SQLite. The recipe compiler may push source resolution,
joins, and eligibility predicates into SQL, but deterministic distinctness,
ranking, quota admission, target cutoff, and final ordering belong to the
versioned execution engine described by the recipe model. SQL must project a
stable candidate identity and every field required by residual predicates and
dimensions; it must never rely on planner, insertion, or `rowid` order.

Generate at least 256 random seed bits with `SecureRandom` and retain them only
in the generation record. For seeded-random ranking, compute an unsigned
bytewise digest over canonically encoded, length-delimited inputs such as:

```text
SHA-256("select-v1", seed, recipeRevision, candidateIdentity)
```

Break the vanishingly unlikely digest tie with the canonical candidate
identity. Scan candidates in this total rank order and admit each candidate
only if every maximum quota remains satisfied, stopping at the optional global
target. This one rule composes artist, album, and future dimensions without
embedding a special per-artist algorithm. A dimension's documented null and
multi-value semantics determine which quota buckets a candidate consumes.

Apply final ordering separately after selection. Seeded-random ordering uses a
different domain separator, for example:

```text
SHA-256("order-v1", seed, recipeRevision, candidateIdentity)
```

Non-random ranking and ordering still require explicit direction, null policy,
and canonical identity as a final tie-breaker. The algorithm must not depend on
Kotlin or Java seeded-PRNG implementation details. A custom deterministic
SQLite hash function may optimize large candidate sets later, but it must
implement the same rank bytes and cannot change observable output.

The same seed, normalized candidate snapshot, recipe revision, cache revision,
and algorithm version must produce the same exact ordered URI list. Increment
`algorithmVersion` for any deliberate change to execution semantics; a mere
pushdown or performance optimization must preserve output. The golden contracts
in the testing readiness gate enforce this boundary.

## Sync one managed playlist

```text
POST /api/v1/playlists/{definitionId}/syncs
```

Request body:

```json
{
  "generationId": "opaque-generation-id"
}
```

Require `Idempotency-Key`. Validate that the generation belongs to the
authenticated Spotify user and route definition, is unexpired and unused, and
still matches the current cache, catalog, definition, and algorithm revisions.
Do not accept `reroll=true`: clients create another generation explicitly.
Preview itself is the dry run, so the sync endpoint needs no `dryRun` flag.

Create a `playlist_sync` operation and lock the concrete Spotify playlist (or
definition instance while creating one). Immediately before mutation, fetch
the live Spotify playlist metadata and compare its snapshot ID with the
generation's expected snapshot. Because this check runs inside the accepted
operation, a mismatch marks the operation `failed` with error code
`playlist_changed`; the client then refreshes the library and creates a new
generation. Local generation/revision validation that fails before operation
creation returns HTTP `409` directly.

For an unmapped definition, repeat the managed-mapping and exact-name adoption
check while holding the definition lock immediately before creation. If another
playlist or mapping appeared after generation, fail the operation with a
conflict error rather than creating a duplicate.

Spotify's replace operation does not provide a fully atomic conditional write
for this workflow, so a preflight snapshot check narrows but cannot eliminate
the race between the check and mutation. State that limitation honestly in the
contract. Capture every snapshot ID returned by Spotify item mutations.

Apply the generation's stored ordered URIs exactly; never rerun random
selection during sync. After mutation, fetch the target playlist metadata and
all of its items from Spotify, then atomically update the managed mapping and
targeted cached projection and publish a new cache revision. This authoritative
read avoids inventing wrapper fields such as `added_at` that mutation responses
do not return. Mark the generation applied and return whether the playlist was
created or replaced, its Spotify ID, previous and resulting snapshot IDs,
resulting count, and new cache revision. Publishing the revision intentionally
makes every still-pending generation from the previous cache revision stale.

Serialize cache publication per owner: a library refresh and a playlist sync
must not publish competing revisions concurrently. An operation may wait on the
owner's cache-write lock or fail with a conflict and a link to the active
operation, but it must never overwrite a newer cache revision.

Playlist replacement may require several Spotify requests because writes are
limited to batches. Spotify cannot make the whole multi-request replacement
transactional. If a later batch fails, mark the operation `failed` with error
code `partial_failure` and include a sanitized partial result, mark the cached
playlist/library stale, and require refresh before another preview or sync. If
the Spotify mutation succeeds but the authoritative post-write read fails,
report the mutation result while marking the cache stale and requiring refresh.
When creating a playlist, record the new Spotify playlist ID before adding
items so an idempotent retry cannot create a duplicate playlist.

`Idempotency-Key` and the applied generation record make retries safe: a retry
after a completed operation returns the prior result, while a retry of an
in-progress operation returns its link.

## Legacy whole-run compatibility

```text
POST /api/v1/run
```

This transitional endpoint may compose cache refresh, legacy duplicate cleanup,
generation creation, and multiple sync operations. It returns an operation and
must use the same lower-level services and safety checks as the narrow
resources. No GUI workflow should require it, and it should be deprecated after
migration.

Duplicate cleanup must remain outside library refresh and playlist sync. If it
becomes a GUI feature, design it as its own preview/apply resource pair rather
than adding a boolean to unrelated endpoints.

## Service boundaries and tests

1. Extract an authenticated application facade from `ButlerHttpServer`. HTTP
   parses DTOs and maps errors; application services own authorization,
   revisions, cache policy, generation policy, and operation orchestration.
2. Add `SessionStore`, `GenerationStore`, `OperationStore`, and managed-playlist
   mapping boundaries. In-memory implementations are sufficient initially when
   restart behavior and bounded retention are explicit.
3. Keep Spotify HTTP/OAuth details in Spotify clients, SQLite behind
   `SpotifyStore`, recipes and their compiler/validator behind a recipe service,
   definitions in one catalog service, deterministic generation in a generation
   service, and mutation sequencing in the orchestration layer.
4. Make every Spotify-backed operation obtain a currently usable token through
   one token provider. Do not pass browser credentials or Spotify refresh tokens
   into domain services.
5. Replace catch-all success with JSON `404`, enforce method and content-type
   guards, cap request bodies and pagination limits, and map known Spotify
   failures without returning raw upstream bodies.
6. Add OpenAPI/route contract tests plus module-level tests proving operation
   isolation: refresh never mutates playlists, current view never generates,
   generation never calls Spotify, and one sync never touches another
   definition.
7. Add the readiness-gate golden fixture tests, recipe serialization and
   validation tests, and a small DSL contract proving that representative DSL
   and direct-construction forms canonicalize to the same recipe revision.
8. Test cookie attributes, CSRF and Origin checks, user allowlisting, resource
   ownership, state/PKCE replay rejection, session rotation, refresh locking,
   idempotency, generation expiry, stale revisions, live snapshot conflicts,
   partial batch failure, and absence of credentials/raw JSON in responses and
   logs.

## Rollout sequence

1. Establish the testing readiness gate: characterize all legacy definitions,
   add cutoff-heavy fixtures, preserve exact non-random legacy output, and
   preserve eligibility/quota invariants for legacy random definitions.
2. Migrate Spotify playlist and library endpoints to the current contract and
   extend the cache schema, atomic revision, item ordering, managed mapping, and
   fixture compatibility.
3. Introduce the canonical recipe AST, serialization, validation, capability
   registry, optional built-in DSL, recipe compiler, and deterministic engine
   alongside the legacy path. Express all built-ins as recipes, record exact
   fixed-seed ordered golden output, and satisfy the dual-execution fixture
   contract before retiring `PlaylistQuery` as the definition contract.
4. Refactor catalog, cache status, single-definition planning, and single-plan
   mutation boundaries while preserving explicit `mode=singlerun` behavior.
5. Add OpenAPI, DTO/error infrastructure, single-user session enforcement,
   server-side PKCE, refresh-token handling, CSRF protection, and the session
   endpoints.
6. Add playlist collection/detail, library status/refresh, current metadata and
   paged current items, operation resources, and immutable generation
   resources backed by the already-characterized deterministic engine.
7. Add idempotent per-playlist sync operations, live snapshot preflight,
   managed mapping updates, cache revision publication, and partial-failure
   recovery.
8. Migrate the GUI to the narrow resources, then deprecate the legacy run and
   all-in-one callback.
9. Before enabling remote LAN or internet browsers, deploy the corresponding
   HTTPS callback/origin profile, secure cookies, trusted-proxy and Host checks,
   quotas/rate limits, encrypted persistent secrets if needed, and operational
   monitoring.

## Appendix: Already Done Work

The Kotlin test-readiness layer now characterizes all 15 legacy definitions in
sanitized SQLite fixtures, exercises real global and per-dimension cutoffs,
records exact fixed-seed recipe goldens, verifies predicate/composition and
serialization behavior, proves deterministic results across insertion-order
and SQLite reopen variants, and carries a generated ordered URI list through
storage and a fake mutation client; these tests are now the compatibility
boundary for the broader API and recipe changes.
