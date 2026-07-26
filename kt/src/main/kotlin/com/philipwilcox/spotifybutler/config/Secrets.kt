package com.philipwilcox.spotifybutler.config

import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

data class Secrets(
    val clientId: String,
    val clientSecret: String,
    val redirectUri: String,
    val allowedSpotifyUserId: String? = null,
) {
    companion object {
        private const val SECRETS_FILE_ENV = "SPOTIFY_BUTLER_SECRETS_FILE"
        private const val CLIENT_ID_ENV = "SPOTIFY_BUTLER_CLIENT_ID"
        private const val CLIENT_SECRET_ENV = "SPOTIFY_BUTLER_CLIENT_SECRET"
        private const val REDIRECT_URI_ENV = "SPOTIFY_BUTLER_REDIRECT_URI"

        fun load(environment: Map<String, String> = System.getenv()): Secrets {
            val properties = loadProperties(environment)
            return Secrets(
                clientId = configuredRequired(CLIENT_ID_ENV, "spotify.clientId", environment, properties),
                clientSecret = configuredRequired(CLIENT_SECRET_ENV, "spotify.clientSecret", environment, properties),
                redirectUri = configuredRequired(REDIRECT_URI_ENV, "spotify.redirectUri", environment, properties),
                allowedSpotifyUserId = properties.getProperty("spotify.allowedUserId")?.trim()?.ifEmpty { null },
            )
        }

        private fun loadProperties(environment: Map<String, String>): Properties {
            val configuredPath = environment[SECRETS_FILE_ENV]?.trim()?.takeIf(String::isNotEmpty)?.let(Path::of)
            val secretsFile = configuredPath ?: defaultSecretsPath().takeIf(Files::isRegularFile)
            require(configuredPath == null || Files.isRegularFile(configuredPath)) {
                "Spotify secrets file not found at $configuredPath. Set the secret environment variables or " +
                    "${SECRETS_FILE_ENV}."
            }
            return Properties().also { properties ->
                secretsFile?.let { path -> Files.newInputStream(path).use(properties::load) }
            }
        }

        private fun configuredRequired(
            environmentKey: String,
            propertyKey: String,
            environment: Map<String, String>,
            properties: Properties,
        ): String =
            environment[environmentKey]?.trim()?.takeIf(String::isNotEmpty)
                ?: properties.getProperty(propertyKey)?.trim()?.takeIf(String::isNotEmpty)
                ?: error("Required $environmentKey or property $propertyKey is missing")

        private fun defaultSecretsPath(): Path {
            val repositoryRelative = Path.of("kt", "secrets.properties")
            return if (Files.isRegularFile(repositoryRelative)) repositoryRelative else Path.of("secrets.properties")
        }
    }
}
