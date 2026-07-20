package com.philipwilcox.spotifybutler.spotify

import com.philipwilcox.spotifybutler.config.Secrets
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpotifyAuthClientTest {
    @Test
    fun `authorization uses PKCE and callback state is single use`() {
        val client =
            SpotifyAuthClient(
                secrets = Secrets("client", "secret", "http://127.0.0.1:8888/callback", null),
                clock = Clock.fixed(Instant.parse("2030-01-01T00:00:00Z"), ZoneOffset.UTC),
            )

        val authorization = client.beginAuthorization(refresh = false, returnTo = "/app")
        val location = authorization.location.toString()
        val callback = client.consumeCallbackAuthorization(authorization.state, authorization.state)

        assertTrue(location.contains("code_challenge_method=S256"))
        assertTrue(location.contains("code_challenge="))
        assertNotNull(callback)
        assertTrue(callback.codeVerifier.isNotBlank())
        assertTrue(callback.returnTo == "/app")
        assertNull(client.consumeCallbackAuthorization(authorization.state, authorization.state))
    }
}
