package com.philipwilcox.spotifybutler.spotify

import com.philipwilcox.spotifybutler.config.Secrets
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

class SpotifyAuthClient(
    private val secrets: Secrets,
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
    private val clock: Clock = Clock.systemUTC(),
) {
    data class AuthorizationRequest(
        val location: URI,
        val state: String,
    )

    data class TokenResponse(
        val accessToken: String,
        val expiresInSeconds: Long,
        val refreshToken: String?,
    )

    private data class PendingAuthorization(
        val refresh: Boolean,
        val expiresAt: Instant,
    )

    private val pendingAuthorizations = ConcurrentHashMap<String, PendingAuthorization>()
    private val random = SecureRandom()

    fun beginAuthorization(refresh: Boolean): AuthorizationRequest {
        removeExpiredAuthorizations()
        val state = newState(refresh)
        pendingAuthorizations[state] = PendingAuthorization(refresh, clock.instant().plus(STATE_LIFETIME))
        val parameters =
            mapOf(
                "response_type" to "code",
                "client_id" to secrets.clientId,
                "redirect_uri" to secrets.redirectUri,
                "scope" to SCOPES.joinToString(" "),
                "state" to state,
            )
        return AuthorizationRequest(URI("https://accounts.spotify.com/authorize?${formEncode(parameters)}"), state)
    }

    fun validateCallback(
        state: String?,
        cookieState: String?,
    ): Boolean {
        if (state.isNullOrBlank() || state != cookieState) return false
        val pending = pendingAuthorizations.remove(state) ?: return false
        return pending.expiresAt.isAfter(clock.instant())
    }

    fun exchangeAuthorizationCode(code: String): TokenResponse {
        val credentials =
            Base64.getEncoder().encodeToString(
                "${secrets.clientId}:${secrets.clientSecret}".toByteArray(),
            )
        val form =
            formEncode(
                mapOf("code" to code, "redirect_uri" to secrets.redirectUri, "grant_type" to "authorization_code"),
            )
        val request =
            HttpRequest
                .newBuilder(URI("https://accounts.spotify.com/api/token"))
                .header("Authorization", "Basic $credentials")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .timeout(Duration.ofSeconds(20))
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        require(response.statusCode() in HttpURLConnection.HTTP_OK until HttpURLConnection.HTTP_MULT_CHOICE) {
            "Spotify token exchange failed with HTTP ${response.statusCode()}: ${response.body()}"
        }
        return TokenResponse(
            accessToken = requiredJsonString(response.body(), "access_token"),
            expiresInSeconds = requiredJsonNumber(response.body(), "expires_in"),
            refreshToken = optionalJsonString(response.body(), "refresh_token"),
        )
    }

    private fun newState(refresh: Boolean): String =
        ByteArray(STATE_NONCE_BYTES).also(random::nextBytes).let { nonce ->
            val encodedNonce = Base64.getUrlEncoder().withoutPadding().encodeToString(nonce)
            val stateJson = "{\"nonce\":\"$encodedNonce\",\"refresh\":$refresh}"
            Base64.getUrlEncoder().withoutPadding().encodeToString(stateJson.toByteArray(StandardCharsets.UTF_8))
        }

    private fun removeExpiredAuthorizations() {
        val now = clock.instant()
        pendingAuthorizations.entries.removeIf { !it.value.expiresAt.isAfter(now) }
    }

    companion object {
        const val STATE_COOKIE = "spotify_auth_state"
        private const val STATE_NONCE_BYTES = 32
        private val STATE_LIFETIME: Duration = Duration.ofMinutes(10)
        private val SCOPES =
            listOf(
                "user-read-private",
                "user-read-email",
                "user-top-read",
                "user-read-recently-played",
                "playlist-read-private",
                "user-library-modify",
                "user-library-read",
                "playlist-modify-private",
            )

        fun formEncode(parameters: Map<String, String>): String =
            parameters.entries.joinToString("&") { (key, value) ->
                "${URLEncoder.encode(key, StandardCharsets.UTF_8)}=${URLEncoder.encode(value, StandardCharsets.UTF_8)}"
            }

        private fun requiredJsonString(
            json: String,
            key: String,
        ): String =
            optionalJsonString(json, key)
                ?: error("Spotify response did not contain $key")

        private fun optionalJsonString(
            json: String,
            key: String,
        ): String? =
            Regex(
                "\\\"${Regex.escape(key)}\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"])*)\\\"",
            ).find(json)?.groupValues?.get(1)?.replace("\\\"", "\"")?.replace("\\\\", "\\")

        private fun requiredJsonNumber(
            json: String,
            key: String,
        ): Long =
            Regex(
                "\\\"${Regex.escape(key)}\\\"\\s*:\\s*(\\d+)",
            ).find(json)?.groupValues?.get(1)?.toLongOrNull() ?: error("Spotify response did not contain numeric $key")
    }
}
