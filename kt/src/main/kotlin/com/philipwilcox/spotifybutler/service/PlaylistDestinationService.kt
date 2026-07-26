package com.philipwilcox.spotifybutler.service

import com.philipwilcox.spotifybutler.db.ManagedPlaylist
import com.philipwilcox.spotifybutler.db.SpotifyStore
import java.time.Clock
import java.time.Instant

data class DestinationCreateRequest(
    val name: String? = null,
    val description: String? = null,
    val public: Boolean? = null,
    val collaborative: Boolean? = null,
)

data class AuthoritativePlaylistState(
    val spotifyPlaylistId: String,
    val trackIds: List<String>,
    val snapshotId: String?,
)

data class DestinationState(
    val definitionId: String,
    val spotifyPlaylistId: String,
    val createdAt: Instant,
    val lastSyncedAt: Instant?,
    val lastSeenSnapshotId: String?,
    val canSync: Boolean = true,
)

data class OneTimePlaylistUpdate(
    val spotifyPlaylistId: String,
    val trackIds: List<String>,
    val lastSeenSnapshotId: String?,
    val appliedAt: Instant,
    val tracked: Boolean = false,
)

interface PlaylistDestinationGateway {
    fun create(
        accessToken: String,
        request: DestinationCreateRequest,
    ): String

    fun owns(
        accessToken: String,
        playlistId: String,
        ownerSpotifyUserId: String,
    ): Boolean = true

    fun replace(
        accessToken: String,
        playlistId: String,
        trackIds: List<String>,
    ): AuthoritativePlaylistState

    fun current(
        accessToken: String,
        playlistId: String,
    ): AuthoritativePlaylistState
}

class PlaylistDestinationService(
    private val store: SpotifyStore,
    private val gateway: PlaylistDestinationGateway,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun create(
        definitionId: String,
        ownerSpotifyUserId: String,
        accessToken: String,
        request: DestinationCreateRequest,
    ): DestinationState {
        requireDefinition(definitionId, ownerSpotifyUserId)
        val playlistId = gateway.create(accessToken, request)
        store.saveManagedPlaylist(definitionId, playlistId, ownerSpotifyUserId, clock.millis())
        return current(definitionId, ownerSpotifyUserId)!!
    }

    fun current(
        definitionId: String,
        ownerSpotifyUserId: String,
    ): DestinationState? = store.managedPlaylist(definitionId, ownerSpotifyUserId)?.toState()

    fun sync(
        definitionId: String,
        ownerSpotifyUserId: String,
        accessToken: String,
        trackIds: List<String>,
        expectedSnapshotId: String? = null,
    ): AuthoritativePlaylistState {
        val mapping =
            store.managedPlaylist(definitionId, ownerSpotifyUserId)
                ?: throw MissingDestinationException()
        if (expectedSnapshotId != null &&
            expectedSnapshotId != mapping.lastSeenSnapshotId
        ) {
            throw DestinationConflictException()
        }
        val authoritative = gateway.replace(accessToken, mapping.spotifyPlaylistId, trackIds)
        val now = clock.millis()
        store.updateManagedPlaylistState(definitionId, ownerSpotifyUserId, now, authoritative.snapshotId)
        store.publishPlaylistTrackIds(
            mapping.spotifyPlaylistId,
            authoritative.trackIds,
            now,
            ownerSpotifyUserId,
            authoritative.snapshotId,
        )
        return authoritative
    }

    fun oneTimeUpdate(
        definitionId: String,
        ownerSpotifyUserId: String,
        accessToken: String,
        spotifyPlaylistId: String,
        trackIds: List<String>,
        expectedSnapshotId: String? = null,
    ): OneTimePlaylistUpdate {
        requireDefinition(definitionId, ownerSpotifyUserId)
        if (!gateway.owns(accessToken, spotifyPlaylistId, ownerSpotifyUserId)) throw OwnerMismatchException()
        if (expectedSnapshotId != null) {
            val current = gateway.current(accessToken, spotifyPlaylistId)
            if (current.snapshotId != expectedSnapshotId) throw DestinationConflictException()
        }
        val authoritative = gateway.replace(accessToken, spotifyPlaylistId, trackIds)
        return OneTimePlaylistUpdate(
            spotifyPlaylistId,
            authoritative.trackIds,
            authoritative.snapshotId,
            clock.instant(),
        )
    }

    private fun requireDefinition(
        definitionId: String,
        owner: String,
    ) {
        val builtIn =
            PlaylistQueries.definitions(clock.instant().atZone(java.time.ZoneOffset.UTC).year, 2018).any {
                it.id.name ==
                    definitionId
            }
        if (!builtIn &&
            store.userPlaylistDefinition(definitionId, owner) == null
        ) {
            throw IllegalArgumentException("Playlist definition not found")
        }
    }

    private fun ManagedPlaylist.toState() =
        DestinationState(
            definitionId,
            spotifyPlaylistId,
            Instant.ofEpochMilli(createdAtMillis),
            lastSyncedAtMillis?.let(Instant::ofEpochMilli),
            lastSeenSnapshotId,
        )
}

class MissingDestinationException : RuntimeException("The definition has no Butler-created destination")

class DestinationConflictException : RuntimeException("The destination snapshot has changed")

class OwnerMismatchException : RuntimeException("The playlist does not belong to the authenticated owner")
