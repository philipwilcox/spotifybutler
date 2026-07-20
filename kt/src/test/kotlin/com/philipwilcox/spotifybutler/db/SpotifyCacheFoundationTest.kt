package com.philipwilcox.spotifybutler.db

import com.philipwilcox.spotifybutler.spotify.SpotifyCacheSnapshot
import com.philipwilcox.spotifybutler.spotify.SpotifyPlaylist
import com.philipwilcox.spotifybutler.spotify.SpotifyPlaylistItem
import com.philipwilcox.spotifybutler.spotify.SpotifyTrack
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SpotifyCacheFoundationTest {
    @Test
    fun `failed replacement leaves the prior completed revision and items usable`() {
        val path = Files.createTempDirectory("cache-foundation-").resolve("cache.db")
        SpotifyStore.open(path).use { store ->
            val original = snapshot(listOf(item(0, "track-one")))
            store.replaceCache(original, syncTimestampMillis = 10L, ownerSpotifyUserId = "owner-one")
            val priorRevision = requireNotNull(store.cacheMetadata()).revision

            assertFailsWith<Exception> {
                store.replaceCache(
                    snapshot(listOf(item(0, "track-two"), item(0, "track-two"))),
                    syncTimestampMillis = 20L,
                    ownerSpotifyUserId = "owner-one",
                )
            }

            assertEquals(priorRevision, store.cacheMetadata()?.revision)
            assertEquals(10L, store.cacheMetadata()?.syncTimestampMillis)
            assertEquals(listOf("track-one"), store.playlistItems("playlist-one").mapNotNull { it.itemId })
            assertEquals(listOf("track-one"), store.songs().map(SpotifyTrack::id))
        }
    }

    private fun snapshot(items: List<SpotifyPlaylistItem>) =
        SpotifyCacheSnapshot(
            savedTracks = emptyList(),
            topTracks = emptyList(),
            topArtists = emptyList(),
            playlists =
                listOf(
                    SpotifyPlaylist(
                        name = "Generated",
                        id = "playlist-one",
                        href = "https://api.example.test/playlists/playlist-one",
                        uri = "spotify:playlist:playlist-one",
                        tracksHref = "https://api.example.test/playlists/playlist-one/items",
                    ),
                ),
            playlistTracks = emptyList(),
            playlistItems = items,
        )

    private fun item(
        position: Int,
        id: String,
    ) = SpotifyPlaylistItem(
        playlistId = "playlist-one",
        playlistName = "Generated",
        position = position,
        addedAt = null,
        addedById = null,
        isLocal = false,
        itemType = "track",
        isPlayable = true,
        itemId = id,
        itemUri = "spotify:track:$id",
        status = "playable",
        rawJson = "{\"item\":${track(id).rawJson}}",
        track = track(id),
    )

    private fun track(id: String) =
        SpotifyTrack(
            name = id,
            id = id,
            href = "https://api.example.test/tracks/$id",
            uri = "spotify:track:$id",
            releaseDate = "2024",
            primaryArtistId = "artist-one",
            rawJson =
                """
                {
                    "name":"$id",
                    "id":"$id",
                    "href":"https://api.example.test/tracks/$id",
                    "uri":"spotify:track:$id",
                    "artists":[{"id":"artist-one"}]
                }
                """.trimIndent(),
        )
}
