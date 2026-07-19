package com.philipwilcox.spotifybutler

import com.philipwilcox.spotifybutler.config.Secrets
import com.philipwilcox.spotifybutler.config.ServiceConfig
import com.philipwilcox.spotifybutler.db.SpotifyStore
import com.philipwilcox.spotifybutler.http.ButlerHttpServer
import com.philipwilcox.spotifybutler.service.SpotifyCacheService
import com.philipwilcox.spotifybutler.spotify.SpotifyApiClient
import com.philipwilcox.spotifybutler.spotify.SpotifyAuthClient

fun main() {
    val secrets = Secrets.load()
    val config = ServiceConfig.load()
    val captureLog = System.getenv("SPOTIFY_BUTLER_CAPTURE_LOG") ?: "disabled"
    val captureRunId = System.getenv("SPOTIFY_BUTLER_CAPTURE_RUN_ID") ?: "generated-by-client"
    val databasePath = config.databasePath.toAbsolutePath().normalize()
    println("Spotify startup paths: database=$databasePath captureLog=$captureLog captureRunId=$captureRunId")
    val apiClient = SpotifyApiClient()
    val store = SpotifyStore.open(config.databasePath)
    Runtime.getRuntime().addShutdownHook(Thread(store::close))
    ButlerHttpServer(
        authClient = SpotifyAuthClient(secrets),
        apiClient = apiClient,
        cacheService = SpotifyCacheService(apiClient, store),
        store = store,
    ).start()
}
