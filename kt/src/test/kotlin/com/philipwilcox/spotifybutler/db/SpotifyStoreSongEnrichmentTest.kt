package com.philipwilcox.spotifybutler.db

import com.philipwilcox.spotifybutler.spotify.SpotifyCacheSnapshot
import com.philipwilcox.spotifybutler.spotify.SpotifyTrack
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class SpotifyStoreSongEnrichmentTest {
    @Test
    fun `bounded enrichment returns unique requested songs in first-seen order`() {
        val path = Files.createTempDirectory("spotify-song-enrichment-").resolve("cache.db")

        SpotifyStore.open(path).use { store ->
            store.replaceCache(
                SpotifyCacheSnapshot(
                    savedTracks = emptyList(),
                    topTracks = listOf(track("track-one"), track("track-two")),
                    topArtists = emptyList(),
                    playlists = emptyList(),
                    playlistTracks = emptyList(),
                ),
                syncTimestampMillis = 10L,
                ownerSpotifyUserId = "owner-one",
            )

            assertEquals(
                listOf("track-two", "track-one"),
                store.songEnrichment(listOf("track-two", "missing", "track-one", "track-two")).map { it.id },
            )
            assertEquals("track-one", store.songEnrichment("track-one")?.id)
            assertEquals(emptyList(), store.songEnrichment(emptyList()))
        }
    }

    private fun track(id: String) =
        SpotifyTrack(
            name = id,
            id = id,
            href = "https://api.example.test/tracks/$id",
            uri = "spotify:track:$id",
            releaseDate = "2024-01-01",
            primaryArtistId = null,
            rawJson =
                "{\"id\":\"$id\",\"name\":\"$id\",\"href\":\"https://api.example.test/tracks/$id\"," +
                    "\"uri\":\"spotify:track:$id\"}",
        )
}
