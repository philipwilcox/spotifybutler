package com.philipwilcox.spotifybutler.service

import com.philipwilcox.spotifybutler.spotify.SpotifyTrack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PlaylistMutationServiceTest {
    @Test
    fun `absent playlists are created and populated`() {
        val client = RecordingMutationClient()
        val plan = plan("new playlist", listOf(track("one")), existingId = null)

        val outcome = PlaylistMutationService(client, TrackShuffler { it }).apply(listOf(plan), false).single()

        assertEquals(listOf("create new playlist", "add new playlist-id:one"), client.calls)
        assertEquals("new playlist-id", outcome.playlistId)
    }

    @Test
    fun `existing playlists are replaced with the complete shuffled desired list`() {
        val client = RecordingMutationClient()
        val tracks = listOf(track("one"), track("two"))
        val plan = plan("playlist", tracks, existingId = "playlist-id")

        val outcomes = PlaylistMutationService(client, TrackShuffler { it.reversed() }).apply(listOf(plan), false)

        assertEquals(listOf("replace playlist-id:two,one"), client.calls)
        assertEquals(2, (outcomes.single() as PlaylistMutationOutcome.Replaced).trackCount)
    }

    @Test
    fun `dry run makes no playlist mutation calls`() {
        val client = RecordingMutationClient()
        val plan = plan("playlist", listOf(track("one")), existingId = null)

        val outcome = PlaylistMutationService(client, TrackShuffler { it }).apply(listOf(plan), true).single()

        assertEquals(emptyList(), client.calls)
        assertEquals(true, outcome.dryRun)
        assertEquals(null, outcome.playlistId)
    }

    @Test
    fun `empty existing playlists are explicitly cleared`() {
        val client = RecordingMutationClient()
        val plan = plan("empty playlist", emptyList(), existingId = "playlist-id")

        PlaylistMutationService(client, TrackShuffler { it }).apply(listOf(plan), false)

        assertEquals(listOf("replace playlist-id:"), client.calls)
    }

    @Test
    fun `first mutation failure stops later playlists`() {
        val client = RecordingMutationClient(failOn = "two-id")
        val first = plan("first", listOf(track("one")), existingId = "one-id")
        val second = plan("second", listOf(track("two")), existingId = "two-id")
        val third = plan("third", listOf(track("three")), existingId = "three-id")

        val failure =
            assertFailsWith<PlaylistMutationFailure> {
                PlaylistMutationService(client, TrackShuffler { it }).apply(listOf(first, second, third), false)
            }

        assertEquals(listOf("replace one-id:one", "replace two-id:two"), client.calls)
        assertEquals(listOf(first.definition.name), failure.completed.map { it.name })
        assertEquals(second.definition.name, failure.failedPlan.definition.name)
    }

    private fun plan(
        name: String,
        tracks: List<SpotifyTrack>,
        existingId: String?,
    ): PlaylistPlan {
        val definition = PlaylistDefinition(PlaylistDefinitionId.RECENT_LIKED_100, name, PlaylistQuery.RecentLiked(100))
        val existing = existingId?.let { ExistingPlaylist(it, emptyList()) }
        return PlaylistPlan(definition, tracks, existing, emptyList(), tracks, emptyList())
    }

    private fun track(id: String) =
        SpotifyTrack(id, id, "https://example.invalid/$id", "spotify:track:$id", "2020", "artist", "{}")
}

private class RecordingMutationClient(
    private val failOn: String? = null,
) : PlaylistMutationClient {
    val calls = mutableListOf<String>()

    override fun createPlaylist(name: String): String {
        calls += "create $name"
        return "$name-id"
    }

    override fun addTracks(
        playlistId: String,
        tracks: List<SpotifyTrack>,
    ) {
        calls += "add $playlistId:${tracks.joinToString(",") { it.id }}"
    }

    override fun replaceTracks(
        playlistId: String,
        tracks: List<SpotifyTrack>,
    ) {
        calls += "replace $playlistId:${tracks.joinToString(",") { it.id }}"
        if (failOn == playlistId) error("planned failure")
    }
}
