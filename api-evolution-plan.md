# Kotlin API evolution plan

This plan implements the contract in api-and-client-finalization.md and the Vue contracts in
vue-frontend-component-spec.md. It is written at API, persisted-model, class, and function boundaries. It contains no
implementation code.

## 1. Target architecture

The service will persist three distinct categories of user-scoped state:

1. Cached source snapshots: read-only data fetched from Spotify, independently refreshed by source key.
2. Generation definitions: built-in or owner-scoped instructions for producing an ordered preview.
3. Destinations: Spotify playlists that Butler can create, explicitly adopt, read, and replace.

Every persisted category is scoped by ownerSpotifyUserId. No query may read a source, definition, mapping, or destination
sync record without an authenticated owner boundary.

The API will expose:

- sourceKey and resourceKind for every cached source;
- source-specific revision, status, item count, and lastSyncedAt;
- definitionId, definition kind, and editability;
- destination spotifyPlaylistId, management status, sync capability, lastSyncedAt, and lastSeenSnapshotId;
- preview source dependencies and the exact revisions used to produce generatedTrackIds.

There is no global cache revision in the new contract.

## 2. Current implementation constraints

The current schema stores a single global cache snapshot and one cache_metadata row. The main cache tables do not contain
ownerSpotifyUserId. SpotifyCacheService fetches one aggregate SpotifyCacheSnapshot, and ApiApplication reads that snapshot
as though it belonged to one account.

The current API also resolves mappings by a managed-playlist row or by matching playlist name. A name match is not proof
that Butler created or manages a playlist.

Existing local SQLite databases are not automatically migrated by the application. The migration below therefore requires
one of these deliberately chosen rollout options:

- development/test: delete and recreate the local database after the schema change;
- controlled deployment: run an explicit export/import migration tool before starting the new server;
- production: refuse startup with an actionable schema-version error until the operator performs the migration.

The application must not silently delete or reinterpret an existing database.

## 3. Persisted schema migration

### 3.1 Add an explicit cache-source synchronization table

Replace the singleton meaning of cache_metadata with a per-user, per-source synchronization record. The new table/model
must contain:

- ownerSpotifyUserId;
- sourceKey;
- resourceKind: track_list, artist_list, playlist_list, or playlist_contents;
- status: empty, ready, refreshing, stale, or error;
- sourceRevision, nullable while empty;
- lastSyncedAt, nullable while never fetched;
- itemCount, nullable while unavailable;
- lastErrorCode, nullable and sanitized;
- lastErrorAt, nullable.

The primary identity is ownerSpotifyUserId plus sourceKey. A source revision is unique within that source owner and is
derived from the completed fetch identity, including an exact UTC timestamp and a collision-safe suffix when necessary.
Human-readable lastSyncedAt and opaque sourceRevision are both retained.

The old global cache_metadata row must not be used as the source of truth after this migration. A temporary compatibility
projection may calculate aggregate library status, but no API response may expose a single global revision as if it
represented every source.

### 3.2 Scope all cached source tables by Spotify user

Add ownerSpotifyUserId to every table containing cache data:

- saved_tracks;
- top_tracks;
- top_artists;
- playlists;
- playlist_tracks;
- playlist_details;
- playlist_items;
- songs;
- song_artists.

Update primary keys, unique constraints, and indexes so the owner is part of every cache identity. In particular:

- song identity becomes ownerSpotifyUserId plus Spotify track ID;
- playlist identity becomes ownerSpotifyUserId plus Spotify playlist ID;
- playlist item identity becomes ownerSpotifyUserId plus playlist ID plus position;
- playlist track projections remain keyed by owner plus source playlist identity and position;
- artist rows are keyed by owner plus Spotify artist ID;
- source-specific queries always include ownerSpotifyUserId in their predicates.

The raw JSON retained in these tables remains user-scoped. A response from one user must never be able to enrich or
enumerate another user's cached song, artist, or playlist data.

### 3.3 Replace global sync status

Retire the global sync_status table or convert it into a per-user aggregate compatibility projection. The authoritative
refresh state lives in cache-source synchronization rows.

An aggregate Library status is derived as follows:

- empty: no source has a usable snapshot;
- ready: all required sources for the requested view are ready;
- partial: at least one required source is ready and another is missing, stale, or errored;
- refreshing: one or more requested sources are currently refreshing;
- stale: no requested refresh is active but required source data is stale or errored.

### 3.4 Expand the current destination mapping table

