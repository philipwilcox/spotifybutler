package com.philipwilcox.spotifybutler.config

import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

data class ServiceConfig(
    val databasePath: Path,
    val frontendDirectory: Path,
    val bindHost: String,
    val trustedOrigins: Set<String>,
    val secureCookies: Boolean,
    val callbackHttpsRequired: Boolean,
    val trustedHosts: Set<String>,
    val trustedProxyAddresses: Set<String>,
    val trustedProxyToken: String?,
    val spotifyRetryConfig: SpotifyRetryConfig,
) {
    @Suppress("TooManyFunctions")
    companion object {
        private const val CONFIG_FILE_ENV = "SPOTIFY_BUTLER_CONFIG_FILE"
        private const val DATABASE_PATH_ENV = "SPOTIFY_BUTLER_DATABASE_PATH"
        private const val DATABASE_PATH_PROPERTY = "spotify.databasePath"
        private const val FRONTEND_DIRECTORY_ENV = "SPOTIFY_BUTLER_FRONTEND_DIRECTORY"
        private const val FRONTEND_DIRECTORY_PROPERTY = "spotify.frontendDirectory"
        private const val BIND_HOST_ENV = "SPOTIFY_BUTLER_HOST"
        private const val BIND_HOST_PROPERTY = "spotify.host"
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
        private const val TRUSTED_PROXY_TOKEN_ENV = "SPOTIFY_BUTLER_TRUSTED_PROXY_TOKEN"
        private const val TRUSTED_PROXY_TOKEN_PROPERTY = "spotify.trustedProxyToken"
        private const val SPOTIFY_RETRY_MAX_RETRIES_ENV = "SPOTIFY_BUTLER_SPOTIFY_RETRY_MAX_RETRIES"
        private const val SPOTIFY_RETRY_MAX_RETRIES_PROPERTY = "spotify.spotifyRetryMaxRetries"
        private const val SPOTIFY_RETRY_INITIAL_DELAY_ENV = "SPOTIFY_BUTLER_SPOTIFY_RETRY_INITIAL_DELAY_SECONDS"
        private const val SPOTIFY_RETRY_INITIAL_DELAY_PROPERTY = "spotify.spotifyRetryInitialDelaySeconds"
        private const val SPOTIFY_RETRY_MULTIPLIER_ENV = "SPOTIFY_BUTLER_SPOTIFY_RETRY_BACKOFF_MULTIPLIER"
        private const val SPOTIFY_RETRY_MULTIPLIER_PROPERTY = "spotify.spotifyRetryBackoffMultiplier"

        fun load(environment: Map<String, String> = System.getenv()): ServiceConfig {
            val configFile = configuredFile(environment)
            val properties = configFile?.let(::loadProperties) ?: Properties()
            val configuredPath =
                environment[DATABASE_PATH_ENV]?.trim()?.takeIf(String::isNotEmpty)
                    ?: properties.getProperty(DATABASE_PATH_PROPERTY)?.trim()?.takeIf(String::isNotEmpty)
            val configuredFrontendDirectory =
                environment[FRONTEND_DIRECTORY_ENV]?.trim()?.takeIf(String::isNotEmpty)
                    ?: properties.getProperty(FRONTEND_DIRECTORY_PROPERTY)?.trim()?.takeIf(String::isNotEmpty)
            return ServiceConfig(
                databasePath =
                    configuredPath?.let { path -> resolveConfiguredPath(path, configFile) } ?: defaultDatabasePath(),
                frontendDirectory =
                    configuredFrontendDirectory?.let { path -> resolveConfiguredPath(path, configFile) }
                        ?: defaultFrontendDirectory(),
                bindHost = configuredHost(properties, environment),
                trustedOrigins = trustedOrigins(properties, environment),
                secureCookies = configuredBoolean(SECURE_COOKIES_ENV, SECURE_COOKIES_PROPERTY, properties, environment),
                callbackHttpsRequired =
                    configuredBoolean(
                        CALLBACK_HTTPS_ENV,
                        CALLBACK_HTTPS_PROPERTY,
                        properties,
                        environment,
                    ),
                trustedHosts =
                    configuredSet(
                        TRUSTED_HOSTS_ENV,
                        TRUSTED_HOSTS_PROPERTY,
                        properties,
                        DEFAULT_HOSTS,
                        environment,
                    ),
                trustedProxyAddresses =
                    configuredSet(
                        TRUSTED_PROXIES_ENV,
                        TRUSTED_PROXIES_PROPERTY,
                        properties,
                        DEFAULT_TRUSTED_PROXIES,
                        environment,
                    ),
                trustedProxyToken =
                    configuredOptional(
                        TRUSTED_PROXY_TOKEN_ENV,
                        TRUSTED_PROXY_TOKEN_PROPERTY,
                        properties,
                        environment,
                    ),
                spotifyRetryConfig = spotifyRetryConfig(properties, environment),
            )
        }

        private fun configuredHost(
            properties: Properties,
            environment: Map<String, String>,
        ): String {
            val configured = environment[BIND_HOST_ENV] ?: properties.getProperty(BIND_HOST_PROPERTY)
            return configured?.trim()?.also { host ->
                require(host.isNotEmpty()) { "$BIND_HOST_ENV must not be blank" }
            } ?: "0.0.0.0"
        }

        private fun trustedOrigins(
            properties: Properties,
            environment: Map<String, String>,
        ): Set<String> {
            val configured =
                environment[TRUSTED_ORIGINS_ENV]?.trim()
                    ?: properties.getProperty(TRUSTED_ORIGINS_PROPERTY)?.trim()
                    ?: "http://127.0.0.1:8888,http://localhost:8888"
            return configured.split(',').map(String::trim).filter(String::isNotEmpty).toSet().also {
                require(it.isNotEmpty()) { "$TRUSTED_ORIGINS_ENV must contain at least one Origin" }
                require(it.all { origin -> origin.startsWith("http://") || origin.startsWith("https://") }) {
                    "$TRUSTED_ORIGINS_ENV contains an invalid Origin"
                }
            }
        }

        private fun spotifyRetryConfig(
            properties: Properties,
            environment: Map<String, String>,
        ): SpotifyRetryConfig =
            SpotifyRetryConfig(
                maxRetries =
                    configuredInt(
                        SPOTIFY_RETRY_MAX_RETRIES_ENV,
                        SPOTIFY_RETRY_MAX_RETRIES_PROPERTY,
                        properties,
                        environment,
                        default = SpotifyRetryConfig.DEFAULT_MAX_RETRIES,
                    ).also {
                        require(
                            it in 0..MAX_RETRIES_UPPER_BOUND,
                        ) {
                            "$SPOTIFY_RETRY_MAX_RETRIES_ENV must be an integer from 0 to $MAX_RETRIES_UPPER_BOUND"
                        }
                    },
                initialDelaySeconds =
                    configuredInt(
                        SPOTIFY_RETRY_INITIAL_DELAY_ENV,
                        SPOTIFY_RETRY_INITIAL_DELAY_PROPERTY,
                        properties,
                        environment,
                        default = SpotifyRetryConfig.DEFAULT_INITIAL_DELAY_SECONDS,
                    ).also { require(it > 0) { "$SPOTIFY_RETRY_INITIAL_DELAY_ENV must be a positive integer" } },
                backoffMultiplier =
                    configuredDouble(
                        SPOTIFY_RETRY_MULTIPLIER_ENV,
                        SPOTIFY_RETRY_MULTIPLIER_PROPERTY,
                        properties,
                        environment,
                        default = SpotifyRetryConfig.DEFAULT_BACKOFF_MULTIPLIER,
                    ).also {
                        require(it.isFinite() && it >= 1.0) {
                            "$SPOTIFY_RETRY_MULTIPLIER_ENV must be a finite number greater than or equal to 1.0"
                        }
                    },
            )

        private fun configuredInt(
            environmentName: String,
            property: String,
            properties: Properties,
            environment: Map<String, String>,
            default: Int,
        ): Int {
            val configured = environment[environmentName]?.trim() ?: properties.getProperty(property)?.trim()
            return configured?.toIntOrNull()
                ?: configured?.let { error("$environmentName must be a valid integer, but was '$it'") }
                ?: default
        }

        private fun configuredDouble(
            environmentName: String,
            property: String,
            properties: Properties,
            environment: Map<String, String>,
            default: Double,
        ): Double {
            val configured = environment[environmentName]?.trim() ?: properties.getProperty(property)?.trim()
            return configured?.toDoubleOrNull()
                ?: configured?.let { error("$environmentName must be a valid number, but was '$it'") }
                ?: default
        }

        private fun configuredBoolean(
            environmentName: String,
            property: String,
            properties: Properties,
            environmentValues: Map<String, String>,
        ): Boolean =
            (environmentValues[environmentName]?.trim() ?: properties.getProperty(property)?.trim())
                ?.toBooleanStrictOrNull()
                ?: false

        private fun configuredOptional(
            environmentName: String,
            property: String,
            properties: Properties,
            environmentValues: Map<String, String>,
        ): String? =
            (environmentValues[environmentName]?.trim() ?: properties.getProperty(property)?.trim())
                ?.takeIf(String::isNotEmpty)

        private fun configuredSet(
            environmentName: String,
            property: String,
            properties: Properties,
            default: Set<String> = emptySet(),
            environmentValues: Map<String, String>,
        ): Set<String> {
            val configured = environmentValues[environmentName]?.trim() ?: properties.getProperty(property)?.trim()
            return (configured ?: default.joinToString(","))
                .split(',')
                .map(String::trim)
                .filter(String::isNotEmpty)
                .toSet()
                .also {
                    if (default.isNotEmpty()) {
                        require(
                            it.isNotEmpty(),
                        ) { "$environmentName must contain at least one value" }
                    }
                }
        }

        private fun configuredFile(environment: Map<String, String>): Path? {
            val explicitPath =
                environment[CONFIG_FILE_ENV]
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

        private fun defaultFrontendDirectory(): Path =
            when {
                Files.isDirectory(Path.of("vue")) -> Path.of("vue", "dist")
                Files.isDirectory(Path.of("..", "vue")) -> Path.of("..", "vue", "dist")
                else -> Path.of("dist")
            }

        private val DEFAULT_HOSTS = setOf("127.0.0.1:8888", "localhost:8888")
        private val DEFAULT_TRUSTED_PROXIES = setOf("172.17.0.1")
        private const val MAX_RETRIES_UPPER_BOUND = 10
    }
}
