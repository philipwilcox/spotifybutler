package com.philipwilcox.spotifybutler.http

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

data class ApiRequest(
    val method: String,
    val path: String,
    val query: Map<String, String> = emptyMap(),
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
)

data class ApiResponse(
    val status: Int,
    val body: String,
    val headers: Map<String, String> = mapOf("Content-Type" to "application/json; charset=utf-8"),
)

@Serializable
data class ErrorEnvelope(
    val code: String,
    val message: String,
    val requestId: String,
    val details: Map<String, String> = emptyMap(),
)

@Serializable
data class SessionWire(
    val userId: String,
    val csrfToken: String,
    val expiresAt: String,
)

@Serializable
data class PlaylistReferenceWire(
    val id: String,
    val name: String,
    val state: String,
    val definitionRevision: String,
    val cacheRevision: String?,
    val spotifyPlaylistId: String?,
    val trackIds: List<String> = emptyList(),
)

@Serializable
data class PlaylistListWire(
    val items: List<PlaylistReferenceWire>,
    val cacheRevision: String?,
)

@Serializable
data class PlaylistCurrentWire(
    val spotifyPlaylistId: String,
    val snapshotId: String?,
    val cacheRevision: String,
    val trackIds: List<String>,
)

@Serializable
data class PlaylistCurrentEnvelopeWire(
    val current: PlaylistCurrentWire?,
)

@Serializable
data class PlaylistItemWire(
    val playlistId: String,
    val position: Int,
    val type: String?,
    val status: String,
    val trackId: String?,
    val uri: String?,
    val addedAt: String?,
    val addedById: String?,
    val local: Boolean,
    val playable: Boolean,
)

@Serializable
data class PlaylistItemsWire(
    val items: List<PlaylistItemWire>,
    val nextCursor: String?,
    val cacheRevision: String,
)

@Serializable
data class LibraryWire(
    val status: String,
    val cacheRevision: String?,
    val ownerId: String?,
    val refreshOperationId: String?,
    val completedAt: String?,
    val counts: Map<String, Int>,
)

@Serializable
data class AlbumWire(
    val id: String?,
    val name: String?,
    val href: String?,
    val uri: String?,
    val releaseDate: String?,
)

@Serializable
data class ArtistWire(
    val id: String?,
    val name: String?,
    val href: String?,
    val uri: String?,
)

@Serializable
data class SongWire(
    val id: String,
    val name: String,
    val href: String,
    val uri: String,
    val album: AlbumWire,
    val artists: List<ArtistWire>,
    val durationMs: Long?,
    val explicit: Boolean?,
    val available: Boolean,
    val cacheRevision: String?,
)

@Serializable
data class SongsWire(
    val items: List<SongWire>,
    val missingIds: List<String>,
    val cacheRevision: String?,
)

@Serializable
data class OperationWire(
    val id: String,
    val type: String,
    val status: String,
    val createdAt: String,
    val finishedAt: String?,
    val result: JsonElement? = null,
    val error: ErrorEnvelope? = null,
)

@Serializable
data class OperationsWire(
    val items: List<OperationWire>,
    val nextCursor: String? = null,
)

@Serializable
data class CreatePlaylistRequest(
    val name: String,
    val trackIds: List<String> = emptyList(),
)

@Serializable
data class SyncPlaylistRequest(
    val trackIds: List<String>,
    val baseSnapshotId: String? = null,
    val baseCacheRevision: String? = null,
)

@Serializable
data class PreviewWire(
    val cacheRevision: String?,
    val baseSnapshotId: String?,
    val currentTrackIds: List<String>,
    val submittedTrackIds: List<String>,
    val toAdd: List<String>,
    val toRemove: List<String>,
)

val apiJson =
    Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
        classDiscriminator = "type"
    }
