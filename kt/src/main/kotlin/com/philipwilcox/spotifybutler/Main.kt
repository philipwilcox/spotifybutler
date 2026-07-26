package com.philipwilcox.spotifybutler

import com.philipwilcox.spotifybutler.config.Secrets
import com.philipwilcox.spotifybutler.config.ServiceConfig
import com.philipwilcox.spotifybutler.db.SpotifyStore
import com.philipwilcox.spotifybutler.http.ButlerHttpServer
import com.philipwilcox.spotifybutler.service.SpotifyCacheService
import com.philipwilcox.spotifybutler.spotify.SpotifyApiClient
import com.philipwilcox.spotifybutler.spotify.SpotifyAuthClient
import io.github.oshai.kotlinlogging.KotlinLogging

fun main() {
    val logger = KotlinLogging.logger {}
    val secrets = Secrets.load()
    val config = ServiceConfig.load()
    val captureLog = System.getenv("SPOTIFY_BUTLER_CAPTURE_LOG") ?: "disabled"
    val captureRunId = System.getenv("SPOTIFY_BUTLER_CAPTURE_RUN_ID") ?: "generated-by-client"
    val databasePath = config.databasePath.toAbsolutePath().normalize()
    logger.info { "Spotify startup paths: database=$databasePath captureLog=$captureLog captureRunId=$captureRunId" }
    val apiClient = SpotifyApiClient()
    val store = SpotifyStore.open(config.databasePath, refreshTokenProtectionKey = secrets.clientSecret)
    Runtime.getRuntime().addShutdownHook(Thread(store::close))
    ButlerHttpServer(
        authClient = SpotifyAuthClient(secrets),
        apiClient = apiClient,
        cacheService = SpotifyCacheService(apiClient, store),
        store = store,
        frontendDirectory = config.frontendDirectory,
        allowedSpotifyUserId = secrets.allowedSpotifyUserId,
        trustedOrigins = config.trustedOrigins,
        secureCookies = config.secureCookies,
        callbackHttpsRequired = config.callbackHttpsRequired,
        trustedHosts = config.trustedHosts,
        trustedProxyAddresses = config.trustedProxyAddresses,
    ).start()
}
