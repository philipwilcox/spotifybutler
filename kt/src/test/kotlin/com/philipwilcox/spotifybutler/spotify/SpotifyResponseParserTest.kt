package com.philipwilcox.spotifybutler.spotify

import kotlin.test.Test
import kotlin.test.assertEquals

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
}