For the current product scope, expand managed_playlists in place rather than introducing separate destination, mapping,
and sync-history tables. The table represents the one current destination mapping for a user-specific definition and keeps
only the latest destination audit metadata.

The expanded row contains:

- ownerSpotifyUserId;
- definitionId;
- spotifyPlaylistId;
- managementStatus: butler_created, butler_adopted, or unmanaged;
- createdAt, nullable for adopted destinations;
- adoptedAt, nullable;
- lastSyncedAt, nullable;
- lastSeenSnapshotId, nullable;
- lastSyncSourceRevisionSet, nullable and stored as canonical JSON for latest-sync audit only;
- active, or an equivalent unmapped state.

The database identity and constraints are:

- unique destination identity is ownerSpotifyUserId plus spotifyPlaylistId;
- at most one active destination exists for ownerSpotifyUserId plus definitionId;
- every destination read, insert, update, and sync includes the authenticated owner;
- a Spotify playlist ID or definition ID from one user can never resolve to another user’s row;
- none is an API state derived from no active row; it does not need to be stored as a database value.

An existing name-based mapping is migrated as unmanaged unless there is an auditable record proving Butler created it.
It cannot be synchronized until explicit adoption succeeds. A row becomes butler_created only after Spotify playlist
creation returns a playlist ID and the server persists that ID for the authenticated owner. A row becomes butler_adopted
only after the explicit adoption operation succeeds.

This table intentionally does not retain a complete sync-history relation yet. If history or multiple destinations is
needed later, add a destination_syncs table without changing the API’s flattened DestinationSummary.

### 3.5 Preserve owner-definition storage

Owner playlist definitions and their ordered items already carry owner information. Verify and enforce:

- definition identity is ownerSpotifyUserId plus definitionId;
- item identity is definitionId plus position;
- PUT replacement is scoped to the authenticated owner;
- definition track IDs preserve order and duplicates as currently specified.

Built-in definitions remain code-defined and are never written to the owner-definition tables.

## 4. Kotlin model and service boundaries

### 4.1 Source identity and synchronization models

Add immutable domain models:

- CacheSourceKey: validates the supported source key grammar, including encoded playlist_items identifiers;
- CacheResourceKind: track_list, artist_list, playlist_list, playlist_contents;
- CacheSourceSnapshot: owner, key, kind, status, revision, lastSyncedAt, itemCount, and safe error state;
- CacheRefreshRequest: requested source keys, with an explicit all-sources meaning when omitted;
- CacheRefreshResult: updated source snapshots and aggregate library status.

Function-level contracts:

- CacheSourceKey.parse(raw): reject unknown keys, malformed playlist IDs, and cross-owner identifiers;
- CacheSourceRegistry.dependenciesFor(definition): return stable source keys required by a definition;
- CacheSourceRegistry.allSources(owner): list only sources belonging to that owner;
- CacheSourcePolicy.aggregateStatus(sources): derive the Library aggregate state without planner order;
- CacheSourcePolicy.revision(owner, sourceKey, completedAt): create a collision-safe source revision.

### 4.2 Split Spotify fetch boundaries

Replace the all-or-nothing SpotifyCacheFetcher.fetchCache boundary with source-specific operations:

- fetchSavedTracks(accessToken);
- fetchTopTracks(accessToken);
- fetchTopArtists(accessToken);
- fetchPlaylists(accessToken);
- fetchPlaylistItems(accessToken, spotifyPlaylistId).

SpotifyApiClient remains the owner of Spotify HTTP paths, pagination, parsing, and access tokens. Each operation returns
typed source data and does not write SQLite.

SpotifyCacheService gains:

- refreshSources(accessToken, ownerSpotifyUserId, sourceKeys);
- refreshSource(accessToken, ownerSpotifyUserId, sourceKey);
- readSourceStatus(ownerSpotifyUserId);
- sourceDependenciesAreReady(ownerSpotifyUserId, sourceKeys).

Each refresh replaces only the selected owner's source rows in one SQLite transaction, then commits that source's
lastSyncedAt, revision, status, and count. Other source rows remain unchanged.

A failed source refresh marks only that source stale/error and preserves its previous usable rows. It must not erase
unrelated source data or claim that a full library is ready.

### 4.3 Owner-scoped SpotifyStore methods

Update every store method that reads or writes cached data to require ownerSpotifyUserId. Important boundaries include:

