package com.philipwilcox.spotifybutler.config

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class SecretsTest {
    @Test
    fun `environment variables provide secrets without a file`() {
        val secrets = Secrets.load(environment = environment())

        assertEquals("env-client", secrets.clientId)
        assertEquals("env-secret", secrets.clientSecret)
        assertEquals("https://butler.example/callback", secrets.redirectUri)
    }

    @Test
    fun `complete environment variables allow the configured file to be absent`() {
        val missingFile = Files.createTempDirectory("spotify-butler-secrets").resolve("missing.properties")

        val secrets =
            Secrets.load(
                environment =
                    environment() +
                        mapOf("SPOTIFY_BUTLER_SECRETS_FILE" to missingFile.toString()),
            )

        assertEquals("env-client", secrets.clientId)
        assertEquals("env-secret", secrets.clientSecret)
        assertEquals("https://butler.example/callback", secrets.redirectUri)
    }

    @Test
    fun `environment variables override matching properties`() {
        val file = Files.createTempFile("spotify-butler-secrets", ".properties")
        try {
            Files.writeString(
                file,
                """
                spotify.clientId=file-client
                spotify.clientSecret=file-secret
                spotify.redirectUri=http://127.0.0.1:8888/callback
                spotify.allowedUserId=file-user
                """.trimIndent(),
            )

            val secrets =
                Secrets.load(
                    environment =
                        environment() +
                            mapOf(
                                "SPOTIFY_BUTLER_SECRETS_FILE" to file.toString(),
                                "SPOTIFY_BUTLER_CLIENT_ID" to "env-client",
                            ),
                )

            assertEquals("env-client", secrets.clientId)
            assertEquals("env-secret", secrets.clientSecret)
            assertEquals("https://butler.example/callback", secrets.redirectUri)
            assertEquals("file-user", secrets.allowedSpotifyUserId)
        } finally {
            Files.deleteIfExists(file)
        }
    }

    private fun environment(): Map<String, String> =
        mapOf(
            "SPOTIFY_BUTLER_CLIENT_ID" to "env-client",
            "SPOTIFY_BUTLER_CLIENT_SECRET" to "env-secret",
            "SPOTIFY_BUTLER_REDIRECT_URI" to "https://butler.example/callback",
        )
}
