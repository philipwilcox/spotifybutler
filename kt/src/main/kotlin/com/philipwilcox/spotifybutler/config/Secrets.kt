package com.philipwilcox.spotifybutler.config

import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

data class Secrets(
    val clientId: String,
    val clientSecret: String,
    val redirectUri: String,
    val accessToken: String?,
    val allowedSpotifyUserId: String? = null,
) {
    companion object {
        private const val SECRETS_FILE_ENV = "SPOTIFY_BUTLER_SECRETS_FILE"

        fun load(): Secrets {
            val secretsFile =
                System.getenv(SECRETS_FILE_ENV)?.takeIf(String::isNotBlank)?.let(Path::of)
                    ?: defaultSecretsPath()
            require(Files.isRegularFile(secretsFile)) {
                "Spotify secrets file not found at $secretsFile. Create kt/secrets.properties from " +
                    "kt/secrets.properties.example or set $SECRETS_FILE_ENV."
            }
            val properties = Properties()
            Files.newInputStream(secretsFile).use(properties::load)
            return Secrets(
                clientId = properties.required("spotify.clientId"),
                clientSecret = properties.required("spotify.clientSecret"),
                redirectUri = properties.required("spotify.redirectUri"),
                accessToken = properties.getProperty("spotify.accessToken")?.trim()?.ifEmpty { null },
                allowedSpotifyUserId = properties.getProperty("spotify.allowedUserId")?.trim()?.ifEmpty { null },
            )
        }

        private fun defaultSecretsPath(): Path {
            val repositoryRelative = Path.of("kt", "secrets.properties")
            return if (Files.isRegularFile(repositoryRelative)) repositoryRelative else Path.of("secrets.properties")
        }

        private fun Properties.required(key: String): String =
            getProperty(key)?.trim()?.takeIf(String::isNotEmpty)
                ?: error("Required property $key is missing from the Spotify secrets file")
    }
}
