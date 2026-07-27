package com.philipwilcox.spotifybutler.config

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ServiceConfigTest {
    @Test
    fun `retry configuration uses safe defaults`() {
        withConfigFile("") { file ->
            val config = ServiceConfig.load(mapOf("SPOTIFY_BUTLER_CONFIG_FILE" to file.toString()))

            assertEquals(SpotifyRetryConfig(4, 1, 2.0), config.spotifyRetryConfig)
        }
    }

    @Test
    fun `environment retry settings override properties`() {
        withConfigFile(
            """
            spotify.spotifyRetryMaxRetries=2
            spotify.spotifyRetryInitialDelaySeconds=3
            spotify.spotifyRetryBackoffMultiplier=1.5
            """.trimIndent(),
        ) { file ->
            val config =
                ServiceConfig.load(
                    mapOf(
                        "SPOTIFY_BUTLER_CONFIG_FILE" to file.toString(),
                        "SPOTIFY_BUTLER_SPOTIFY_RETRY_MAX_RETRIES" to "7",
                    ),
                )

            assertEquals(SpotifyRetryConfig(7, 3, 1.5), config.spotifyRetryConfig)
        }
    }

    @Test
    fun `invalid retry settings fail with the setting name`() {
        withConfigFile("") { file ->
            val exception =
                assertFailsWith<IllegalArgumentException> {
                    ServiceConfig.load(
                        mapOf(
                            "SPOTIFY_BUTLER_CONFIG_FILE" to file.toString(),
                            "SPOTIFY_BUTLER_SPOTIFY_RETRY_MAX_RETRIES" to "11",
                        ),
                    )
                }

            assertEquals(true, exception.message?.contains("SPOTIFY_BUTLER_SPOTIFY_RETRY_MAX_RETRIES"))
        }
    }

    private fun withConfigFile(
        contents: String,
        block: (java.nio.file.Path) -> Unit,
    ) {
        val file = Files.createTempFile("spotify-butler-config", ".properties")
        try {
            Files.writeString(file, contents)
            block(file)
        } finally {
            Files.deleteIfExists(file)
        }
    }
}
