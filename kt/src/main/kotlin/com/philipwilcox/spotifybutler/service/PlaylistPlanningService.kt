package com.philipwilcox.spotifybutler.service

import com.philipwilcox.spotifybutler.db.SpotifyStore
import com.philipwilcox.spotifybutler.spotify.SpotifyTrack

data class ExistingPlaylist(
    val id: String,
    val snapshotId: String?,
    val tracks: List<SpotifyTrack>,
)

data class PlaylistPlan(
    val definition: PlaylistDefinition,
    val desiredTracks: List<SpotifyTrack>,
    val existingPlaylist: ExistingPlaylist?,
    val alreadyPresentTracks: List<SpotifyTrack>,
    val tracksToAdd: List<SpotifyTrack>,
    val tracksToRemove: List<SpotifyTrack>,
)

class PlaylistPlanningService(
    private val store: SpotifyStore,
) {
    fun plan(definitions: List<PlaylistDefinition>): List<PlaylistPlan> =
        definitions.map { definition ->
            val desired = store.execute(definition.query)
            val metadata = store.findPlaylistByName(definition.name)
            val existingTracks = store.findPlaylistTracksByName(definition.name)
            val existing = metadata?.let { ExistingPlaylist(it.id, it.snapshotId, existingTracks) }
            classify(definition, desired, existing)
        }

    private fun classify(
        definition: PlaylistDefinition,
        desired: List<SpotifyTrack>,
        existing: ExistingPlaylist?,
    ): PlaylistPlan {
        val existingUris = existing?.tracks.orEmpty().mapTo(mutableSetOf(), SpotifyTrack::uri)
        val desiredUris = desired.mapTo(mutableSetOf(), SpotifyTrack::uri)
        return PlaylistPlan(
            definition = definition,
            desiredTracks = desired,
            existingPlaylist = existing,
            alreadyPresentTracks = desired.filter { it.uri in existingUris },
            tracksToAdd = desired.filter { it.uri !in existingUris },
            tracksToRemove = existing?.tracks.orEmpty().filter { it.uri !in desiredUris },
        )
    }
}
