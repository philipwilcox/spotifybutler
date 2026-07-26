package com.philipwilcox.spotifybutler.spotify

import com.philipwilcox.spotifybutler.service.PublishOperationLog
import java.net.URI
import java.net.URLDecoder
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SpotifyApiClientMutationTest {
    @Test
    fun `playlist replacement uses current Spotify item resources and batch sizes`() {
        val transport = RecordingMutationTransport()
        val client = SpotifyApiClient(apiBaseUri = URI("https://api.example.test/"), transport = transport)
        val trackIds = (1..205).map { "track-$it" }

        client.replaceTrackIds("token", "playlist-id", trackIds)
        client.replaceTrackIds("token", "playlist-id", emptyList())

        assertEquals(listOf("PUT", "POST", "POST", "PUT"), transport.methods)
        assertEquals(listOf(100, 100, 5, 0), transport.uriCounts)
    }

    @Test
    fun `create playlist uses the current me resource and reports mutation failures`() {
        val transport = RecordingMutationTransport()
        val client = SpotifyApiClient(apiBaseUri = URI("https://api.example.test/"), transport = transport)

        assertEquals(
            "created-playlist",
            client.createPlaylistMetadata("token", "Generated Playlist").id,
        )
        assertEquals("https://api.example.test/v1/me/playlists", transport.uris[0].substringBefore('?'))

        transport.failureStatus = 500
        assertFailsWith<IllegalArgumentException> {
            client.replaceTrackIds("token", "playlist-id", listOf("failure"))
        }
    }

    @Test
    fun `authoritative playlist replacement uses the mutation snapshot without rereading playlist items`() {
        val transport = RecordingMutationTransport()
        val client = SpotifyApiClient(apiBaseUri = URI("https://api.example.test/"), transport = transport)

        val state = client.replaceTrackIdsAuthoritative("token", "playlist-id", listOf("track-1", "track-2"))

        assertEquals(listOf("track-1", "track-2"), state.trackIds)
        assertEquals("snapshot-write", state.snapshotId)
        assertEquals(listOf("PUT"), transport.methods)
    }

    @Test
    fun `publish logging context counts replacement calls and records the last step`() {
        val transport = RecordingMutationTransport()
        val client = SpotifyApiClient(apiBaseUri = URI("https://api.example.test/"), transport = transport)

        PublishOperationLog.with("publish-adopt", "flow-1", expectedExternalCalls = 2) { log ->
            client.replaceTrackIds("token", "playlist-id", (1..101).map { "track-$it" })
            assertContains(log.logFields(), "flowId=flow-1")
            assertContains(log.logFields(), "step=2 of 2")
            assertContains(log.logFields(), "stepDurationMs=")
            assertContains(log.logFields(), "elapsedMs=")
        }
    }
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
        return SpotifyHttpResponse(
            failureStatus ?: 201,
            "{\"id\":\"created-playlist\",\"snapshot_id\":\"snapshot-write\"}",
        )
    }

    override fun put(
        uri: URI,
        accessToken: String,
        body: String,
    ): SpotifyHttpResponse {
        record("PUT", uri, body)
        return SpotifyHttpResponse(failureStatus ?: 201, "{\"snapshot_id\":\"snapshot-write\"}")
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
