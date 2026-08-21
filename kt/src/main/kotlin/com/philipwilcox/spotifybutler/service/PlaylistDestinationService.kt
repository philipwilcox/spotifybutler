package com.philipwilcox.spotifybutler.service

import com.philipwilcox.spotifybutler.db.ManagedPlaylist
import com.philipwilcox.spotifybutler.db.SpotifyStore
import java.time.Clock
import java.time.Instant

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

data class PublishPlaylistCandidate(
    val spotifyPlaylistId: String,
    val name: String,
    val description: String?,
    val itemCount: Int?,
    val displayUrl: String?,
)

data class PublishPlan(
    val definitionId: String,
    val playlistName: String,
    val action: PublishPlanAction,
    val candidates: List<PublishPlaylistCandidate> = emptyList(),
    val message: String? = null,
    val publishFlowId: String? = null,
)

enum class PublishPlanAction {
    CREATE,
    ADOPT,
    CHOOSE,
    BLOCKED,
}

enum class PublishAction {
    CREATE,
    ADOPT,
}

interface PlaylistDestinationGateway {
    fun create(
        accessToken: String,
        name: String,
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
    fun planPublish(
        definitionId: String,
        ownerSpotifyUserId: String,
        playlistName: String,
    ): PublishPlan {
        requireDefinition(definitionId, ownerSpotifyUserId)
        val matches = store.findPlaylistsByName(playlistName, ownerSpotifyUserId)
        val owned = matches.filter { it.ownerId == ownerSpotifyUserId }
        val action =
            when {
                owned.isEmpty() && matches.isEmpty() -> PublishPlanAction.CREATE
                owned.size == 1 -> PublishPlanAction.ADOPT
                owned.size > 1 -> PublishPlanAction.CHOOSE
                else -> PublishPlanAction.BLOCKED
            }
        return PublishPlan(
            definitionId,
            playlistName,
            action,
            owned.map { it.toCandidate(playlistName) },
            if (action == PublishPlanAction.BLOCKED) {
                "A playlist named \"$playlistName\" exists, but it is not owned by the authenticated Spotify user."
            } else {
                null
            },
        )
    }

    fun publish(
        definitionId: String,
        ownerSpotifyUserId: String,
        accessToken: String,
        playlistName: String,
        action: PublishAction,
        spotifyPlaylistId: String?,
        trackIds: List<String>,
    ): DestinationState {
        requireDefinition(definitionId, ownerSpotifyUserId)
        val playlistId =
            when (action) {
                PublishAction.CREATE -> createPlaylist(accessToken, playlistName, ownerSpotifyUserId)
                PublishAction.ADOPT -> adoptPlaylist(accessToken, playlistName, spotifyPlaylistId, ownerSpotifyUserId)
            }
        store.saveManagedPlaylist(definitionId, playlistId, ownerSpotifyUserId, clock.millis())
        val authoritative = gateway.replace(accessToken, playlistId, trackIds)
        val now = clock.millis()
        store.updateManagedPlaylistState(definitionId, ownerSpotifyUserId, now, authoritative.snapshotId)
        store.publishPlaylistTrackIds(
            playlistId,
            authoritative.trackIds,
            now,
            ownerSpotifyUserId,
            authoritative.snapshotId,
        )
        return current(definitionId, ownerSpotifyUserId)!!
    }

    private fun createPlaylist(
        accessToken: String,
        playlistName: String,
        ownerSpotifyUserId: String,
    ): String {
        if (store.findPlaylistsByName(playlistName, ownerSpotifyUserId).any { it.ownerId == ownerSpotifyUserId }) {
            throw DestinationConflictException()
        }
        return gateway.create(accessToken, playlistName)
    }

    private fun adoptPlaylist(
        accessToken: String,
        playlistName: String,
        spotifyPlaylistId: String?,
        ownerSpotifyUserId: String,
    ): String {
        val candidate =
            store
                .findPlaylistsByName(playlistName, ownerSpotifyUserId)
                .firstOrNull { it.id == spotifyPlaylistId && it.ownerId == ownerSpotifyUserId }
                ?: throw OwnerMismatchException()
        if (!gateway.owns(accessToken, candidate.id, ownerSpotifyUserId)) throw OwnerMismatchException()
        return candidate.id
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

    private fun requireDefinition(
        definitionId: String,
        owner: String,
    ) {
        val builtIn =
            PlaylistQueries.definitions(clock.instant().atZone(java.time.ZoneOffset.UTC).year).any {
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

    private fun com.philipwilcox.spotifybutler.db.ExistingPlaylistMetadata.toCandidate(defaultName: String) =
        PublishPlaylistCandidate(
            id,
            name ?: defaultName,
            description,
            itemCount,
            displayUrl,
        )
}

class MissingDestinationException : RuntimeException("The definition has no Butler-created destination")

class DestinationConflictException : RuntimeException("The destination snapshot has changed")

class OwnerMismatchException : RuntimeException("The playlist does not belong to the authenticated owner")