- songs(ownerSpotifyUserId);
- songEnrichment(ownerSpotifyUserId, ids);
- candidates(ownerSpotifyUserId, source);
- recipeExecutionContext(ownerSpotifyUserId);
- playlistDetails(ownerSpotifyUserId, playlistId);
- playlistItems(ownerSpotifyUserId, playlistId);
- userPlaylistDefinitions(ownerSpotifyUserId);
- managedPlaylist(ownerSpotifyUserId, definitionId);
- saveManagedPlaylist(destination);
- destinationSyncState(ownerSpotifyUserId, definitionId);
- publishPlaylistTrackIds(destination, authoritativeState).

No default-owner overload may remain once the API uses multiple accounts.

### 4.4 Cache-backed preview service

Introduce PlaylistPreviewService with immutable Preview and SourceDependency models.

Function-level contracts:

- preview(ownerSpotifyUserId, definitionId, optionalSeed): resolve the definition and return generated ordered IDs;
- previewSummary(ownerSpotifyUserId, definitionId): return dependency/status/count metadata without IDs;
- defaultSeed(definitionId, sourceRevisions, recipeRevision, algorithmVersion): derive a stable seed;
- resolveBuiltIn(ownerSpotifyUserId, definition, sourceRevisions, seed): execute the canonical recipe against owner-scoped
  cached candidates;
- resolveOwnerDefinition(ownerSpotifyUserId, trackIds, sourceRevisions): preserve stored order and duplicates.

Built-in preview generation must use the typed recipe engine and deterministic candidate projections. It must not call SQL
queries containing ORDER BY RANDOM(). The result records recipeRevision, algorithmVersion, generatedTrackIds, and exact
source dependencies.

If a required source is unavailable, return status unavailable or partial with no fabricated IDs. If the sources are ready
but the recipe selects zero rows, return empty.

### 4.5 Destination creation, adoption, and sync

Extend SpotifyApiClient with typed destination operations:

- createPlaylist(accessToken, ownerSpotifyUserId, name, description, public, collaborative): return created playlist
  metadata, including Spotify ID and any available snapshot/version information;
- getPlaylist(accessToken, playlistId): return playlist metadata when explicitly needed for adoption validation;
- replaceTrackIds(accessToken, playlistId, trackIds): return the Spotify mutation version when available;
- getPlaylistCurrent(accessToken, playlistId): return ordered playable IDs and last-seen snapshot/version.

Extend PlaylistSyncGateway so replacement and authoritative-read results retain snapshot/version metadata.

Add destination service methods:

- createDestination(owner, definitionId, requestedDetails): create Spotify playlist, persist butler_created provenance,
  and return DestinationSummary;
- adoptDestination(owner, definitionId, spotifyPlaylistId): verify ownership from the scoped cache or explicit validation
  read, persist butler_adopted provenance, and return DestinationSummary;
- currentDestination(owner, definitionId): read the cached destination only;
- syncDestination(owner, definitionId, orderedTrackIds): replace Spotify contents, read authoritative state, publish only
  the destination cache, and update lastSyncedAt and lastSeenSnapshotId.

The sync path must not update source synchronization timestamps. It must not rewrite the generation definition.

### 4.6 ApiApplication routing and wire mapping

Add routes for:

- POST /api/v1/playlists/{definitionId}/destinations;
- POST /api/v1/playlists/{definitionId}/destinations/adoptions.

Update routes for:

- GET /api/v1/library to return per-source snapshots;
- POST /api/v1/library/refresh to accept optional sourceKeys;
- GET /api/v1/playlists and GET /api/v1/playlists/{definitionId} to return definition, source, and destination fields;
- GET /api/v1/playlists/{definitionId}/preview to accept an optional seed;
- GET /api/v1/playlists/{definitionId}/current to return destination timestamps and snapshot;
- POST /api/v1/playlists/{definitionId}/syncs to publish authoritative destination metadata.

Add serializable wire models:

- SourceSnapshotWire;
- SourceDependencyWire;
- DestinationSummaryWire;
- PlaylistReferenceWire with kind, editable, sources, and destination;
- PreviewWire;
- CurrentWire with lastSyncedAt and lastSeenSnapshotId;
- LibraryWire with sources and aggregate status;
- CreateDestinationRequest and AdoptDestinationRequest.

Keep mapping in dedicated functions so every list/detail/create/update response uses the same definitions and fields.

## 5. API error and security rules

All source and destination lookups are owner-scoped before returning not-found, mapping, or capability information.

Required stable errors include:

