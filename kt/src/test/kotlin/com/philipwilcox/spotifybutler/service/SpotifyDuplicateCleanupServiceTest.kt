package com.philipwilcox.spotifybutler.service

import com.philipwilcox.spotifybutler.db.SpotifyStore
import com.philipwilcox.spotifybutler.spotify.SavedTrack
import com.philipwilcox.spotifybutler.spotify.SpotifyApiClient
import com.philipwilcox.spotifybutler.spotify.SpotifyCacheSnapshot
import com.philipwilcox.spotifybutler.spotify.SpotifyHttpResponse
import com.philipwilcox.spotifybutler.spotify.SpotifyHttpTransport
import com.philipwilcox.spotifybutler.spotify.SpotifyTrack
import java.net.URI
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SpotifyDuplicateCleanupServiceTest {
    @Test
    fun `remote duplicate removal succeeds before local deletion`() {
        val transport = DuplicateTransport()
        val client = SpotifyApiClient(apiBaseUri = URI("https://api.example.test/"), transport = transport)
        withStore { store ->
            store.replaceCacheContent(snapshot())
            assertEquals(2, store.exportTables().savedTracks.size)
            assertEquals(listOf("duplicate-old"), store.duplicateSavedTrackIds())

            val result = SpotifyDuplicateCleanupService(store, client).clean("token")

            assertEquals(1, result.removedTrackCount)
            assertEquals(emptyList(), store.duplicateSavedTrackIds())
            assertEquals(false, store.hasCompletedSync())
            assertEquals(true, transport.deleteCalled)
        }
    }

    @Test
    fun `failed remote duplicate removal leaves local duplicates and sync incomplete`() {
        val transport = DuplicateTransport(failureStatus = 500)
        val client = SpotifyApiClient(apiBaseUri = URI("https://api.example.test/"), transport = transport)
        withStore { store ->
            store.replaceCacheContent(snapshot())

            assertFailsWith<IllegalArgumentException> {
                SpotifyDuplicateCleanupService(store, client).clean("token")
            }

            assertEquals(listOf("duplicate-old"), store.duplicateSavedTrackIds())
            assertEquals(false, store.hasCompletedSync())
        }
    }

    private fun withStore(block: (SpotifyStore) -> Unit) {
        val path = Files.createTempDirectory("duplicate-cleanup-").resolve("cache.db")
        SpotifyStore.open(path).use(block)
    }

    private fun snapshot(): SpotifyCacheSnapshot {
        val first = track("duplicate-old", "2026-01-01", "Duplicate")
        val second = track("duplicate-new", "2026-01-02", "Duplicate")
        return SpotifyCacheSnapshot(
            savedTracks = listOf(SavedTrack("2026-01-01", first), SavedTrack("2026-01-02", second)),
            topTracks = emptyList(),
            topArtists = emptyList(),
            playlists = emptyList(),
            playlistTracks = emptyList(),
        )
    }

    private fun track(
        id: String,
        date: String,
        name: String = id,
    ) = SpotifyTrack(
        name,
        id,
        "href-$id",
        "spotify:track:$id",
        date,
        "artist",
        "{\"id\":\"$id\",\"name\":\"$name\",\"href\":\"href-$id\",\"uri\":\"spotify:track:$id\"}",
    )
}

private class DuplicateTransport(
    private val failureStatus: Int? = null,
) : SpotifyHttpTransport {
    var deleteCalled = false

    override fun get(
        uri: URI,
        accessToken: String,
    ): SpotifyHttpResponse = SpotifyHttpResponse(200, "{}")

    override fun delete(
        uri: URI,
        accessToken: String,
    ): SpotifyHttpResponse {
        deleteCalled = true
        return SpotifyHttpResponse(failureStatus ?: 200, "")
    }
}
