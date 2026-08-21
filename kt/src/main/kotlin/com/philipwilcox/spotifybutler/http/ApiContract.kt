package com.philipwilcox.spotifybutler.http

import com.philipwilcox.spotifybutler.service.PlaylistRecipe
import kotlinx.serialization.SerialName
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

@Serializable
enum class OperationKind {
    @SerialName("library_refresh")
    LIBRARY_REFRESH,

    @SerialName("publish_plan")
    PUBLISH_PLAN,

    @SerialName("publish_create")
    PUBLISH_CREATE,

    @SerialName("publish_adopt")
    PUBLISH_ADOPT,

    @SerialName("destination_sync")
    DESTINATION_SYNC,

    @SerialName("library_playlist_publish")
    LIBRARY_PLAYLIST_PUBLISH,

    @SerialName("bulk_republish_plan")
    BULK_REPUBLISH_PLAN,

    @SerialName("bulk_republish")
    BULK_REPUBLISH,

    ;

    companion object {
        val library_refresh get() = LIBRARY_REFRESH
        val publish_plan get() = PUBLISH_PLAN
        val publish_create get() = PUBLISH_CREATE
        val publish_adopt get() = PUBLISH_ADOPT
        val destination_sync get() = DESTINATION_SYNC
        val library_playlist_publish get() = LIBRARY_PLAYLIST_PUBLISH
        val bulk_republish_plan get() = BULK_REPUBLISH_PLAN
        val bulk_republish get() = BULK_REPUBLISH
    }
}

@Serializable
enum class OperationPhase {
    @SerialName("queued")
    QUEUED,

    @SerialName("running")
    RUNNING,

    @SerialName("succeeded")
    SUCCEEDED,

    @SerialName("failed")
    FAILED,

    ;

    companion object {
        val queued get() = QUEUED
        val running get() = RUNNING
        val succeeded get() = SUCCEEDED
        val failed get() = FAILED
    }
}

@Serializable data class OperationAcceptedWire(
    val operationId: String,
    val kind: OperationKind,
)

@Serializable data class OperationFailureWire(
    val code: String,
    val message: String,
)

@Serializable sealed class OperationResultWire

@Serializable data class LibraryRefreshResultWire(
    val library: LibraryWire,
) : OperationResultWire()

@Serializable data class PublishPlanResultWire(
    val plan: PublishPlanWire,
) : OperationResultWire()

@Serializable data class PublishDestinationResultWire(
    val destination: DestinationSummaryWire,
) : OperationResultWire()

@Serializable data class DestinationSyncResultWire(
    val current: CurrentEnvelopeWire,
) : OperationResultWire()

@Serializable data class LibraryPlaylistPublishResultWire(
    val playlist: LibraryPlaylistDetailWire,
) : OperationResultWire()

@Serializable data class BulkRepublishPlanResultWire(
    val plan: BulkRepublishPlanWire,
) : OperationResultWire()

@Serializable data class BulkRepublishResultWire(
    val library: LibraryWire,
    val items: List<BulkRepublishItemWire>,
) : OperationResultWire()

@Serializable data class OperationStatusWire(
    val operationId: String,
    val kind: OperationKind,
    val phase: OperationPhase,
    val action: String,
    val completedSteps: Int,
    val totalSteps: Int? = null,
    val result: OperationResultWire? = null,
    val error: OperationFailureWire? = null,
    val libraryRefreshProgress: LibraryRefreshProgressWire? = null,
    val bulkRepublishProgress: BulkRepublishProgressWire? = null,
)

@Serializable data class LibraryRefreshProgressWire(
    val completedSources: Int,
    val totalSources: Int,
    val activeSourceCompletedPages: Int? = null,
    val activeSourceTotalPages: Int? = null,
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
    val editable: Boolean,
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

@Serializable data class CreateDefinitionRequest(
    val name: String,
    val description: String = "",
    val recipe: PlaylistRecipe,
    val enabled: Boolean = true,
)

@Serializable data class UpdateRecipeSettingsRequest(
    val shuffleAfterGeneration: Boolean,
)

@Serializable data class PublishPlaylistCandidateWire(
    val spotifyPlaylistId: String,
    val name: String,
    val description: String?,
    val itemCount: Int?,
    val displayUrl: String?,
)

@Serializable data class PublishPlanWire(
    val definitionId: String,
    val playlistName: String,
    val action: String,
    val candidates: List<PublishPlaylistCandidateWire>,
    val message: String?,
    val publishFlowId: String,
)

@Serializable data class PublishDestinationRequest(
    val action: String,
    val spotifyPlaylistId: String? = null,
    val trackIds: List<String>,
    val publishFlowId: String? = null,
)

@Serializable data class BulkRepublishPlanWire(
    val items: List<BulkRepublishPlanItemWire>,
)

@Serializable data class BulkRepublishPlanItemWire(
    val definitionId: String,
    val name: String,
    val action: String,
    val candidates: List<PublishPlaylistCandidateWire> = emptyList(),
    val message: String? = null,
)

@Serializable data class BulkRepublishRequest(
    val items: List<BulkRepublishChoiceWire>,
)

@Serializable data class BulkRepublishChoiceWire(
    val definitionId: String,
    val action: String,
    val spotifyPlaylistId: String? = null,
)

@Serializable data class BulkRepublishProgressWire(
    val completedItems: Int,
    val totalItems: Int,
    val items: List<BulkRepublishItemWire>,
)

@Serializable data class BulkRepublishItemWire(
    val definitionId: String,
    val name: String,
    val phase: String,
    val trackCount: Int? = null,
    val completedSteps: Int? = null,
    val totalSteps: Int? = null,
    val message: String? = null,
)

@Serializable data class SyncPlaylistRequest(
    val trackIds: List<String>,
    val expectedDestinationSnapshotId: String? = null,
)

@Serializable data class PublishLibraryPlaylistRequest(
    val trackIds: List<String>,
)

@Serializable data class BulkSongsRequest(
    val trackIds: List<String>,
)

@Serializable data class AlbumWire(
    val id: String?,
    val name: String?,
    val href: String?,
    val uri: String?,
    val releaseDate: String?,
    val imageUrl: String?,
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