- source_not_found;
- source_not_ready;
- definition_not_found;
- destination_not_found;
- destination_not_owned;
- destination_not_editable;
- destination_already_managed;
- mapping_missing;
- invalid_source_key;
- invalid_track_ids;
- spotify_failure;
- csrf_failed and origin_not_trusted.

An unmanaged name match may be returned as a non-syncable destination candidate, but it must not reveal another user's
playlist or permit replacement.

## 6. Contract and integration tests

### OpenAPI tests

Update the contract test to verify:

- every new route and method;
- refresh body is optional for full refresh and supports sourceKeys for selective refresh;
- PlaylistReference includes kind, editable, sources, and destination;
- SourceSnapshot and DestinationSummary include user-scoped IDs, status, revisions, and timestamps;
- Preview includes generatedTrackIds, seed, recipeRevision, algorithmVersion, and dependencies;
- Current includes lastSyncedAt and lastSeenSnapshotId;
- destination create/adopt mutations require CSRF;
- GET preview/current do not require CSRF;
- all mutations retain reusable sanitized errors.

### Database and store tests

Use sanitized fixture databases to prove:

- two Spotify users can hold identical source keys and playlist IDs without data leakage;
- every source query filters ownerSpotifyUserId;
- refreshing one source leaves all other source rows and timestamps unchanged;
- refreshing one playlist_items source leaves other playlist item sources unchanged;
- failed refresh preserves prior rows and marks only the selected source stale/error;
- source revisions and lastSyncedAt values are independent;
- destination mappings are unique per user and playlist;
- name matches do not create butler_created or butler_adopted status;
- explicit adoption creates butler_adopted status;
- sync updates only destination rows and records authoritative order plus snapshot;
- source timestamps do not change after destination sync.

### Preview and destination contract tests

Add exact ordered-ID tests for:

- built-in preview from fixed source revisions;
- repeated preview with the same seed and source revisions;
- preview changes after the relevant source refresh;
- preview dependencies identifying every source used;
- owner-definition preview preserving stored order and duplicates;
- destination creation recording butler_created;
- adoption recording butler_adopted only after explicit request;
- unmanaged or unmapped destinations disabling sync;
- successful sync replacing local state with the authoritative response;
- concurrent source refresh and preview never exposing a partial transaction.

Use deterministic fake Spotify clients and no network calls. Reports may identify owners by sanitized symbolic IDs and
counts, but must not log access tokens, raw playlist contents, or private payloads.

## 7. Documentation and Vue follow-up

Update api-and-client-finalization.md and vue-frontend-component-spec.md together whenever a route, field, source key,
management status, or capability changes.

The Vue client must:

- display definitions, not raw source collections, in the main sidebar;
- show source dependencies and independently refreshable source statuses;
- distinguish definition editability from destination syncability;
- display butler_created, butler_adopted, unmanaged, and none distinctly;
- call preview GET with a seed for Re-roll;
- call selective library refresh with sourceKeys;
- submit only through the mapped destination sync route;
- preserve lastSyncedAt and lastSeenSnapshotId in the destination state.

## 8. Delivery order and verification

1. Add schema definitions and a deliberate database reset/export-import decision.
2. Add owner-scoped source metadata and cache table columns.
3. Split source fetchers and implement selective refresh transactions.
4. Add source-aware store projections and deterministic preview service.
5. Add destination provenance, create/adopt operations, and authoritative sync metadata.
6. Update ApiContract, OpenAPI, ApiApplication routes, and sanitized errors.
7. Update the two client documents and add end-to-end contract fixtures.
8. Run the Kotlin formatter first, review the diff, run focused tests, then run ./gradlew lint and ./gradlew build.

The plan document itself remains unstaged. Source code, tests, OpenAPI, schema, and sanitized fixtures created for the
implementation are staged only after verification.

## 9. Acceptance criteria

The migration is complete when:

- multiple Spotify users can use the same service without shared cached rows or mappings;
- each source has an independent revision, status, item count, and lastSyncedAt;
- selective refresh never refreshes or timestamps unrelated sources;
- definitions clearly identify their source dependencies and editability;
- destinations clearly identify their Spotify playlist ID, management origin, syncability, lastSyncedAt, and snapshot;
- butler_created is based on recorded creation, not name or description;
- adoption is explicit and user-scoped;
- preview output is reproducible for the same source revisions and seed;
- submit updates only the mapped destination and returns authoritative state;
- the API and Vue documents describe the same route names, fields, IDs, statuses, and operation boundaries.
