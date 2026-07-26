# Spotify Butler API and client finalization

This document is the browser-facing contract for the Kotlin service and Vue 3.5 SPA. It separates source caching,
recipe generation, and Spotify destinations, and every persisted resource is scoped to the authenticated Spotify user.

## Contract principles

Each source collection has its own cache row, revision, timestamp, status, and count. Refreshing top_artists cannot
invalidate or retimestamp saved_tracks; a failed source retains its usable rows and reports a sanitized source-local
error. The library response therefore has no global cache revision, completed timestamp, or aggregate counts map.

Recipes are immutable typed PlaylistRecipe values at the Kotlin/API boundary. Owner definitions persist the canonical
recipe revision and ordered recipe items. Built-ins are code-defined and read-only. A preview evaluates the recipe against
current SQLite snapshots and returns exact ordered IDs, dependencies, seed, recipe revision, and algorithm version.
Preview requests never contact Spotify or mutate a destination, so a re-roll is another seeded preview GET.

A managed destination exists only after Spotify creates a playlist and returns its ID. The mapping is then stored with the
owner, definition, creation time, last sync time, and last-seen snapshot ID. A playlist discovered in the cached library
does not become managed automatically. An explicit one-time update may write any owned playlist and returns
tracked=false; it never creates or changes a managed mapping.

## Authentication and request safety

Authenticated requests use the HttpOnly butler_session cookie. State-changing requests also require the in-memory
session X-CSRF-Token, a trusted browser Origin, and application/json when a body is present. Spotify access and refresh
tokens never cross the browser boundary. /callback only establishes the session and redirects; refresh work is performed
through the explicit library refresh operation.

## Routes

| Method | Route | Purpose |
| --- | --- | --- |
| GET | /api/v1/session | Read the authenticated user, CSRF token, and expiry |
| POST | /api/v1/session/refresh | Rotate session values |
| DELETE | /api/v1/session | Sign out |
| GET | /api/v1/library | Read aggregate status and independent source snapshots |
| POST | /api/v1/library/refresh | Refresh all sources or the requested sourceKeys |
| GET | /api/v1/playlists | List built-in and owner definitions |
| POST | /api/v1/playlists | Create an owner definition |
| GET/PUT | /api/v1/playlists/{definitionId} | Read or edit one owner-scoped definition |
| GET | /api/v1/playlists/{definitionId}/preview | Generate a deterministic cache-backed preview |
| POST | /api/v1/playlists/{definitionId}/destinations | Create and track a Butler destination |
| GET | /api/v1/playlists/{definitionId}/current | Read the cached current managed destination |
| POST | /api/v1/playlists/{definitionId}/syncs | Replace a Butler-created destination |
| POST | /api/v1/playlists/{definitionId}/one-time-updates | Replace an explicitly named owned playlist without tracking it |
| GET | /api/v1/songs?ids=... | Batch song enrichment for up to 50 normalized IDs |

The target surface has no implicit destination resolution by name, destination history, global cache fields, raw source
routes, singular song route, or legacy run route.

## Resource shapes

Library contains ownerSpotifyUserId, aggregate status, sources, and definition summaries. Each source has sourceKey,
resourceKind, status, nullable sourceRevision, nullable lastSyncedAt, nullable itemCount, canRefresh, and nullable
sanitized error fields.

Definition contains definitionId, name, description, kind (built_in or owner), editable, enabled, typed recipe, source
dependencies, and a nullable managed destination summary. Destination fields are only definitionId, spotifyPlaylistId,
createdAt, nullable lastSyncedAt, nullable lastSeenSnapshotId, canSync, and the derived presentation value
managementStatus=butler_created.

Preview contains definitionId, status, ordered generatedTrackIds, generatedTrackCount, seed, recipeRevision,
algorithmVersion, source dependencies, generatedAt, and an optional unavailable reason. Omitting seed derives a stable
default from the definition, recipe revision, and source revisions.

CurrentEnvelope contains nullable current. A current value has the destination ID, authoritative ordered IDs, and the
managed timestamps. OneTimePlaylistUpdate has the authoritative ID order, snapshot ID, applied time, and literal
tracked=false.

All errors use {code, message, requestId, details}. Stable conflict codes include destination_missing,
destination_conflict, invalid_track_ids, and owner_mismatch; server and Spotify details are sanitized.

## Vue client responsibilities

ButlerApiClient is the only module that knows route paths. It sends credentials on every request, sends CSRF only for
mutations, validates successful DTOs at runtime, preserves track-ID order, batches song enrichment at 50 IDs, and never
retries a mutation automatically.

The session controller owns the CSRF token in memory. The library controller renders independent source statuses and
offers explicit full or selective refresh. The studio controller keeps the selected recipe, server preview, current
managed destination, and local ordered edits separate. Re-roll requests GET /preview?seed=...; the client never shuffles
candidates or treats a preview as a Spotify write.

Definition editability and destination syncability are separate capabilities. The UI shows only a Butler-created
destination or no destination. It offers destination creation before recurring sync and a separate one-time-update flow
that requires the explicit Spotify playlist ID. Source dependency revisions are displayed beside the generated selection
so a stale preview is understandable before submission.
