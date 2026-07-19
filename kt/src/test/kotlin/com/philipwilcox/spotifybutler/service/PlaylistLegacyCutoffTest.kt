package com.philipwilcox.spotifybutler.service

import com.philipwilcox.spotifybutler.db.SpotifyStore
import com.philipwilcox.spotifybutler.spotify.SavedTrack
import com.philipwilcox.spotifybutler.spotify.SpotifyCacheSnapshot
import com.philipwilcox.spotifybutler.spotify.SpotifyTrack
import org.junit.jupiter.api.Tag
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Tag("playlist-generation-contract")
class PlaylistLegacyCutoffTest {
    @Test
    fun `legacy random liked exercises a real global cutoff`() {
        val tracks = (1..101).map { track("random-$it", "artist-$it") }
        val definition =
            PlaylistDefinition(
                PlaylistDefinitionId.RANDOM_LIKED_100,
                "Random cutoff",
                PlaylistQuery.RandomLiked(100),
            )

        withStore(tracks) { store ->
            val actual = store.execute(definition.query)
            logPlaylistGenerationReport(
                PlaylistGenerationTestReport(
                    fixtureName = "synthetic-global-cutoff",
                    definition = definition,
                    executionPath = "legacy",
                    actualTracks = actual,
                    notes = listOf("eligible=101", "expectedCount=100"),
                ),
            )
            assertEquals(100, actual.size)
            assertTrue(actual.all { track -> track in tracks })
        }
    }

    @Test
    fun `legacy per-artist query exercises a real quota cutoff`() {
        val tracks = (1..13).map { track("artist-cutoff-$it", "same-artist") }
        val definition =
            PlaylistDefinition(
                PlaylistDefinitionId.LIKED_PER_ARTIST,
                "Artist cutoff",
                PlaylistQuery.SavedPerArtist(12),
            )

        withStore(tracks) { store ->
            val actual = store.execute(definition.query)
            logPlaylistGenerationReport(
                PlaylistGenerationTestReport(
                    fixtureName = "synthetic-artist-cutoff",
                    definition = definition,
                    executionPath = "legacy",
                    actualTracks = actual,
                    notes = listOf("eligible=13", "expectedCount=12", "maxPerPrimaryArtist=12"),
                ),
            )
            assertEquals(12, actual.size)
            assertEquals(12, actual.map(SpotifyTrack::uri).toSet().size)
            assertTrue(actual.all { it.primaryArtistId == "same-artist" })
        }
    }

    private fun withStore(
        tracks: List<SpotifyTrack>,
        block: (SpotifyStore) -> Unit,
    ) {
        val path = Files.createTempDirectory("playlist-legacy-cutoff-").resolve("cache.db")
        SpotifyStore.open(path).use { store ->
            store.replaceCache(
                SpotifyCacheSnapshot(
                    savedTracks =
                        tracks.mapIndexed { index, track ->
                            SavedTrack("2026-01-${(index % 28 + 1).toString().padStart(2, '0')}T00:00:00Z", track)
                        },
                    topTracks = emptyList(),
                    topArtists = emptyList(),
                    playlists = emptyList(),
                    playlistTracks = emptyList(),
                ),
                syncTimestampMillis = 1L,
            )
            block(store)
        }
    }

    private fun track(
        id: String,
        artistId: String,
    ) = SpotifyTrack(
        name = id,
        id = id,
        href = "https://example.invalid/$id",
        uri = "spotify:track:$id",
        releaseDate = "2024",
        primaryArtistId = artistId,
        rawJson =
            "{\"name\":\"$id\",\"id\":\"$id\",\"href\":\"https://example.invalid/$id\"," +
                "\"uri\":\"spotify:track:$id\",\"album\":{\"release_date\":\"2024\"}," +
                "\"artists\":[{\"id\":\"$artistId\"}]}",
        artistIds = listOf(artistId),
    )
}
