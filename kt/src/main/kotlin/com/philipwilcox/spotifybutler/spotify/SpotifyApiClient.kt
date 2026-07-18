package com.philipwilcox.spotifybutler.spotify

import java.net.HttpURLConnection
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class SpotifyApiClient(
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
) {
    data class CurrentUser(
        val displayName: String?,
        val id: String,
    )

    fun getCurrentUser(accessToken: String): CurrentUser {
        val request =
            HttpRequest
                .newBuilder(URI("https://api.spotify.com/v1/me"))
                .header("Authorization", "Bearer $accessToken")
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        require(response.statusCode() in HttpURLConnection.HTTP_OK until HttpURLConnection.HTTP_MULT_CHOICE) {
            "Spotify current-user request failed with HTTP ${response.statusCode()}: ${response.body()}"
        }
        val id = jsonString(response.body(), "id") ?: error("Spotify current-user response did not contain id")
        return CurrentUser(jsonString(response.body(), "display_name"), id)
    }

    private fun jsonString(
        json: String,
        key: String,
    ): String? =
        Regex(
            "\\\"${Regex.escape(key)}\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"])*)\\\"",
        ).find(json)?.groupValues?.get(1)?.replace("\\\"", "\"")?.replace("\\\\", "\\")
}
