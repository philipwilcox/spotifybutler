package com.philipwilcox.spotifybutler

import com.philipwilcox.spotifybutler.config.Secrets
import com.philipwilcox.spotifybutler.http.ButlerHttpServer
import com.philipwilcox.spotifybutler.spotify.SpotifyApiClient
import com.philipwilcox.spotifybutler.spotify.SpotifyAuthClient

fun main() {
    val secrets = Secrets.load()
    ButlerHttpServer(SpotifyAuthClient(secrets), SpotifyApiClient()).start()
}
