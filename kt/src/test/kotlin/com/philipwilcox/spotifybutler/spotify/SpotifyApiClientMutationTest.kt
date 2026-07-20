package com.philipwilcox.spotifybutler.spotify

import java.net.URI
import java.net.URLDecoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SpotifyApiClientMutationTest {
    @Test
    fun `playlist writes use current Spotify item resources and batch sizes`() {
        val transport = RecordingMutationTransport()
        val client = SpotifyApiClient(apiBaseUri = URI("https://api.example.test/"), transport = transport)
        val tracks = (1..205).map { track("track-$it") }

        client.addTracks("token", "playlist-id", tracks)
        client.replaceTracks("token", "playlist-id", tracks)
        client.replaceTracks("token", "playlist-id", emptyList())

        assertEquals(listOf("POST", "POST", "POST", "PUT", "POST", "POST", "PUT"), transport.methods)
        assertEquals(listOf(100, 100, 5, 100, 100, 5, 0), transport.uriCounts)
    }

    @Test
    fun `saved track removal uses distinct IDs in batches of fifty`() {
        val transport = RecordingMutationTransport()
        val client = SpotifyApiClient(apiBaseUri = URI("https://api.example.test/"), transport = transport)
        val ids = (1..101).map { "track-$it" } + "track-1"

        client.removeSavedTracks("token", ids)

        assertEquals(listOf("DELETE", "DELETE", "DELETE"), transport.methods)
        assertEquals(listOf(40, 40, 21), transport.uriCounts)
        assertEquals(
            listOf("/v1/me/library", "/v1/me/library", "/v1/me/library"),
            transport.uris.map(URI::create).map {
                it.path
            },
        )
        assertEquals(true, transport.uris.all { it.contains("uris=") })
    }

    @Test
    fun `create playlist uses the current me resource and reports mutation failures`() {
        val transport = RecordingMutationTransport()
        val client = SpotifyApiClient(apiBaseUri = URI("https://api.example.test/"), transport = transport)

        assertEquals("created-playlist", client.createPlaylist("token", "Generated Playlist"))
        assertEquals("https://api.example.test/v1/me/playlists", transport.uris[0].substringBefore('?'))

        transport.failureStatus = 500
        assertFailsWith<IllegalArgumentException> {
            client.addTracks("token", "playlist-id", listOf(track("failure")))
        }
    }

    private fun track(id: String) =
        SpotifyTrack(id, id, "https://api.example.test/tracks/$id", "spotify:track:$id", "2026", "artist", "{}")
}

private class RecordingMutationTransport : SpotifyHttpTransport {
    val methods = mutableListOf<String>()
    val uris = mutableListOf<String>()
    val uriCounts = mutableListOf<Int>()
    val bodies = mutableListOf<String>()
    var failureStatus: Int? = null

    override fun get(
        uri: URI,
        accessToken: String,
    ): SpotifyHttpResponse {
        methods += "GET"
        uris += uri.toString()
        uriCounts += 0
        return SpotifyHttpResponse(200, "{\"id\":\"user-id\",\"display_name\":\"Test User\"}")
    }

    override fun post(
        uri: URI,
        accessToken: String,
        body: String,
    ): SpotifyHttpResponse {
        record("POST", uri, body)
        return SpotifyHttpResponse(failureStatus ?: 201, "{\"id\":\"created-playlist\"}")
    }

    override fun put(
        uri: URI,
        accessToken: String,
        body: String,
    ): SpotifyHttpResponse {
        record("PUT", uri, body)
        return SpotifyHttpResponse(failureStatus ?: 201, "{}")
    }

    override fun delete(
        uri: URI,
        accessToken: String,
    ): SpotifyHttpResponse {
        methods += "DELETE"
        uris += uri.toString()
        uriCounts +=
            URLDecoder
                .decode(uri.query.substringAfter("uris="), Charsets.UTF_8)
                .split(',')
                .size
        return SpotifyHttpResponse(failureStatus ?: 200, "")
    }

    private fun record(
        method: String,
        uri: URI,
        body: String,
    ) {
        methods += method
        uris += uri.toString()
        bodies += body
        uriCounts += Regex("spotify:track:").findAll(body).count()
    }
}
