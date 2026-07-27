package com.philipwilcox.spotifybutler.spotify

import com.philipwilcox.spotifybutler.config.SpotifyRetryConfig
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SpotifyApiClientRetryTest {
    @Test
    fun `successful recovery uses exponential delays`() {
        val transport =
            SequenceTransport(
                SpotifyHttpResponse(429, "{}"),
                SpotifyHttpResponse(429, "{}"),
                SpotifyHttpResponse(200, USER_BODY),
            )
        val delays = mutableListOf<Double>()
        val client = client(transport, SpotifyRetryConfig(4, 1, 2.0), delays)

        assertEquals("user-id", client.getCurrentUser("token").id)
        assertEquals(listOf(1.0, 2.0), delays)
    }

    @Test
    fun `retry after is the lower bound for computed backoff`() {
        val transport =
            SequenceTransport(
                SpotifyHttpResponse(429, "{}", retryAfterSeconds = 3),
                SpotifyHttpResponse(429, "{}", retryAfterSeconds = 1),
                SpotifyHttpResponse(200, USER_BODY),
            )
        val delays = mutableListOf<Double>()
        val client = client(transport, SpotifyRetryConfig(2, 2, 2.0), delays)

        client.getCurrentUser("token")

        assertEquals(listOf(3.0, 4.0), delays)
    }

    @Test
    fun `exhausted and nonretryable responses are not retried`() {
        val exhausted =
            SequenceTransport(
                SpotifyHttpResponse(429, "{}"),
                SpotifyHttpResponse(429, "{}"),
                SpotifyHttpResponse(429, "{}"),
            )
        assertFailsWith<IllegalArgumentException> {
            client(exhausted, SpotifyRetryConfig(2, 1, 2.0), mutableListOf()).getCurrentUser("token")
        }
        assertEquals(3, exhausted.calls)

        val quota = SequenceTransport(SpotifyHttpResponse(429, "{\"error\":{\"reason\":\"QUOTA_EXCEEDED\"}}"))
        assertFailsWith<IllegalArgumentException> {
            client(quota, SpotifyRetryConfig(4, 1, 2.0), mutableListOf()).getCurrentUser("token")
        }
        assertEquals(1, quota.calls)
    }

    @Test
    fun `pagination retries the current page before requesting next page`() {
        val firstPage = "https://api.example.test/v1/me/top/tracks?limit=50&offset=0"
        val secondPage = "https://api.example.test/v1/me/top/tracks?limit=50&offset=50"
        val transport =
            SequenceTransport(
                SpotifyHttpResponse(200, pageBody("first", secondPage)),
                SpotifyHttpResponse(429, "{}"),
                SpotifyHttpResponse(200, pageBody("second", null)),
            )
        val client = client(transport, SpotifyRetryConfig(1, 1, 2.0), mutableListOf())

        assertEquals(listOf("first", "second"), client.fetchTopTracks("token").map(SpotifyTrack::id))
        assertEquals(listOf(firstPage, secondPage, secondPage), transport.uris)
    }

    private fun client(
        transport: SequenceTransport,
        config: SpotifyRetryConfig,
        delays: MutableList<Double>,
    ) = SpotifyApiClient(
        apiBaseUri = URI("https://api.example.test/"),
        transport = transport,
        retryConfig = config,
        retrySleeper = SpotifyRetrySleeper { delays += it },
    )

    private companion object {
        const val USER_BODY = "{\"id\":\"user-id\",\"display_name\":\"Test User\"}"
    }

    private fun pageBody(
        id: String,
        next: String?,
    ) =
        """{"items":[{"id":"$id","name":"$id","href":"https://api.example.test/tracks/$id","uri":"spotify:track:$id","artists":[]}],"next":${next?.let {
            "\"$it\""
        } ?: "null"}}"""
}

private class SequenceTransport(
    private vararg val responses: SpotifyHttpResponse,
) : SpotifyHttpTransport {
    var calls = 0
    val uris = mutableListOf<String>()

    override fun get(
        uri: URI,
        accessToken: String,
    ): SpotifyHttpResponse {
        uris += uri.toString()
        return responses.getOrNull(calls++) ?: error("No response configured for $uri")
    }
}
