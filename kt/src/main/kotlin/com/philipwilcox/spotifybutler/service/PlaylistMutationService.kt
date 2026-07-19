package com.philipwilcox.spotifybutler.service

import com.philipwilcox.spotifybutler.spotify.SpotifyApiClient
import com.philipwilcox.spotifybutler.spotify.SpotifyTrack

class SpotifyPlaylistMutationClient(
    private val apiClient: SpotifyApiClient,
    private val accessToken: String,
) : PlaylistMutationClient {
    override fun createPlaylist(name: String): String = apiClient.createPlaylist(accessToken, name)

    override fun addTracks(
        playlistId: String,
        tracks: List<SpotifyTrack>,
    ) {
        apiClient.addTracks(accessToken, playlistId, tracks)
    }

    override fun replaceTracks(
        playlistId: String,
        tracks: List<SpotifyTrack>,
    ) {
        apiClient.replaceTracks(accessToken, playlistId, tracks)
    }
}

interface PlaylistMutationClient {
    fun createPlaylist(name: String): String

    fun addTracks(
        playlistId: String,
        tracks: List<SpotifyTrack>,
    )

    fun replaceTracks(
        playlistId: String,
        tracks: List<SpotifyTrack>,
    )
}

fun interface TrackShuffler {
    fun shuffle(tracks: List<SpotifyTrack>): List<SpotifyTrack>
}

sealed interface PlaylistMutationOutcome {
    val name: String
    val playlistId: String?
    val trackCount: Int
    val dryRun: Boolean

    data class Created(
        override val name: String,
        override val playlistId: String?,
        override val trackCount: Int,
        override val dryRun: Boolean,
    ) : PlaylistMutationOutcome

    data class Replaced(
        override val name: String,
        override val playlistId: String,
        val addedCount: Int,
        val removedCount: Int,
        override val trackCount: Int,
        override val dryRun: Boolean,
    ) : PlaylistMutationOutcome
}

class PlaylistMutationFailure(
    val completed: List<PlaylistMutationOutcome>,
    val failedPlan: PlaylistPlan,
    cause: Throwable,
) : RuntimeException("Playlist mutation failed for ${failedPlan.definition.name}", cause)

class PlaylistMutationService(
    private val client: PlaylistMutationClient,
    private val shuffler: TrackShuffler = TrackShuffler { tracks -> tracks },
) {
    @Suppress("TooGenericExceptionCaught")
    fun apply(
        plans: List<PlaylistPlan>,
        dryRun: Boolean,
    ): List<PlaylistMutationOutcome> {
        val outcomes = mutableListOf<PlaylistMutationOutcome>()
        for (plan in plans) {
            try {
                outcomes += applyPlan(plan, dryRun)
            } catch (exception: RuntimeException) {
                throw PlaylistMutationFailure(outcomes.toList(), plan, exception)
            }
        }
        return outcomes
    }

    private fun applyPlan(
        plan: PlaylistPlan,
        dryRun: Boolean,
    ): PlaylistMutationOutcome {
        val shuffledTracks = shuffler.shuffle(plan.desiredTracks)
        val existing = plan.existingPlaylist
        if (existing == null) {
            val playlistId = if (dryRun) null else client.createPlaylist(plan.definition.name)
            if (!dryRun && shuffledTracks.isNotEmpty()) client.addTracks(playlistId!!, shuffledTracks)
            return PlaylistMutationOutcome.Created(plan.definition.name, playlistId, shuffledTracks.size, dryRun)
        }
        if (!dryRun) client.replaceTracks(existing.id, shuffledTracks)
        return PlaylistMutationOutcome.Replaced(
            name = plan.definition.name,
            playlistId = existing.id,
            addedCount = plan.tracksToAdd.size,
            removedCount = plan.tracksToRemove.size,
            trackCount = shuffledTracks.size,
            dryRun = dryRun,
        )
    }
}
