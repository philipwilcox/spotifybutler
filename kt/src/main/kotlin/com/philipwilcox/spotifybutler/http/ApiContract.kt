package com.philipwilcox.spotifybutler.http

import com.philipwilcox.spotifybutler.service.PlaylistRecipe
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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
    val headers: Map<String, String> =
        mapOf(
            "Content-Type" to "application/json; charset=utf-8",
        ),
)

@Serializable data class ErrorEnvelope(
    val code: String,
    val message: String,
    val requestId: String,
    val details: Map<String, String> = emptyMap(),
)

@Serializable data class SessionWire(
    val userId: String,
    val csrfToken: String,
    val expiresAt: String,
)

@Serializable data class SourceDependencyWire(
    val sourceKey: String,
    val resourceKind: String,
    val sourceRevision: String?,
    val lastSyncedAt: String?,
    val itemCount: Int?,
    val usable: Boolean,
)

@Serializable data class SourceSnapshotWire(
    val sourceKey: String,
    val resourceKind: String,
    val status: String,
    val sourceRevision: String?,
    val lastSyncedAt: String?,
    val itemCount: Int?,
    val canRefresh: Boolean,
    val lastErrorCode: String?,
    val lastErrorAt: String?,
)

@Serializable data class DestinationSummaryWire(
    val definitionId: String,
    val spotifyPlaylistId: String,
    val createdAt: String,
    val lastSyncedAt: String?,
    val lastSeenSnapshotId: String?,
    val canSync: Boolean,
    val managementStatus: String = "butler_created",
)

@Serializable data class DefinitionWire(
    val definitionId: String,
    val name: String,
    val description: String,
    val kind: String,
    val editable: Boolean,
    val enabled: Boolean,
    val recipe: PlaylistRecipe,
    val sourceDependencies: List<SourceDependencyWire>,
    val destination: DestinationSummaryWire?,
)

@Serializable data class DefinitionListWire(
    val items: List<DefinitionWire>,
)

@Serializable data class LibraryWire(
    val ownerSpotifyUserId: String,
    val status: String,
    val sources: List<SourceSnapshotWire>,
    val definitions: List<DefinitionWire>,
    val playlists: List<LibraryPlaylistWire>,
)

@Serializable data class LibraryPlaylistWire(
    val spotifyPlaylistId: String,
    val name: String,
    val description: String?,
    val href: String,
    val uri: String,
    val displayUrl: String?,
    val declaredItemCount: Int?,
    val cachedPlayableTrackCount: Int,
    val contentSourceKey: String,
    val contentStatus: String,
    val sourceRevision: String?,
    val lastSyncedAt: String?,
)

@Serializable data class LibraryPlaylistDetailWire(
    val summary: LibraryPlaylistWire,
    val trackIds: List<String>,
)

@Serializable data class PreviewWire(
    val definitionId: String,
    val status: String,
    val generatedTrackIds: List<String>,
    val generatedTrackCount: Int,
    val seed: String,
    val recipeRevision: String,
    val algorithmVersion: String,
    val sourceDependencies: List<SourceDependencyWire>,
    val generatedAt: String,
    val unavailableReason: String?,
)

@Serializable data class CurrentWire(
    val spotifyPlaylistId: String,
    val trackIds: List<String>,
    val lastSyncedAt: String?,
    val lastSeenSnapshotId: String?,
)

@Serializable data class CurrentEnvelopeWire(
    val current: CurrentWire?,
)

@Serializable data class OneTimePlaylistUpdateWire(
    val spotifyPlaylistId: String,
    val trackIds: List<String>,
    val lastSeenSnapshotId: String?,
    val appliedAt: String,
    val tracked: Boolean,
)

@Serializable data class CreateDefinitionRequest(
    val name: String,
    val description: String = "",
    val recipe: PlaylistRecipe,
    val enabled: Boolean = true,
)

@Serializable data class DestinationCreateRequestWire(
    val name: String? = null,
    val description: String? = null,
    val public: Boolean? = null,
    val collaborative: Boolean? = null,
)

@Serializable data class SyncPlaylistRequest(
    val trackIds: List<String>,
    val expectedDestinationSnapshotId: String? = null,
)

@Serializable data class OneTimeUpdateRequest(
    val spotifyPlaylistId: String,
    val trackIds: List<String>,
    val expectedDestinationSnapshotId: String? = null,
)

@Serializable data class AlbumWire(
    val id: String?,
    val name: String?,
    val href: String?,
    val uri: String?,
    val releaseDate: String?,
)

@Serializable data class ArtistWire(
    val id: String?,
    val name: String?,
    val href: String?,
    val uri: String?,
)

@Serializable data class SongWire(
    val id: String,
    val name: String,
    val href: String,
    val uri: String,
    val album: AlbumWire,
    val artists: List<ArtistWire>,
    val durationMs: Long?,
    val explicit: Boolean?,
    val available: Boolean,
)

@Serializable data class SongsWire(
    val items: List<SongWire>,
    val missingIds: List<String>,
)

val apiJson =
    Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
        classDiscriminator = "type"
    }
