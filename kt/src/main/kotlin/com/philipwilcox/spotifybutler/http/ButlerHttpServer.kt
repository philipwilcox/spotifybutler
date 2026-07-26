// The embedded JDK server is intentionally a small transport adapter around ApiApplication.
@file:Suppress("TooManyFunctions", "MagicNumber")

package com.philipwilcox.spotifybutler.http

import com.philipwilcox.spotifybutler.db.SpotifyStore
import com.philipwilcox.spotifybutler.service.SpotifyCacheService
import com.philipwilcox.spotifybutler.spotify.SpotifyApiClient
import com.philipwilcox.spotifybutler.spotify.SpotifyAuthClient
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.Executors

class ButlerHttpServer(
    private val authClient: SpotifyAuthClient,
    private val apiClient: SpotifyApiClient,
    private val cacheService: SpotifyCacheService,
    private val store: SpotifyStore,
    private val host: String = "127.0.0.1",
    private val port: Int = 8888,
    private val allowedSpotifyUserId: String? = null,
    private val trustedOrigins: Set<String> = emptySet(),
    private val secureCookies: Boolean = false,
    private val callbackHttpsRequired: Boolean = false,
    private val trustedHosts: Set<String> = setOf("127.0.0.1:8888", "localhost:8888"),
    private val trustedProxyAddresses: Set<String> = emptySet(),
) {
    private val logger = KotlinLogging.logger {}
    private val sessionStore = SessionStore()
    private val apiApplication =
        ApiApplication(
            cacheService = cacheService,
            store = store,
            sessionStore = sessionStore,
            apiClient = apiClient,
            trustedOrigins = trustedOrigins,
            authClient = authClient,
            allowedSpotifyUserId = allowedSpotifyUserId,
            secureCookies = secureCookies,
        )

    fun start() {
        val server = HttpServer.create(InetSocketAddress(host, port), 0)
        server.createContext("/") { exchange -> handle(exchange) }
        server.executor = Executors.newCachedThreadPool()
        server.start()
        logger.info { "Spotify Butler listening at http://$host:$port/" }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun handle(exchange: HttpExchange) {
        val path = exchange.requestURI.path
        val requestId = exchange.requestHeaders.getFirst("X-Request-Id")?.takeIf(::validRequestId) ?: newRequestId()
        exchange.responseHeaders.set("X-Request-Id", requestId)
        val startedAt = System.nanoTime()
        var status = HttpURLConnection.HTTP_INTERNAL_ERROR
        try {
            validateHost(exchange)
            if (path == "/callback" && callbackHttpsRequired && effectiveScheme(exchange) != "https") {
                throw RequestFailure(HttpURLConnection.HTTP_BAD_REQUEST, "HTTPS is required for the OAuth callback")
            }
            status =
                when {
                    path == "/health" -> health(exchange)
                    path == "/start" -> start(exchange)
                    path == "/callback" -> callback(exchange)
                    path.startsWith("/api/v1/") -> api(exchange)
                    else ->
                        json(
                            exchange,
                            HttpURLConnection.HTTP_NOT_FOUND,
                            errorJson("not_found", "Route not found", requestId),
                        )
                }
        } catch (failure: RequestFailure) {
            status = json(exchange, failure.status, errorJson("request_rejected", failure.message, requestId))
        } catch (exception: Exception) {
            logger.error(exception) { "Request failed: method=${exchange.requestMethod} path=$path" }
            if (!exchange.responseHeaders.containsKey("Content-Type")) {
                status =
                    json(
                        exchange,
                        HttpURLConnection.HTTP_INTERNAL_ERROR,
                        errorJson("internal_error", "Request failed", requestId),
                    )
            }
        } finally {
            val durationMs = (System.nanoTime() - startedAt) / 1_000_000
            logger.info {
                "Request finished: method=${exchange.requestMethod} path=$path status=$status durationMs=$durationMs"
            }
        }
    }

    private fun health(exchange: HttpExchange): Int {
        requireGet(exchange)
        return json(
            exchange,
            HttpURLConnection.HTTP_OK,
            buildJsonObject { put("status", JsonPrimitive("ready")) }.toString(),
        )
    }

    private fun start(exchange: HttpExchange): Int {
        requireGet(exchange)
        val query = exchange.requestURI.queryParameters()
        val returnTo = query["returnTo"].orEmpty().ifBlank { "/" }
        requireSafeReturnTo(returnTo)
        val authorization = authClient.beginAuthorization(returnTo)
        exchange.responseHeaders.add("Set-Cookie", stateCookie(authorization.state))
        exchange.responseHeaders.add("Location", authorization.location.toASCIIString())
        exchange.sendResponseHeaders(HttpURLConnection.HTTP_MOVED_TEMP, -1)
        exchange.close()
        return HttpURLConnection.HTTP_MOVED_TEMP
    }

    private fun callback(exchange: HttpExchange): Int {
        requireGet(exchange)
        val parameters = exchange.requestURI.queryParameters()
        val authorization =
            authClient.consumeCallbackAuthorization(
                parameters["state"],
                exchange.requestCookies()[SpotifyAuthClient.STATE_COOKIE],
            )
                ?: return json(
                    exchange,
                    HttpURLConnection.HTTP_BAD_REQUEST,
                    errorJson("invalid_state", "Invalid or expired authorization state", requestId(exchange)),
                )
        val code =
            parameters["code"]
                ?: return json(
                    exchange,
                    HttpURLConnection.HTTP_BAD_REQUEST,
                    errorJson(
                        "missing_code",
                        "Spotify callback did not include an authorization code",
                        requestId(exchange),
                    ),
                )
        val token = authClient.exchangeAuthorizationCode(code, authorization.codeVerifier)
        val user = apiClient.getCurrentUser(token.accessToken)
        if (allowedSpotifyUserId != null && user.id != allowedSpotifyUserId) {
            return json(
                exchange,
                HttpURLConnection.HTTP_FORBIDDEN,
                errorJson("user_not_allowed", "This Spotify account is not allowed", requestId(exchange)),
            )
        }
        val session = sessionStore.create(user.id, token.accessToken, token.refreshToken)
        exchange.responseHeaders.add("Set-Cookie", clearStateCookie())
        exchange.responseHeaders.add("Set-Cookie", sessionCookie(session.id))
        exchange.responseHeaders.add("Location", authorization.returnTo)
        exchange.sendResponseHeaders(HttpURLConnection.HTTP_SEE_OTHER, -1)
        exchange.close()
        return HttpURLConnection.HTTP_SEE_OTHER
    }

    private fun api(exchange: HttpExchange): Int {
        val body = readBoundedBody(exchange)
        val request =
            ApiRequest(
                method = exchange.requestMethod,
                path = exchange.requestURI.path,
                query = exchange.requestURI.queryParameters(),
                headers = exchange.requestHeaders.entries.associate { it.key to it.value.joinToString(",") },
                body = body,
            )
        val response = apiApplication.handle(request)
        response.headers.forEach { (name, value) -> exchange.responseHeaders.set(name, value) }
        return json(exchange, response.status, response.body)
    }

    private fun readBoundedBody(exchange: HttpExchange): String? {
        val length = exchange.requestHeaders.getFirst("Content-Length")?.toLongOrNull()
        if (length != null && length > MAX_REQUEST_BYTES) {
            throw RequestFailure(413, "Request body is too large")
        }
        val bytes = exchange.requestBody.use { it.readNBytes(MAX_REQUEST_BYTES + 1) }
        if (bytes.size > MAX_REQUEST_BYTES) throw RequestFailure(413, "Request body is too large")
        return bytes.takeIf { it.isNotEmpty() }?.toString(StandardCharsets.UTF_8)
    }

    private fun requireGet(exchange: HttpExchange) {
        if (exchange.requestMethod !=
            "GET"
        ) {
            throw RequestFailure(HttpURLConnection.HTTP_BAD_METHOD, "Method not allowed")
        }
    }

    private fun json(
        exchange: HttpExchange,
        status: Int,
        body: String,
    ): Int {
        exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
        return status
    }

    private fun errorJson(
        code: String,
        message: String,
        requestId: String,
    ): String =
        buildJsonObject {
            put("code", JsonPrimitive(code))
            put("message", JsonPrimitive(message))
            put("requestId", JsonPrimitive(requestId))
            put("details", buildJsonObject {})
        }.toString()

    private fun stateCookie(state: String): String = cookie(SpotifyAuthClient.STATE_COOKIE, state, 600, httpOnly = true)

    private fun clearStateCookie(): String = cookie(SpotifyAuthClient.STATE_COOKIE, "", 0, httpOnly = true)

    private fun sessionCookie(sessionId: String): String = cookie("butler_session", sessionId, 43_200, httpOnly = true)

    private fun cookie(
        name: String,
        value: String,
        maxAge: Int,
        httpOnly: Boolean,
    ): String =
        buildString {
            append("$name=$value; Path=/; Max-Age=$maxAge; SameSite=Lax")
            if (httpOnly) append("; HttpOnly")
            if (secureCookies) append("; Secure")
        }

    private fun requireSafeReturnTo(returnTo: String) {
        require(returnTo.startsWith("/") && !returnTo.startsWith("//") && !returnTo.contains('\\')) {
            "returnTo must be a relative path"
        }
    }

    private fun URI.queryParameters(): Map<String, String> =
        rawQuery.orEmpty().split('&').filter(String::isNotBlank).associate { part ->
            val pieces = part.split('=', limit = 2)
            URLDecoder.decode(pieces[0], StandardCharsets.UTF_8) to
                URLDecoder.decode(pieces.getOrElse(1) { "" }, StandardCharsets.UTF_8)
        }

    private fun HttpExchange.requestCookies(): Map<String, String> =
        requestHeaders
            .getFirst("Cookie")
            .orEmpty()
            .split(';')
            .mapNotNull { cookie ->
                val pieces = cookie.trim().split('=', limit = 2)
                pieces.firstOrNull()?.takeIf(String::isNotEmpty)?.let { it to pieces.getOrElse(1) { "" } }
            }.toMap()

    private data class RequestFailure(
        val status: Int,
        override val message: String,
    ) : RuntimeException(message)

    companion object {
        private const val MAX_REQUEST_BYTES = 1_048_576
    }

    private fun newRequestId(): String = "req-${UUID.randomUUID()}"

    private fun validRequestId(value: String): Boolean =
        value.length in 1..100 && value.all { it.isLetterOrDigit() || it in "-_" }

    private fun requestId(exchange: HttpExchange): String =
        exchange.responseHeaders.getFirst("X-Request-Id") ?: newRequestId()

    private fun validateHost(exchange: HttpExchange) {
        val host = effectiveHost(exchange)
        if (host == null || host !in trustedHosts.map(String::lowercase).toSet()) {
            throw RequestFailure(HttpURLConnection.HTTP_BAD_REQUEST, "The request Host is not trusted")
        }
    }

    private fun effectiveHost(exchange: HttpExchange): String? {
        val directHost =
            exchange.requestHeaders
                .getFirst("Host")
                ?.trim()
                ?.lowercase()
        if (!isTrustedProxy(exchange)) return directHost
        return exchange.requestHeaders
            .getFirst("X-Forwarded-Host")
            ?.split(',')
            ?.firstOrNull()
            ?.trim()
            ?.lowercase()
            ?.takeIf(String::isNotEmpty)
            ?: directHost
    }

    private fun effectiveScheme(exchange: HttpExchange): String {
        if (!isTrustedProxy(exchange)) return "http"
        return exchange.requestHeaders
            .getFirst("X-Forwarded-Proto")
            ?.split(',')
            ?.firstOrNull()
            ?.trim()
            ?.lowercase()
            ?.takeIf { it == "http" || it == "https" }
            ?: forwardedScheme(exchange)
            ?: "http"
    }

    private fun forwardedScheme(exchange: HttpExchange): String? =
        exchange.requestHeaders
            .getFirst("Forwarded")
            ?.split(',')
            ?.firstOrNull()
            ?.split(';')
            ?.firstOrNull { it.trim().startsWith("proto=", ignoreCase = true) }
            ?.substringAfter('=')
            ?.trim()
            ?.trim('"')
            ?.lowercase()
            ?.takeIf { it == "http" || it == "https" }

    private fun isTrustedProxy(exchange: HttpExchange): Boolean =
        exchange.remoteAddress.address.hostAddress in trustedProxyAddresses
}
