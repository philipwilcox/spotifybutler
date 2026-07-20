package com.philipwilcox.spotifybutler.spotify

import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpotifyResponseParserTest {
    @Test
    fun `current playlist items shape is parsed without changing cached track identity`() {
        val playlist =
            parseSpotifyPlaylist(
                parseSpotifyResponse(
                    """{
                        "name":"Generated",
                        "id":"playlist-id",
                        "href":"https://api.example.test/playlists/playlist-id",
                        "uri":"spotify:playlist:playlist-id",
                        "items":{"href":"https://api.example.test/playlists/playlist-id/items"},
                        "snapshot_id":"snapshot-1"
                    }""",
                ),
            )
        val track =
            parsePlaylistTrack(
                parseSpotifyResponse(
                    """{
                        "added_at":"2026-01-01T00:00:00Z",
                        "item":{
                            "name":"Track",
                            "id":"track-id",
                            "href":"https://api.example.test/tracks/track-id",
                            "uri":"spotify:track:track-id",
                            "artists":[],
                            "album":{"release_date":"2026"}
                        }
                    }""",
                ),
                playlist.name,
            )

        assertEquals("https://api.example.test/playlists/playlist-id/items", playlist.tracksHref)
        assertEquals("spotify:track:track-id", track?.track?.uri)
        assertEquals("2026-01-01T00:00:00Z", track?.addedAt)
    }

    @Test
    fun `playlist item parser preserves position, duplicates, and inaccessible item states`() {
        val playlist =
            SpotifyPlaylist(
                name = "Generated",
                id = "playlist-id",
                href = "https://api.example.test/playlists/playlist-id",
                uri = "spotify:playlist:playlist-id",
                tracksHref = "https://api.example.test/playlists/playlist-id/items",
                snapshotId = "snapshot-1",
            )
        val playable =
            parsePlaylistItem(
                parseSpotifyResponse(
                    """
                    {
                        "added_at":"2026-01-01T00:00:00Z",
                        "item":{"type":"track","id":"track-id","name":"Track",
                        "href":"https://api.example.test/tracks/track-id",
                        "uri":"spotify:track:track-id","artists":[]}
                    }
                    """.trimIndent(),
                ),
                playlist,
                position = 17,
            )
        val episode =
            parsePlaylistItem(
                parseSpotifyResponse(
                    """{"item":{"type":"episode","id":"episode-id","uri":"spotify:episode:episode-id"}}""",
                ),
                playlist,
                position = 18,
            )
        val inaccessible =
            parsePlaylistItem(
                parseSpotifyResponse("""{"added_at":null,"item":null}"""),
                playlist,
                position = 19,
            )

        assertEquals(17, playable.position)
        assertTrue(playable.isPlayable)
        assertEquals("track-id", playable.itemId)
        assertEquals("playable", playable.status)
        assertFalse(episode.isPlayable)
        assertEquals("unsupported_type", episode.status)
        assertNull(episode.track)
        assertFalse(inaccessible.isPlayable)
        assertEquals("inaccessible", inaccessible.status)
        assertNull(inaccessible.itemId)
    }

    @Test
    fun `cache fetch uses response offsets for playlist item positions`() {
        val client = SpotifyApiClient(apiBaseUri = URI("https://api.example.test/"), transport = OffsetTransport())

        val snapshot = client.fetchCache("fixture-token")

        assertEquals(listOf(50), snapshot.playlistItems.map(SpotifyPlaylistItem::position))
        assertEquals(listOf("track-id"), snapshot.playlistTracks.map { it.track.id })
    }
}

private class OffsetTransport : SpotifyHttpTransport {
    override fun get(
        uri: URI,
        accessToken: String,
    ): SpotifyHttpResponse {
        require(accessToken == "fixture-token")
        return when (uri.path) {
            "/v1/me/tracks", "/v1/me/top/tracks", "/v1/me/top/artists" -> emptyPage()
            "/v1/me/playlists" ->
                SpotifyHttpResponse(
                    200,
                    """
                    {
                        "items":[{"name":"Playlist","id":"playlist-id",
                        "href":"https://api.example.test/v1/playlists/playlist-id",
                        "uri":"spotify:playlist:playlist-id",
                        "items":{"href":"https://api.example.test/v1/playlists/playlist-id/items"}}],
                        "next":null
                    }
                    """.trimIndent(),
                )
            "/v1/playlists/playlist-id/items" ->
                SpotifyHttpResponse(
                    200,
                    """
                    {
                        "offset":50,
                        "items":[{"item":{"type":"track","name":"Track","id":"track-id",
                        "href":"https://api.example.test/tracks/track-id",
                        "uri":"spotify:track:track-id","artists":[]}}],
                        "next":null
                    }
                    """.trimIndent(),
                )
            else -> error("Unexpected URI $uri")
        }
    }

    private fun emptyPage() = SpotifyHttpResponse(200, "{\"items\":[],\"next\":null}")
}
