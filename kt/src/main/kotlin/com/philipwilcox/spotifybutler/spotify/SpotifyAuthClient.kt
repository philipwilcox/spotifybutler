package com.philipwilcox.spotifybutler.spotify

import com.philipwilcox.spotifybutler.config.Secrets
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
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
        val returnTo: String,
    )

    data class TokenResponse(
        val accessToken: String,
        val expiresInSeconds: Long,
        val refreshToken: String?,
    )

    data class CallbackAuthorization(
        val codeVerifier: String,
        val returnTo: String,
    )

    private data class PendingAuthorization(
        val expiresAt: Instant,
        val codeVerifier: String,
        val returnTo: String,
    )

    private val pendingAuthorizations = ConcurrentHashMap<String, PendingAuthorization>()
    private val random = SecureRandom()

    fun beginAuthorization(returnTo: String = "/"): AuthorizationRequest {
        removeExpiredAuthorizations()
        val state = newState()
        val codeVerifier = newCodeVerifier()
        pendingAuthorizations[state] =
            PendingAuthorization(clock.instant().plus(STATE_LIFETIME), codeVerifier, returnTo)
        val parameters =
            mapOf(
                "response_type" to "code",
                "client_id" to secrets.clientId,
                "redirect_uri" to secrets.redirectUri,
                "scope" to SCOPES.joinToString(" "),
                "state" to state,
                "code_challenge_method" to "S256",
                "code_challenge" to codeChallenge(codeVerifier),
            )
        return AuthorizationRequest(
            URI("https://accounts.spotify.com/authorize?${formEncode(parameters)}"),
            state,
            returnTo,
        )
    }

    fun consumeCallbackAuthorization(
        state: String?,
        cookieState: String?,
    ): CallbackAuthorization? {
        if (state.isNullOrBlank() || state != cookieState) return null
        val pending = pendingAuthorizations.remove(state) ?: return null
        return pending
            .takeIf { it.expiresAt.isAfter(clock.instant()) }
            ?.let { CallbackAuthorization(it.codeVerifier, it.returnTo) }
    }

    fun exchangeAuthorizationCode(
        code: String,
        codeVerifier: String? = null,
    ): TokenResponse {
        val credentials =
            Base64.getEncoder().encodeToString(
                "${secrets.clientId}:${secrets.clientSecret}".toByteArray(),
            )
        val form =
            formEncode(
                buildMap {
                    put("code", code)
                    put("redirect_uri", secrets.redirectUri)
                    put("grant_type", "authorization_code")
                    codeVerifier?.let { put("code_verifier", it) }
                },
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
            "Spotify token exchange failed with HTTP ${response.statusCode()}"
        }
        return TokenResponse(
            accessToken = requiredJsonString(response.body(), "access_token"),
            expiresInSeconds = requiredJsonNumber(response.body(), "expires_in"),
            refreshToken = optionalJsonString(response.body(), "refresh_token"),
        )
    }

    fun refreshAccessToken(refreshToken: String): TokenResponse {
        val credentials =
            Base64.getEncoder().encodeToString(
                "${secrets.clientId}:${secrets.clientSecret}".toByteArray(),
            )
        val form = formEncode(mapOf("grant_type" to "refresh_token", "refresh_token" to refreshToken))
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
            "Spotify token refresh failed with HTTP ${response.statusCode()}"
        }
        return TokenResponse(
            accessToken = requiredJsonString(response.body(), "access_token"),
            expiresInSeconds = requiredJsonNumber(response.body(), "expires_in"),
            refreshToken = optionalJsonString(response.body(), "refresh_token") ?: refreshToken,
        )
    }

    private fun newState(): String =
        ByteArray(STATE_NONCE_BYTES).also(random::nextBytes).let { nonce ->
            val encodedNonce = Base64.getUrlEncoder().withoutPadding().encodeToString(nonce)
            val stateJson = "{\"nonce\":\"$encodedNonce\"}"
            Base64.getUrlEncoder().withoutPadding().encodeToString(stateJson.toByteArray(StandardCharsets.UTF_8))
        }

    private fun newCodeVerifier(): String =
        ByteArray(CODE_VERIFIER_BYTES).also(random::nextBytes).let {
            Base64.getUrlEncoder().withoutPadding().encodeToString(it)
        }

    private fun codeChallenge(codeVerifier: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(codeVerifier.toByteArray()),
        )

    private fun removeExpiredAuthorizations() {
        val now = clock.instant()
        pendingAuthorizations.entries.removeIf { !it.value.expiresAt.isAfter(now) }
    }

    companion object {
        const val STATE_COOKIE = "spotify_auth_state"
        private const val STATE_NONCE_BYTES = 32
        private const val CODE_VERIFIER_BYTES = 32
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
