@file:Suppress("TooManyFunctions", "MagicNumber", "TooGenericExceptionCaught", "ktlint:standard:filename")

package com.philipwilcox.spotifybutler.http

import com.philipwilcox.spotifybutler.db.SpotifyStore
import com.philipwilcox.spotifybutler.service.SpotifyCacheService
import com.philipwilcox.spotifybutler.spotify.SpotifyApiClient
import com.philipwilcox.spotifybutler.spotify.SpotifyAuthClient
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.intercept
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.send
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.takeWhile
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

class ButlerHttpServer(
    private val authClient: SpotifyAuthClient,
    private val apiClient: SpotifyApiClient,
    cacheService: SpotifyCacheService,
    store: SpotifyStore,
    private val host: String = "127.0.0.1",
    private val port: Int = 8888,
    private val allowedSpotifyUserId: String? = null,
    private val trustedOrigins: Set<String> = emptySet(),
    private val secureCookies: Boolean = false,
    private val callbackHttpsRequired: Boolean = false,
    private val trustedHosts: Set<String> = setOf("127.0.0.1:8888", "localhost:8888"),
    private val trustedProxyAddresses: Set<String> = emptySet(),
    private val trustedProxyToken: String? = null,
    private val frontendDirectory: Path = Path.of("vue", "dist"),
) {
    private val logger = KotlinLogging.logger {}
    private val sessionStore = SessionStore(authStore = store)
    private val operationRegistry = OperationRegistry()
    private val apiApplication =
        ApiApplication(
            cacheService,
            store,
            sessionStore,
            apiClient,
            trustedOrigins = trustedOrigins,
            authClient = authClient,
            allowedSpotifyUserId = allowedSpotifyUserId,
            secureCookies = secureCookies,
            operationRegistry = operationRegistry,
        )

    fun start() {
        embeddedServer(Netty, host = host, port = port) {
            install(WebSockets) {
                pingPeriodMillis = 20.seconds.inWholeMilliseconds
                timeoutMillis = 60.seconds.inWholeMilliseconds
                maxFrameSize = 2L * 1024 * 1024
            }
            environment.monitor.subscribe(ApplicationStopped) { operationRegistry.close() }
            intercept(ApplicationCallPipeline.Call) {
                if (!isOperationSocketPath(call.request.path())) {
                    handleCall(call)
                    finish()
                }
            }
            routing {
                webSocket("/api/v1/operations/{operationId}/events") {
                    handleOperationSocket(call, call.parameters["operationId"])
                }
            }
        }.start(wait = true)
    }

    private suspend fun handleCall(call: ApplicationCall) {
        val requestId = call.request.headers["X-Request-Id"]?.takeIf(::validRequestId) ?: "req-${UUID.randomUUID()}"
        call.response.header("X-Request-Id", requestId)
        try {
            validateHost(call)
            if (call.request.path() == "/callback" && callbackHttpsRequired && effectiveScheme(call) != "https") {
                throw RequestFailure(400, "HTTPS is required for the OAuth callback")
            }
            when (call.request.path()) {
                "/health" -> health(call)
                "/start" -> start(call)
                "/callback" -> callback(call, requestId)
                else -> if (call.request.path().startsWith("/api/v1")) api(call) else frontend(call, requestId)
            }
        } catch (failure: RequestFailure) {
            json(call, failure.status, errorJson("request_rejected", failure.message, requestId))
        } catch (exception: Exception) {
            logger.error(
                exception,
            ) { "Request failed: method=${call.request.httpMethod.value} path=${call.request.path()}" }
            json(call, 500, errorJson("internal_error", "Request failed", requestId))
        }
    }

    private suspend fun io.ktor.server.websocket.DefaultWebSocketServerSession.handleOperationSocket(
        call: ApplicationCall,
        operationId: String?,
    ) {
        val requestId = call.request.headers["X-Request-Id"]?.takeIf(::validRequestId) ?: "req-${UUID.randomUUID()}"
        try {
            validateHost(call)
        } catch (_: RequestFailure) {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "unauthorized"))
            return
        }
        val request =
            ApiRequest(
                method = call.request.httpMethod.value,
                path = call.request.path(),
                query =
                    call.request.queryParameters
                        .entries()
                        .associate { it.key to it.value.first() },
                headers =
                    call.request.headers
                        .entries()
                        .associate { it.key to it.value.joinToString(",") },
            )
        val authorization = apiApplication.authorizeOperationSocket(request, requestId)
        val owner = (authorization as? OperationSocketAuthorization.Accepted)?.ownerSpotifyUserId
        if (owner == null) {
            val code =
                (authorization as OperationSocketAuthorization.Rejected)
                    .response.body
                    .let {
                        runCatching {
                            apiJson
                                .decodeFromString<ErrorEnvelope>(
                                    it,
                                ).code
                        }.getOrDefault("unauthorized")
                    }
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, code))
            return
        }
        val updates = operationId?.let { operationRegistry.updates(owner, it) }
        if (updates == null) {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "operation_not_found"))
            return
        }
        updates
            .takeWhile { status ->
                send(Frame.Text(apiJson.encodeToString(status)))
                status.phase !in setOf(OperationPhase.succeeded, OperationPhase.failed)
            }.collect {}
        close()
    }

    private suspend fun health(call: ApplicationCall) {
        requireGet(call)
        json(call, 200, "{\"status\":\"ready\"}")
    }

    private suspend fun start(call: ApplicationCall) {
        requireGet(call)
        val returnTo =
            call.request.queryParameters["returnTo"]
                .orEmpty()
                .ifBlank { "/" }
        require(returnTo.startsWith("/") && !returnTo.startsWith("//") && !returnTo.contains('\\'))
        val authorization = authClient.beginAuthorization(returnTo)
        call.response.header(
            HttpHeaders.SetCookie,
            cookie(SpotifyAuthClient.STATE_COOKIE, authorization.state, 600, true),
        )
        call.response.header(HttpHeaders.Location, authorization.location.toASCIIString())
        call.respondText("", status = HttpStatusCode.Found)
    }

    private suspend fun callback(
        call: ApplicationCall,
        requestId: String,
    ) {
        requireGet(call)
        val authorization =
            authClient.consumeCallbackAuthorization(
                call.request.queryParameters["state"],
                cookies(call)[SpotifyAuthClient.STATE_COOKIE],
            )
                ?: return json(
                    call,
                    400,
                    errorJson("invalid_state", "Invalid or expired authorization state", requestId),
                )
        val code =
            call.request.queryParameters["code"]
                ?: return json(
                    call,
                    400,
                    errorJson("missing_code", "Spotify callback did not include an authorization code", requestId),
                )
        val token = authClient.exchangeAuthorizationCode(code, authorization.codeVerifier)
        val user = apiClient.getCurrentUser(token.accessToken)
        if (allowedSpotifyUserId != null && user.id != allowedSpotifyUserId) {
            return json(call, 403, errorJson("user_not_allowed", "This Spotify account is not allowed", requestId))
        }
        val session = sessionStore.create(user.id, token.accessToken, token.refreshToken, token.expiresInSeconds)
        call.response.headers.append(
            HttpHeaders.SetCookie,
            cookie(SpotifyAuthClient.STATE_COOKIE, "", 0, true),
            safeOnly = false,
        )
        call.response.headers.append(
            HttpHeaders.SetCookie,
            cookie("butler_session", session.id, 15_552_000, true),
            safeOnly = false,
        )
        call.response.header(HttpHeaders.Location, authorization.returnTo)
        call.respondText("", status = HttpStatusCode.SeeOther)
    }

    private suspend fun api(call: ApplicationCall) {
        val body = if (call.request.httpMethod.value == "GET") null else call.receiveText().takeIf(String::isNotEmpty)
        if (body != null &&
            body.toByteArray(StandardCharsets.UTF_8).size > MAX_REQUEST_BYTES
        ) {
            throw RequestFailure(413, "Request body is too large")
        }
        val response =
            apiApplication.handle(
                ApiRequest(
                    call.request.httpMethod.value,
                    call.request.path(),
                    call.request.queryParameters
                        .entries()
                        .associate { it.key to it.value.first() },
                    call.request.headers
                        .entries()
                        .associate { it.key to it.value.joinToString(",") },
                    body,
                ),
            )
        response.headers.forEach { (name, value) -> call.response.headers.append(name, value, safeOnly = false) }
        call.response.header(HttpHeaders.CacheControl, "no-store")
        json(call, response.status, response.body)
    }

    private suspend fun frontend(
        call: ApplicationCall,
        requestId: String,
    ) {
        requireGet(call)
        val resolver = FrontendAssetResolver(frontendDirectory)
        if (resolver.isUnsafe(call.request.path())) throw RequestFailure(400, "Invalid frontend asset path")
        if (!Files.isRegularFile(frontendDirectory.resolve("index.html"))) {
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respondText(
                "Frontend build is missing. Run npm --prefix vue run build before starting Spotify Butler.",
                contentType = io.ktor.http.ContentType.Text.Plain,
                status = HttpStatusCode.ServiceUnavailable,
            )
            return
        }
        val asset =
            resolver.resolve(call.request.path())
                ?: return json(call, 404, errorJson("not_found", "Frontend asset not found", requestId))
        call.response.header(HttpHeaders.CacheControl, asset.cacheControl)
        call.respondBytes(
            asset.bytes,
            io.ktor.http.ContentType
                .parse(asset.contentType),
        )
    }

    private suspend fun json(
        call: ApplicationCall,
        status: Int,
        body: String,
    ) = call.respondText(body, io.ktor.http.ContentType.Application.Json, HttpStatusCode.fromValue(status))

    private fun requireGet(call: ApplicationCall) {
        if (call.request.httpMethod.value !=
            "GET"
        ) {
            throw RequestFailure(405, "Method not allowed")
        }
    }

    private fun cookies(call: ApplicationCall) =
        call.request.headers[HttpHeaders.Cookie]
            .orEmpty()
            .split(';')
            .mapNotNull {
                it.trim().split('=', limit = 2).let { part ->
                    part.firstOrNull()?.takeIf(String::isNotEmpty)?.let { key ->
                        key to
                            part.getOrElse(1) { "" }
                    }
                }
            }.toMap()

    private fun cookie(
        name: String,
        value: String,
        maxAge: Int,
        httpOnly: Boolean,
    ) = buildString {
        append("$name=$value; Path=/; Max-Age=$maxAge; SameSite=${if (name == "butler_session") "Strict" else "Lax"}")
        if (httpOnly) append("; HttpOnly")
        if (secureCookies) append("; Secure")
    }

    private fun errorJson(
        code: String,
        message: String,
        requestId: String,
    ) = "{\"code\":\"$code\",\"message\":\"$message\",\"requestId\":\"$requestId\",\"details\":{}}"

    private fun validRequestId(value: String) =
        value.length in 1..100 && value.all { it.isLetterOrDigit() || it in "-_" }

    private fun validateHost(call: ApplicationCall) {
        val host = effectiveHost(call)
        if (host == null ||
            host !in trustedHosts.map(String::lowercase).toSet()
        ) {
            throw RequestFailure(400, "The request Host is not trusted")
        }
    }

    private fun effectiveHost(call: ApplicationCall): String? {
        val direct =
            call.request.headers[HttpHeaders.Host]
                ?.trim()
                ?.lowercase()
        return if (!trustedProxy(call)) {
            direct
        } else {
            call.request.headers["X-Forwarded-Host"]
                ?.substringBefore(',')
                ?.trim()
                ?.lowercase()
                ?.ifEmpty { direct }
                ?: direct
        }
    }

    private fun effectiveScheme(call: ApplicationCall): String =
        if (!trustedProxy(call)) {
            "http"
        } else {
            call.request.headers["X-Forwarded-Proto"]?.substringBefore(',')?.trim()?.lowercase()?.takeIf {
                it ==
                    "http" ||
                    it == "https"
            }
                ?: "http"
        }

    private fun trustedProxy(call: ApplicationCall): Boolean {
        val address = call.request.local.remoteAddress
        return address in trustedProxyAddresses ||
            tokensMatch(call.request.headers[PROXY_TOKEN_HEADER], trustedProxyToken)
    }

    private fun isOperationSocketPath(path: String): Boolean =
        path.startsWith("/api/v1/operations/") && path.endsWith("/events")

    private data class RequestFailure(
        val status: Int,
        override val message: String,
    ) : RuntimeException(message)

    private companion object {
        const val MAX_REQUEST_BYTES = 1_048_576
        const val PROXY_TOKEN_HEADER = "X-Butler-Proxy-Token"
    }
}
