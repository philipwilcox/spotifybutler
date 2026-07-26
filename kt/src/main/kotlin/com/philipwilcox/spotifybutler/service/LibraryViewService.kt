package com.philipwilcox.spotifybutler.service

import com.philipwilcox.spotifybutler.db.SpotifyStore
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Instant

data class LibraryPlaylistSummary(
    val spotifyPlaylistId: String,
    val name: String,
    val description: String?,
    val href: String,
    val uri: String,
    val displayUrl: String?,
    val declaredItemCount: Int?,
    val cachedPlayableTrackCount: Int,
    val contentSourceKey: String,
    val contentStatus: CacheSourceStatus,
    val sourceRevision: String?,
    val lastSyncedAt: Instant?,
)

data class LibraryPlaylistDetail(
    val summary: LibraryPlaylistSummary,
    val trackIds: List<String>,
)

data class LibraryView(
    val ownerSpotifyUserId: String,
    val status: CacheAggregateStatus,
    val sources: List<CacheSourceSnapshot>,
    val definitions: List<PlaylistDefinitionView>,
    val playlists: List<LibraryPlaylistSummary>,
)

class LibraryViewService(
    private val store: SpotifyStore,
    private val previewService: PlaylistPreviewService,
) {
    private val logger = KotlinLogging.logger {}

    fun library(ownerSpotifyUserId: String): LibraryView {
        val view =
            LibraryView(
                ownerSpotifyUserId,
                store.aggregateStatus(ownerSpotifyUserId),
                store.sourceSnapshots(ownerSpotifyUserId),
                previewService.definitions(ownerSpotifyUserId),
                store.libraryPlaylists(ownerSpotifyUserId).map { it.toSummary() },
            )
        logger.info {
            "Library view assembled: owner=$ownerSpotifyUserId status=${view.status} " +
                "sourceCount=${view.sources.size} definitionCount=${view.definitions.size} " +
                "playlistCount=${view.playlists.size} sourceKeys=${view.sources.joinToString(",") { it.sourceKey }}"
        }
        return view
    }

    fun playlist(
        ownerSpotifyUserId: String,
        spotifyPlaylistId: String,
    ): LibraryPlaylistDetail {
        val summary =
            library(ownerSpotifyUserId).playlists.firstOrNull { it.spotifyPlaylistId == spotifyPlaylistId }
                ?: throw LibraryPlaylistNotFoundException()
        return LibraryPlaylistDetail(summary, store.libraryPlaylistTrackIds(ownerSpotifyUserId, spotifyPlaylistId))
    }

    private fun com.philipwilcox.spotifybutler.db.StoredLibraryPlaylist.toSummary() =
        LibraryPlaylistSummary(
            playlistId,
            name,
            description,
            href,
            uri,
            displayUrl,
            itemCount,
            playableTrackCount,
            contentSourceKey,
            contentStatus,
            sourceRevision,
            lastSyncedAt,
        )
}

class LibraryPlaylistNotFoundException : RuntimeException("Library playlist not found")
