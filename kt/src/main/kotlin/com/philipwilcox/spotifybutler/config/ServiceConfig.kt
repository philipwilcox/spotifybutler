package com.philipwilcox.spotifybutler.config

import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

data class ServiceConfig(
    val databasePath: Path,
    val trustedOrigins: Set<String>,
    val secureCookies: Boolean,
    val callbackHttpsRequired: Boolean,
    val trustedHosts: Set<String>,
    val trustedProxyAddresses: Set<String>,
) {
    companion object {
        private const val CONFIG_FILE_ENV = "SPOTIFY_BUTLER_CONFIG_FILE"
        private const val DATABASE_PATH_ENV = "SPOTIFY_BUTLER_DATABASE_PATH"
        private const val DATABASE_PATH_PROPERTY = "spotify.databasePath"
        private const val TRUSTED_ORIGINS_ENV = "SPOTIFY_BUTLER_TRUSTED_ORIGINS"
        private const val TRUSTED_ORIGINS_PROPERTY = "spotify.trustedOrigins"
        private const val SECURE_COOKIES_ENV = "SPOTIFY_BUTLER_SECURE_COOKIES"
        private const val SECURE_COOKIES_PROPERTY = "spotify.secureCookies"
        private const val CALLBACK_HTTPS_ENV = "SPOTIFY_BUTLER_REQUIRE_HTTPS_CALLBACK"
        private const val CALLBACK_HTTPS_PROPERTY = "spotify.requireHttpsCallback"
        private const val TRUSTED_HOSTS_ENV = "SPOTIFY_BUTLER_TRUSTED_HOSTS"
        private const val TRUSTED_HOSTS_PROPERTY = "spotify.trustedHosts"
        private const val TRUSTED_PROXIES_ENV = "SPOTIFY_BUTLER_TRUSTED_PROXIES"
        private const val TRUSTED_PROXIES_PROPERTY = "spotify.trustedProxies"

        fun load(): ServiceConfig {
            val configFile = configuredFile()
            val properties = configFile?.let(::loadProperties) ?: Properties()
            val configuredPath =
                System.getenv(DATABASE_PATH_ENV)?.trim()?.takeIf(String::isNotEmpty)
                    ?: properties.getProperty(DATABASE_PATH_PROPERTY)?.trim()?.takeIf(String::isNotEmpty)
            return ServiceConfig(
                databasePath =
                    configuredPath?.let { path -> resolveConfiguredPath(path, configFile) } ?: defaultDatabasePath(),
                trustedOrigins = trustedOrigins(properties),
                secureCookies = configuredBoolean(SECURE_COOKIES_ENV, SECURE_COOKIES_PROPERTY, properties),
                callbackHttpsRequired = configuredBoolean(CALLBACK_HTTPS_ENV, CALLBACK_HTTPS_PROPERTY, properties),
                trustedHosts = configuredSet(TRUSTED_HOSTS_ENV, TRUSTED_HOSTS_PROPERTY, properties, DEFAULT_HOSTS),
                trustedProxyAddresses = configuredSet(TRUSTED_PROXIES_ENV, TRUSTED_PROXIES_PROPERTY, properties),
            )
        }

        private fun trustedOrigins(properties: Properties): Set<String> {
            val configured =
                System.getenv(TRUSTED_ORIGINS_ENV)?.trim()
                    ?: properties.getProperty(TRUSTED_ORIGINS_PROPERTY)?.trim()
                    ?: "http://127.0.0.1:8888,http://localhost:8888"
            return configured.split(',').map(String::trim).filter(String::isNotEmpty).toSet().also {
                require(it.isNotEmpty()) { "$TRUSTED_ORIGINS_ENV must contain at least one Origin" }
                require(it.all { origin -> origin.startsWith("http://") || origin.startsWith("https://") }) {
                    "$TRUSTED_ORIGINS_ENV contains an invalid Origin"
                }
            }
        }

        private fun configuredBoolean(
            environment: String,
            property: String,
            properties: Properties,
        ): Boolean =
            (System.getenv(environment)?.trim() ?: properties.getProperty(property)?.trim())
                ?.toBooleanStrictOrNull()
                ?: false

        private fun configuredSet(
            environment: String,
            property: String,
            properties: Properties,
            default: Set<String> = emptySet(),
        ): Set<String> {
            val configured = System.getenv(environment)?.trim() ?: properties.getProperty(property)?.trim()
            return (configured ?: default.joinToString(","))
                .split(',')
                .map(String::trim)
                .filter(String::isNotEmpty)
                .toSet()
                .also {
                    if (default.isNotEmpty()) {
                        require(
                            it.isNotEmpty(),
                        ) { "$environment must contain at least one value" }
                    }
                }
        }

        private fun configuredFile(): Path? {
            val explicitPath =
                System
                    .getenv(CONFIG_FILE_ENV)
                    ?.trim()
                    ?.takeIf(String::isNotEmpty)
                    ?.let(Path::of)
            if (explicitPath != null) {
                require(Files.isRegularFile(explicitPath)) {
                    "Spotify service configuration file not found at $explicitPath. Set $CONFIG_FILE_ENV to a file."
                }
                return explicitPath
            }

            return listOf(Path.of("kt", "application.properties"), Path.of("application.properties"))
                .firstOrNull(Files::isRegularFile)
        }

        private fun loadProperties(path: Path): Properties =
            Properties().also { properties -> Files.newInputStream(path).use(properties::load) }

        private fun resolveConfiguredPath(
            configuredPath: String,
            configFile: Path?,
        ): Path {
            val path = Path.of(configuredPath)
            val configDirectory = configFile?.toAbsolutePath()?.parent
            return if (path.isAbsolute || configDirectory == null) path else configDirectory.resolve(path)
        }

        private fun defaultDatabasePath(): Path =
            if (Files.isDirectory(Path.of("kt"))) Path.of("kt", "spotify.db") else Path.of("spotify.db")

        private val DEFAULT_HOSTS = setOf("127.0.0.1:8888", "localhost:8888")
    }
}
