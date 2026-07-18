package com.philipwilcox.spotifybutler.http

import com.philipwilcox.spotifybutler.spotify.SpotifyApiClient
import com.philipwilcox.spotifybutler.spotify.SpotifyAuthClient
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.oshai.kotlinlogging.KotlinLogging
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

class ButlerHttpServer(
    private val authClient: SpotifyAuthClient,
    private val apiClient: SpotifyApiClient,
    private val host: String = "127.0.0.1",
    private val port: Int = 8888,
) {
    private val logger = KotlinLogging.logger {}

    fun start() {
        val server = HttpServer.create(InetSocketAddress(host, port), 0)
        server.createContext("/") { exchange -> handle(exchange) }
        server.executor = Executors.newCachedThreadPool()
        server.start()
        logger.info { "Spotify Butler listening at http://$host:$port/" }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun handle(exchange: HttpExchange) {
        val method = exchange.requestMethod
        val path = exchange.requestURI.path
        val startedAt = System.nanoTime()
        var status: Int? = null
        logger.info { "Request started: method=$method path=$path" }
        try {
            status =
                when (path) {
                    "/start" -> handleStart(exchange)
                    "/callback" -> handleCallback(exchange)
                    "/hello" -> handleHello(exchange)
                    else -> exchange.respond(HttpURLConnection.HTTP_OK, "Hello World!")
                }
        } catch (exception: RequestAlreadyHandled) {
            // The method guard has already sent its response.
            status = exception.status
        } catch (exception: Exception) {
            logger.error(exception) { "Request failed: method=$method path=$path" }
            status =
                exchange.respond(
                    HttpURLConnection.HTTP_INTERNAL_ERROR,
                    "Spotify Butler request failed. Check server logs.",
                )
        } finally {
            val durationMs = (System.nanoTime() - startedAt) / 1_000_000
            logger.info {
                "Request finished: method=$method path=$path status=${status ?: "unknown"} durationMs=$durationMs"
            }
        }
    }

    private fun handleStart(exchange: HttpExchange): Int {
        requireGet(exchange)
        val refresh = exchange.requestURI.queryParameters()["refresh"] == "true"
        val authorization = authClient.beginAuthorization(refresh)
        exchange.responseHeaders.add(
            "Set-Cookie",
            "${SpotifyAuthClient.STATE_COOKIE}=${authorization.state}; Path=/; Max-Age=600; HttpOnly; SameSite=Lax",
        )
        return exchange.redirect(authorization.location)
    }

    private fun handleCallback(exchange: HttpExchange): Int {
        requireGet(exchange)
        val parameters = exchange.requestURI.queryParameters()
        val state = parameters["state"]
        if (!authClient.validateCallback(state, exchange.requestCookies()[SpotifyAuthClient.STATE_COOKIE])) {
            return exchange.respond(
                HttpURLConnection.HTTP_BAD_REQUEST,
                "Invalid or expired Spotify authorization state.",
            )
        }
        exchange.responseHeaders.add(
            "Set-Cookie",
            "${SpotifyAuthClient.STATE_COOKIE}=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax",
        )
        val code =
            parameters["code"] ?: run {
                return exchange.respond(
                    HttpURLConnection.HTTP_BAD_REQUEST,
                    "Spotify callback did not include an authorization code.",
                )
            }
        val token = authClient.exchangeAuthorizationCode(code)
        exchange.responseHeaders.add(
            "Set-Cookie",
            "spotify_access_token=${token.accessToken}; " +
                "Path=/; Max-Age=${token.expiresInSeconds}; HttpOnly; SameSite=Lax",
        )
        return exchange.redirect(URI("/hello"))
    }

    private fun handleHello(exchange: HttpExchange): Int {
        requireGet(exchange)
        val accessToken = exchange.requestCookies()["spotify_access_token"]
        if (accessToken.isNullOrBlank()) {
            return exchange.respond(
                HttpURLConnection.HTTP_UNAUTHORIZED,
                "No Spotify session. Start at /start.",
            )
        }
        val user = apiClient.getCurrentUser(accessToken)
        return exchange.respond(HttpURLConnection.HTTP_OK, "hello, ${user.displayName ?: user.id}")
    }

    private fun requireGet(exchange: HttpExchange) {
        if (exchange.requestMethod != "GET") {
            exchange.respond(HttpURLConnection.HTTP_BAD_METHOD, "Method not allowed")
            throw RequestAlreadyHandled(HttpURLConnection.HTTP_BAD_METHOD)
        }
    }

    private fun HttpExchange.redirect(location: URI): Int {
        responseHeaders.add("Location", location.toASCIIString())
        sendResponseHeaders(HttpURLConnection.HTTP_MOVED_TEMP, -1)
        close()
        return HttpURLConnection.HTTP_MOVED_TEMP
    }

    private fun HttpExchange.respond(
        status: Int,
        body: String,
    ): Int {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        responseHeaders.set("Content-Type", "text/plain; charset=utf-8")
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
        return status
    }

    private fun URI.queryParameters(): Map<String, String> =
        rawQuery
            .orEmpty()
            .split("&")
            .filter(String::isNotBlank)
            .associate { part ->
                val pieces = part.split("=", limit = 2)
                URLDecoder.decode(pieces[0], StandardCharsets.UTF_8) to
                    URLDecoder.decode(pieces.getOrElse(1) { "" }, StandardCharsets.UTF_8)
            }

    private fun HttpExchange.requestCookies(): Map<String, String> =
        requestHeaders
            .getFirst("Cookie")
            .orEmpty()
            .split(';')
            .mapNotNull { cookie ->
                val pieces = cookie.trim().split("=", limit = 2)
                pieces.firstOrNull()?.takeIf(String::isNotEmpty)?.let {
                    it to pieces.getOrElse(1) { "" }
                }
            }.toMap()

    private data class RequestAlreadyHandled(
        val status: Int,
    ) : RuntimeException()
}
