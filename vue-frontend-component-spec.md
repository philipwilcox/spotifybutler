# Vue frontend component and module specification

Status: design specification only. This document defines a Vue 3.5 + TypeScript playlist studio for the finalized
owner-scoped Spotify Butler API.

## Product boundary

The left column lists built-in and owner generation definitions. The selected definition panel shows its typed recipe,
independent source dependencies, server preview, and either a Butler-created destination or no destination. Local
reordering and removal are staged until the user submits a recurring sync. A one-time update is a separate action that
requires an explicit Spotify playlist ID and never creates tracking state.

The browser never stores Spotify credentials, writes source data, shuffles candidates, resolves playlists by name, or
calls Spotify directly. OAuth remains browser navigation to /start and /callback.

## API client

ButlerApiClient is the only module that knows route paths. It uses credentials: include, keeps the CSRF token in memory,
validates successful DTOs at runtime, preserves ordered IDs, batches song enrichment into at most 50 IDs, and never
retries mutations automatically.

Its methods are:

- getSession, refreshSession, and deleteSession;
- getLibrary and refreshLibrary(sourceKeys?);
- listDefinitions, getDefinition, createDefinition, and updateDefinition;
- previewDefinition(definitionId, seed?);
- createDestination(definitionId, options);
- getCurrentDestination(definitionId);
- syncDestination(definitionId, trackIds, expectedDestinationSnapshotId?);
- oneTimeUpdate(definitionId, spotifyPlaylistId, trackIds, expectedDestinationSnapshotId?);
- getSongs(trackIds).

There is no singular-song method, legacy run method, source-content method, destination adoption method, or client-side
candidate generation method.

## Wire and domain models

Runtime DTOs mirror the OpenAPI document: Session, Library, SourceSnapshot, Definition, PlaylistRecipe, Preview,
Destination, CurrentEnvelope, OneTimePlaylistUpdate, Songs, and sanitized Error. Unknown status or kind values, missing
required arrays, unexpected nulls, and malformed recipe objects are contract errors.

The domain mapper produces:

- DefinitionModel: definitionId, name, description, kind, editable, enabled, typed recipe, source dependencies, nullable
  destination, and separate edit and sync capabilities.
- SourceModel: sourceKey, resourceKind, status, sourceRevision, lastSyncedAt, itemCount, and sanitized error state.
- PreviewModel: ordered generated IDs, status, seed, recipe revision, algorithm version, dependencies, and generated time.
- SelectionModel: ordered local IDs, source (current or preview), dirty state, seed metadata, and enrichment states.
- DestinationModel: only a Butler-created destination summary; absence means no destination.

The server preview is the source of truth for generated selections. A re-roll generates a new seed and calls
GET /api/v1/playlists/{definitionId}/preview?seed=...; it does not use a local random generator.

## Controllers

SessionController owns authentication phases and the in-memory CSRF token. A 401 clears dependent workspace state; a
mutation is never transparently replayed.

LibraryController owns the last LibraryModel, request phase, source status display, and explicit full/selective refresh.
Refreshing a source updates only that source in the UI. The controller does not infer readiness from a global count.

StudioController owns the definition list, selected DefinitionModel, current managed state, PreviewModel, local selection,
song enrichment, and submit flows:

1. load session, library, and definitions;
2. select a definition and request its cached current state and preview;
3. enrich visible IDs through batch songs while retaining caller order;
4. allow local reorder/remove;
5. create a destination explicitly when no mapping exists;
6. submit recurring sync only when a Butler-created destination exists;
7. offer one-time update with an explicit target playlist ID and display tracked=false.

Controllers receive dependencies rather than importing singleton clients, clocks, or transports. This permits isolated
integration tests with a recording API transport.

## Components

Primitive components render loading, errors, source-status badges, recipe summaries, destination summaries, dialogs,
buttons, and track rows. Studio components receive mapped view models and callbacks; they do not call fetch, construct
URLs, parse envelopes, or decide mutation safety.

The definition header shows editability separately from destination syncability. Source dependency rows show individual
revisions and timestamps. The destination area has Create destination, Submit playlist, and One-time update actions with
clear confirmation and conflict errors.

## Integration-test cases

The recording transport and fake clock assert:

- session startup and CSRF rotation;
- independent source status rendering and selective refresh;
- exact server preview IDs, seed, revision, dependencies, and deterministic re-roll calls;
- built-in read-only and owner-definition edit flows;
- destination creation before the first recurring sync;
- missing-destination sync rejection;
- snapshot-conflict handling without mutation retry;
- one-time updates returning authoritative IDs with tracked=false and no destination mapping;
- owner isolation and absence of removed routes or legacy response fields.
