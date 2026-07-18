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
    val apiClient = SpotifyApiClient()
    val store = SpotifyStore.open(config.databasePath)
    Runtime.getRuntime().addShutdownHook(Thread(store::close))
    ButlerHttpServer(
        authClient = SpotifyAuthClient(secrets),
        apiClient = apiClient,
        cacheService = SpotifyCacheService(apiClient, store),
    ).start()
}
