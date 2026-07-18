package com.philipwilcox.spotifybutler.config

import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

data class ServiceConfig(
    val databasePath: Path,
) {
    companion object {
        private const val CONFIG_FILE_ENV = "SPOTIFY_BUTLER_CONFIG_FILE"
        private const val DATABASE_PATH_ENV = "SPOTIFY_BUTLER_DATABASE_PATH"
        private const val DATABASE_PATH_PROPERTY = "spotify.databasePath"

        fun load(): ServiceConfig {
            val configFile = configuredFile()
            val properties = configFile?.let(::loadProperties) ?: Properties()
            val configuredPath =
                System.getenv(DATABASE_PATH_ENV)?.trim()?.takeIf(String::isNotEmpty)
                    ?: properties.getProperty(DATABASE_PATH_PROPERTY)?.trim()?.takeIf(String::isNotEmpty)
            return ServiceConfig(
                databasePath =
                    configuredPath?.let { path -> resolveConfiguredPath(path, configFile) } ?: defaultDatabasePath(),
            )
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
    }
}
