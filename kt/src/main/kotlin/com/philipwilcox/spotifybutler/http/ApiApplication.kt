// This application facade intentionally keeps route policy, DTO mapping, and resource authorization together.
@file:Suppress(
    "TooManyFunctions",
    "LargeClass",
    "CyclomaticComplexMethod",
    "TooGenericExceptionCaught",
    "SwallowedException",
    "MagicNumber",
    "ThrowsCount",
)

package com.philipwilcox.spotifybutler.http

import com.philipwilcox.spotifybutler.db.SpotifyStore
import com.philipwilcox.spotifybutler.service.CacheLoadResult
import com.philipwilcox.spotifybutler.service.PlaylistDefinition
import com.philipwilcox.spotifybutler.service.PlaylistQueries
import com.philipwilcox.spotifybutler.service.SpotifyCacheService
import com.philipwilcox.spotifybutler.spotify.SpotifyApiClient
import com.philipwilcox.spotifybutler.spotify.SpotifyAuthClient
import com.philipwilcox.spotifybutler.spotify.SpotifyTrack
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.net.HttpURLConnection
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Year
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

interface PlaylistSyncGateway {
    fun currentSnapshot(
        accessToken: String,
        playlistId: String,
    ): String?

    fun replaceTracks(
        accessToken: String,
        playlistId: String,
        trackIds: List<String>,
    ): String?
}

class SpotifyPlaylistSyncGateway(
    private val apiClient: SpotifyApiClient,
) : PlaylistSyncGateway {
    override fun currentSnapshot(
        accessToken: String,
        playlistId: String,
    ): String? = apiClient.getPlaylistSnapshot(accessToken, playlistId)

    override fun replaceTracks(
        accessToken: String,
        playlistId: String,
        trackIds: List<String>,
    ): String? = apiClient.replaceTrackIds(accessToken, playlistId, trackIds)
}

class ApiApplication(
    private val cacheService: SpotifyCacheService,
    private val store: SpotifyStore,
    private val sessionStore: SessionStore,
    private val operationStore: OperationStore,
    private val syncGateway: PlaylistSyncGateway,
    private val clock: Clock = Clock.systemUTC(),
    private val trustedOrigins: Set<String> = emptySet(),
    private val authClient: SpotifyAuthClient? = null,
) {
    private val logger = KotlinLogging.logger {}
    private val userDefinitions = ConcurrentHashMap<String, UserPlaylistDefinition>()

    constructor(
        cacheService: SpotifyCacheService,
        store: SpotifyStore,
        sessionStore: SessionStore,
        operationStore: OperationStore = OperationStore(),
        apiClient: SpotifyApiClient,
        clock: Clock = Clock.systemUTC(),
        trustedOrigins: Set<String> = emptySet(),
        authClient: SpotifyAuthClient? = null,
    ) : this(
        cacheService,
        store,
        sessionStore,
        operationStore,
        SpotifyPlaylistSyncGateway(apiClient),
        clock,
        trustedOrigins,
        authClient,
    )

    fun handle(request: ApiRequest): ApiResponse {
        val requestId = request.headers.header("X-Request-Id")?.takeIf(::validRequestId) ?: newRequestId()
        return try {
            require(request.path.startsWith("/api/v1/")) {
                ApiFailure(HttpURLConnection.HTTP_NOT_FOUND, "not_found", "Route not found")
            }
            val session = requireSession(request)
            route(request, session)
        } catch (failure: ApiFailure) {
            errorResponse(failure, requestId)
        } catch (exception: IllegalArgumentException) {
            errorResponse(
                ApiFailure(
                    HttpURLConnection.HTTP_BAD_REQUEST,
                    "malformed_request",
                    exception.message ?: "Invalid request",
                ),
                requestId,
            )
        } catch (exception: Exception) {
            logger.error(exception) { "API request failed path=${request.path}" }
            errorResponse(
                ApiFailure(
                    HttpURLConnection.HTTP_INTERNAL_ERROR,
                    "internal_error",
                    "The request could not be completed",
                ),
                requestId,
            )
        }
    }

    private fun route(
        request: ApiRequest,
        session: ButlerSession,
    ): ApiResponse {
        val parts = request.path.trim('/').split('/')
        return when {
            parts == listOf("api", "v1", "session") && request.method == "GET" -> session(request, session)
            parts == listOf("api", "v1", "session") && request.method == "DELETE" -> deleteSession(request, session)
            parts == listOf("api", "v1", "playlists") && request.method == "GET" -> playlists(session)
            parts == listOf("api", "v1", "playlists") && request.method == "POST" -> createPlaylist(request, session)
            parts.size == 4 && parts.take(3) == listOf("api", "v1", "playlists") && request.method == "GET" ->
                playlist(parts[3], session)
            parts.size == 5 &&
                parts.take(3) == listOf("api", "v1", "playlists") &&
                parts[4] == "current" &&
                request.method == "GET" -> current(parts[3], session)
            parts.size == 6 &&
                parts.take(3) == listOf("api", "v1", "playlists") &&
                parts[4] == "current" &&
                parts[5] == "items" &&
                request.method == "GET" -> currentItems(parts[3], request, session)
            parts.size == 5 &&
                parts.take(3) == listOf("api", "v1", "playlists") &&
                parts[4] == "syncs" &&
                request.method == "POST" -> sync(parts[3], request, session)
            parts.size == 6 &&
                parts.take(3) == listOf("api", "v1", "playlists") &&
                parts[4] == "syncs" &&
                parts[5] == "preview" &&
                request.method == "POST" -> preview(parts[3], request, session)
            parts == listOf("api", "v1", "library") && request.method == "GET" -> library(session)
            parts == listOf("api", "v1", "library", "refresh") && request.method == "POST" -> refresh(request, session)
            parts == listOf("api", "v1", "songs") && request.method == "GET" -> songs(request, session)
            parts.size == 4 && parts.take(3) == listOf("api", "v1", "songs") && request.method == "GET" ->
                song(parts[3], session)
            parts == listOf("api", "v1", "run") && request.method == "POST" -> run(request, session)
            parts == listOf("api", "v1", "operations") && request.method == "GET" -> operations(request, session)
            parts.size == 4 && parts.take(3) == listOf("api", "v1", "operations") && request.method == "GET" ->
                operation(parts[3], session)
            parts ==
                listOf(
                    "api",
                    "v1",
                    "session",
                    "refresh",
                ) &&
                request.method == "POST" -> refreshSession(request, session)
            else -> throw ApiFailure(HttpURLConnection.HTTP_NOT_FOUND, "not_found", "Route not found")
        }
    }

    private fun session(
        request: ApiRequest,
        session: ButlerSession,
    ): ApiResponse {
        if (request.method != "GET") requireStateChange(request, session)
        return json(
            HttpURLConnection.HTTP_OK,
            SessionWire(session.ownerSpotifyUserId, session.csrfToken, session.expiresAt.toString()),
        )
    }

    private fun deleteSession(
        request: ApiRequest,
        session: ButlerSession,
    ): ApiResponse {
        requireStateChange(request, session)
        sessionStore.remove(session.id)
        return ApiResponse(
            HttpURLConnection.HTTP_NO_CONTENT,
            "",
            mapOf(
                "Content-Type" to "application/json; charset=utf-8",
            ),
        )
    }

    private fun refreshSession(
        request: ApiRequest,
        session: ButlerSession,
    ): ApiResponse {
        requireStateChange(request, session)
        val refreshToken =
            session.refreshToken
                ?: throw ApiFailure(
                    HttpURLConnection.HTTP_CONFLICT,
                    "refresh_unavailable",
                    "No refresh token is available",
                )
        val client =
            authClient
                ?: throw ApiFailure(
                    HttpURLConnection.HTTP_NOT_IMPLEMENTED,
                    "refresh_unavailable",
                    "Session refresh is not configured",
                )
        val token =
            try {
                client.refreshAccessToken(refreshToken)
            } catch (exception: Exception) {
                throw ApiFailure(
                    HttpURLConnection.HTTP_BAD_GATEWAY,
                    "spotify_auth_failure",
                    "Spotify session refresh failed",
                )
            }
        val rotated = sessionStore.rotate(session, token.accessToken, token.refreshToken)
        return ApiResponse(
            HttpURLConnection.HTTP_OK,
            apiJson.encodeToString(
                SessionWire(rotated.ownerSpotifyUserId, rotated.csrfToken, rotated.expiresAt.toString()),
            ),
            mapOf(
                "Content-Type" to "application/json; charset=utf-8",
                "Set-Cookie" to "butler_session=${rotated.id}; Path=/; Max-Age=43200; SameSite=Lax; HttpOnly",
            ),
        )
    }

    private fun playlists(session: ButlerSession): ApiResponse {
        requireCacheOwner(session)
        val metadata = store.cacheMetadata()
        val definitions = builtInDefinitions() + userDefinitions.values.map(UserPlaylistDefinition::asView)
        val response =
            PlaylistListWire(
                items = definitions.map { definition -> playlistReference(definition, session, metadata?.revision) },
                cacheRevision = metadata?.revision,
            )
        return json(HttpURLConnection.HTTP_OK, response)
    }

    private fun createPlaylist(
        request: ApiRequest,
        session: ButlerSession,
    ): ApiResponse {
        requireStateChange(request, session)
        val input = body<CreatePlaylistRequest>(request)
        require(input.name.isNotBlank()) { "name must not be blank" }
        require(input.name.length <= MAX_NAME_LENGTH) { "name is too long" }
        validateTrackIds(input.trackIds)
        val id = "user-${Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest
                .getInstance(
                    "SHA-256",
                ).digest("${session.ownerSpotifyUserId}:${input.name}:${clock.millis()}".toByteArray()),
        ).take(16)}"
        val definition =
            UserPlaylistDefinition(id, input.name, input.trackIds.toList(), revision(input.name, input.trackIds))
        userDefinitions[id] = definition
        return json(
            HttpURLConnection.HTTP_CREATED,
            playlistReference(definition.asView(), session, store.cacheMetadata()?.revision),
        )
    }

    private fun playlist(
        definitionId: String,
        session: ButlerSession,
    ): ApiResponse {
        val definition = definition(definitionId)
        return json(HttpURLConnection.HTTP_OK, playlistReference(definition, session, store.cacheMetadata()?.revision))
    }

    private fun current(
        definitionId: String,
        session: ButlerSession,
    ): ApiResponse {
        requireCacheOwner(session)
        val definition = definition(definitionId)
        val metadata =
            store.cacheMetadata() ?: return json(HttpURLConnection.HTTP_OK, PlaylistCurrentEnvelopeWire(null))
        val playlistId =
            resolvePlaylistId(definition, session)
                ?: return json(HttpURLConnection.HTTP_OK, PlaylistCurrentEnvelopeWire(null))
        val details = store.playlistDetails(playlistId)
        val trackIds = store.playlistItems(playlistId).filter { it.isPlayable }.mapNotNull { it.itemId }
        return json(
            HttpURLConnection.HTTP_OK,
            PlaylistCurrentEnvelopeWire(
                PlaylistCurrentWire(playlistId, details?.snapshotId, metadata.revision, trackIds),
            ),
        )
    }

    private fun currentItems(
        definitionId: String,
        request: ApiRequest,
        session: ButlerSession,
    ): ApiResponse {
        requireCacheOwner(session)
        val definition = definition(definitionId)
        val metadata =
            store.cacheMetadata()
                ?: throw ApiFailure(
                    HttpURLConnection.HTTP_CONFLICT,
                    "cache_not_ready",
                    "The library cache is not ready",
                )
        val playlistId =
            resolvePlaylistId(definition, session)
                ?: return json(HttpURLConnection.HTTP_OK, PlaylistItemsWire(emptyList(), null, metadata.revision))
        val offset = decodeCursor(request.query["cursor"], metadata.revision, definitionId)
        val limit = boundedLimit(request.query["limit"])
        val all = store.playlistItems(playlistId)
        val page = all.drop(offset).take(limit)
        val next =
            if (offset + page.size <
                all.size
            ) {
                encodeCursor(metadata.revision, definitionId, offset + page.size)
            } else {
                null
            }
        return json(
            HttpURLConnection.HTTP_OK,
            PlaylistItemsWire(
                page.map {
                    PlaylistItemWire(
                        playlistId = it.playlistId,
                        position = it.position,
                        type = it.itemType,
                        status = it.status,
                        trackId = it.itemId,
                        uri = it.itemUri,
                        addedAt = it.addedAt,
                        addedById = it.addedById,
                        local = it.isLocal,
                        playable = it.isPlayable,
                    )
                },
                next,
                metadata.revision,
            ),
        )
    }

    private fun library(session: ButlerSession): ApiResponse {
        requireCacheOwner(session)
        val metadata = store.cacheMetadata()
        val status = if (metadata?.completionState == "complete") "ready" else "empty"
        return json(
            HttpURLConnection.HTTP_OK,
            LibraryWire(
                status = status,
                cacheRevision = metadata?.revision,
                ownerId = metadata?.ownerSpotifyUserId,
                refreshOperationId = null,
                completedAt =
                    metadata?.syncTimestampMillis?.let {
                        java.time.Instant
                            .ofEpochMilli(it)
                            .toString()
                    },
                counts = mapOf("songs" to store.songs().size, "playlists" to builtInDefinitions().size),
            ),
        )
    }

    private fun songs(
        request: ApiRequest,
        session: ButlerSession,
    ): ApiResponse {
        requireCacheOwner(session)
        val ids =
            request.query["ids"]
                ?.split(',')
                ?.filter(String::isNotBlank)
                .orEmpty()
        require(ids.isNotEmpty()) { "ids must contain at least one track ID" }
        require(ids.size <= MAX_SONG_IDS) { "ids contains too many track IDs" }
        val tracksById = store.songs().associateBy(SpotifyTrack::id)
        val tracks = ids.mapNotNull(tracksById::get)
        return json(
            HttpURLConnection.HTTP_OK,
            SongsWire(
                items = tracks.map { it.toSongWire(store.cacheMetadata()?.revision) },
                missingIds = ids.distinct().filterNot(tracksById::containsKey),
                cacheRevision = store.cacheMetadata()?.revision,
            ),
        )
    }

    private fun song(
        trackId: String,
        session: ButlerSession,
    ): ApiResponse {
        requireCacheOwner(session)
        val track =
            store.song(trackId)
                ?: throw ApiFailure(HttpURLConnection.HTTP_NOT_FOUND, "song_not_found", "Song not found")
        return json(HttpURLConnection.HTTP_OK, track.toSongWire(store.cacheMetadata()?.revision))
    }

    private fun refresh(
        request: ApiRequest,
        session: ButlerSession,
    ): ApiResponse {
        requireStateChange(request, session)
        val key = requiredIdempotencyKey(request)
        val fingerprint = fingerprint(request.body.orEmpty())
        return when (
            val lookup =
                operationStore.lookup(
                    session.ownerSpotifyUserId,
                    "library_refresh",
                    key,
                    fingerprint,
                )
        ) {
            IdempotencyLookup.Conflict -> throw ApiFailure(
                HttpURLConnection.HTTP_CONFLICT,
                "idempotency_conflict",
                "Idempotency-Key was already used for another request",
            )
            is IdempotencyLookup.Existing -> json(HttpURLConnection.HTTP_ACCEPTED, lookup.operation.toWire())
            IdempotencyLookup.New -> {
                val operation = operationStore.create(session.ownerSpotifyUserId, "library_refresh", key, fingerprint)
                val running = operationStore.running(operation)
                try {
                    val result =
                        cacheService.loadIfNeeded(
                            session.accessToken,
                            refresh = true,
                            ownerSpotifyUserId = session.ownerSpotifyUserId,
                        )
                    val resultJson =
                        buildJsonObject {
                            put("status", JsonPrimitive(result::class.simpleName ?: "loaded"))
                            if (result is CacheLoadResult.Loaded) {
                                put("savedTrackCount", JsonPrimitive(result.savedTrackCount))
                                put("playlistCount", JsonPrimitive(result.playlistCount))
                                put("playlistItemCount", JsonPrimitive(result.playlistTrackCount))
                            }
                        }.toString()
                    json(HttpURLConnection.HTTP_ACCEPTED, operationStore.succeeded(running, resultJson).toWire())
                } catch (exception: Exception) {
                    json(
                        HttpURLConnection.HTTP_ACCEPTED,
                        operationStore.failed(running, "spotify_failure", "Library refresh failed").toWire(),
                    )
                }
            }
        }
    }

    private fun run(
        request: ApiRequest,
        session: ButlerSession,
    ): ApiResponse {
        requireStateChange(request, session)
        val key = requiredIdempotencyKey(request)
        val fingerprint = fingerprint(request.body.orEmpty())
        return when (val lookup = operationStore.lookup(session.ownerSpotifyUserId, "legacy_run", key, fingerprint)) {
            IdempotencyLookup.Conflict -> throw ApiFailure(
                HttpURLConnection.HTTP_CONFLICT,
                "idempotency_conflict",
                "Idempotency-Key was already used for another request",
            )
            is IdempotencyLookup.Existing -> json(HttpURLConnection.HTTP_ACCEPTED, lookup.operation.toWire())
            IdempotencyLookup.New -> {
                val operation = operationStore.create(session.ownerSpotifyUserId, "legacy_run", key, fingerprint)
                val running = operationStore.running(operation)
                try {
                    cacheService.loadIfNeeded(
                        session.accessToken,
                        refresh = true,
                        ownerSpotifyUserId = session.ownerSpotifyUserId,
                    )
                    json(
                        HttpURLConnection.HTTP_ACCEPTED,
                        operationStore.succeeded(running, "{\"status\":\"cache_refreshed\"}").toWire(),
                    )
                } catch (exception: Exception) {
                    json(
                        HttpURLConnection.HTTP_ACCEPTED,
                        operationStore.failed(running, "spotify_failure", "Legacy run failed").toWire(),
                    )
                }
            }
        }
    }

    private fun sync(
        definitionId: String,
        request: ApiRequest,
        session: ButlerSession,
    ): ApiResponse {
        requireStateChange(request, session)
        val input = body<SyncPlaylistRequest>(request)
        validateTrackIds(input.trackIds)
        val key = requiredIdempotencyKey(request)
        val fingerprint = fingerprint(request.body.orEmpty())
        when (val lookup = operationStore.lookup(session.ownerSpotifyUserId, "playlist_sync", key, fingerprint)) {
            IdempotencyLookup.Conflict -> throw ApiFailure(
                HttpURLConnection.HTTP_CONFLICT,
                "idempotency_conflict",
                "Idempotency-Key was already used for another request",
            )
            is IdempotencyLookup.Existing -> return json(HttpURLConnection.HTTP_ACCEPTED, lookup.operation.toWire())
            IdempotencyLookup.New -> Unit
        }
        val metadata =
            store.cacheMetadata()
                ?: throw ApiFailure(
                    HttpURLConnection.HTTP_CONFLICT,
                    "cache_not_ready",
                    "The library cache is not ready",
                )
        if (input.baseCacheRevision != null && input.baseCacheRevision != metadata.revision) {
            throw ApiFailure(
                HttpURLConnection.HTTP_CONFLICT,
                "cache_revision_stale",
                "The edited cache revision is no longer current",
            )
        }
        val definition = definition(definitionId)
        val playlistId =
            resolvePlaylistId(definition, session)
                ?: throw ApiFailure(HttpURLConnection.HTTP_CONFLICT, "mapping_missing", "The playlist is not mapped")
        val currentSnapshot = store.playlistDetails(playlistId)?.snapshotId
        if (input.baseSnapshotId != null && input.baseSnapshotId != currentSnapshot) {
            throw ApiFailure(
                HttpURLConnection.HTTP_CONFLICT,
                "playlist_changed",
                "The Spotify playlist changed since it was edited",
            )
        }
        val operation = operationStore.create(session.ownerSpotifyUserId, "playlist_sync", key, fingerprint)
        val running = operationStore.running(operation)
        return try {
            val liveSnapshot = syncGateway.currentSnapshot(session.accessToken, playlistId)
            if (input.baseSnapshotId != null && liveSnapshot != null && input.baseSnapshotId != liveSnapshot) {
                val failed =
                    operationStore.failed(
                        running,
                        "playlist_changed",
                        "The Spotify playlist changed since it was edited",
                    )
                return json(HttpURLConnection.HTTP_ACCEPTED, failed.toWire())
            }
            val newSnapshot = syncGateway.replaceTracks(session.accessToken, playlistId, input.trackIds)
            store.publishPlaylistTrackIds(playlistId, input.trackIds, newSnapshot)
            json(
                HttpURLConnection.HTTP_ACCEPTED,
                operationStore
                    .succeeded(
                        running,
                        buildJsonObject {
                            put("playlistId", JsonPrimitive(playlistId))
                            put("trackCount", JsonPrimitive(input.trackIds.size))
                        }.toString(),
                    ).toWire(),
            )
        } catch (exception: Exception) {
            json(
                HttpURLConnection.HTTP_ACCEPTED,
                operationStore.failed(running, "spotify_failure", "Playlist synchronization failed").toWire(),
            )
        }
    }

    private fun preview(
        definitionId: String,
        request: ApiRequest,
        session: ButlerSession,
    ): ApiResponse {
        requireStateChange(request, session)
        val input = body<SyncPlaylistRequest>(request)
        validateTrackIds(input.trackIds)
        val definition = definition(definitionId)
        val playlistId =
            resolvePlaylistId(definition, session)
                ?: throw ApiFailure(HttpURLConnection.HTTP_CONFLICT, "mapping_missing", "The playlist is not mapped")
        val current = store.playlistItems(playlistId).filter { it.isPlayable }.mapNotNull { it.itemId }
        return json(
            HttpURLConnection.HTTP_OK,
            PreviewWire(
                cacheRevision = store.cacheMetadata()?.revision,
                baseSnapshotId = store.playlistDetails(playlistId)?.snapshotId,
                currentTrackIds = current,
                submittedTrackIds = input.trackIds,
                toAdd = multisetDifference(input.trackIds, current),
                toRemove = multisetDifference(current, input.trackIds),
            ),
        )
    }

    private fun operations(
        request: ApiRequest,
        session: ButlerSession,
    ): ApiResponse {
        val limit = boundedLimit(request.query["limit"])
        val status =
            request.query["status"]?.let { value ->
                OperationStatus.entries.firstOrNull { it.name.equals(value, true) }
                    ?: throw ApiFailure(
                        HttpURLConnection.HTTP_BAD_REQUEST,
                        "invalid_status",
                        "Unknown operation status",
                    )
            }
        val records = operationStore.list(session.ownerSpotifyUserId, status, request.query["type"], limit)
        return json(HttpURLConnection.HTTP_OK, OperationsWire(records.map(OperationRecord::toWire)))
    }

    private fun operation(
        id: String,
        session: ButlerSession,
    ): ApiResponse {
        val operation =
            operationStore.findById(id)?.takeIf { it.ownerSpotifyUserId == session.ownerSpotifyUserId }
                ?: throw ApiFailure(HttpURLConnection.HTTP_NOT_FOUND, "not_found", "Operation not found")
        return json(HttpURLConnection.HTTP_OK, operation.toWire())
    }

    private fun definition(id: String): DefinitionView =
        userDefinitions[id]?.asView()
            ?: builtInDefinitions().firstOrNull { it.id == id }
            ?: throw ApiFailure(HttpURLConnection.HTTP_NOT_FOUND, "not_found", "Playlist definition not found")

    private fun builtInDefinitions(): List<DefinitionView> =
        PlaylistQueries.definitions(Year.now(clock).value, MIN_DISCOVER_WEEKLY_YEAR).map { definition ->
            DefinitionView(definition.id.name, definition.name, definition, emptyList())
        }

    private fun playlistReference(
        definition: DefinitionView,
        session: ButlerSession,
        cacheRevision: String?,
    ): PlaylistReferenceWire {
        val mapping = resolvePlaylistId(definition, session)
        return PlaylistReferenceWire(
            id = definition.id,
            name = definition.name,
            state = if (mapping == null) "unmapped" else "mapped",
            definitionRevision = definitionRevision(definition),
            cacheRevision = cacheRevision,
            spotifyPlaylistId = mapping,
            trackIds = definition.trackIds,
        )
    }

    private fun resolvePlaylistId(
        definition: DefinitionView,
        session: ButlerSession,
    ): String? {
        val revision = definitionRevision(definition)
        store.managedPlaylist(definition.id, revision, session.ownerSpotifyUserId)?.let { return it.spotifyPlaylistId }
        val matches = store.playlistMatchesByName(definition.name)
        if (matches.size >
            1
        ) {
            throw ApiFailure(
                HttpURLConnection.HTTP_CONFLICT,
                "playlist_mapping_conflict",
                "More than one owned playlist matches the definition name",
            )
        }
        val cachedId = matches.singleOrNull()?.id ?: return null
        val owner = store.playlistDetails(cachedId)?.ownerId
        if (owner != null && owner != session.ownerSpotifyUserId) return null
        store.saveManagedPlaylist(definition.id, revision, cachedId, session.ownerSpotifyUserId)
        return cachedId
    }

    private fun requireSession(request: ApiRequest): ButlerSession {
        val session =
            sessionStore.find(request.headers.cookie("butler_session"))
                ?: throw ApiFailure(HttpURLConnection.HTTP_UNAUTHORIZED, "unauthorized", "A Butler session is required")
        if (store.cacheMetadata()?.ownerSpotifyUserId != null &&
            store.cacheMetadata()?.ownerSpotifyUserId != session.ownerSpotifyUserId
        ) {
            throw ApiFailure(HttpURLConnection.HTTP_FORBIDDEN, "owner_mismatch", "The session does not own this cache")
        }
        return session
    }

    private fun requireCacheOwner(session: ButlerSession) {
        val owner = store.cacheMetadata()?.ownerSpotifyUserId
        if (owner != null && owner != session.ownerSpotifyUserId) {
            throw ApiFailure(HttpURLConnection.HTTP_FORBIDDEN, "owner_mismatch", "The session does not own this cache")
        }
    }

    private fun requireStateChange(
        request: ApiRequest,
        session: ButlerSession,
    ) {
        if (request.headers.header("X-CSRF-Token") != session.csrfToken) {
            throw ApiFailure(HttpURLConnection.HTTP_FORBIDDEN, "csrf_failed", "The CSRF token is invalid")
        }
        val origin = request.headers.header("Origin")
        if (origin != null && trustedOrigins.isNotEmpty() && origin !in trustedOrigins) {
            throw ApiFailure(
                HttpURLConnection.HTTP_FORBIDDEN,
                "origin_not_trusted",
                "The request origin is not trusted",
            )
        }
        if (request.body != null &&
            !request.headers
                .header("Content-Type")
                .orEmpty()
                .lowercase()
                .startsWith("application/json")
        ) {
            throw ApiFailure(415, "unsupported_media_type", "JSON content is required")
        }
    }

    private inline fun <reified T> body(request: ApiRequest): T {
        val body =
            request.body
                ?: throw ApiFailure(HttpURLConnection.HTTP_BAD_REQUEST, "malformed_request", "A JSON body is required")
        return try {
            apiJson.decodeFromString<T>(body)
        } catch (exception: Exception) {
            throw ApiFailure(HttpURLConnection.HTTP_BAD_REQUEST, "malformed_json", "The request body is not valid JSON")
        }
    }

    private fun validateTrackIds(trackIds: List<String>) {
        require(trackIds.size <= MAX_PLAYLIST_TRACKS) { "trackIds contains too many entries" }
        require(trackIds.all { it.matches(TRACK_ID_PATTERN) }) { "trackIds must contain Spotify track IDs" }
        val known = store.songs().associateBy(SpotifyTrack::id)
        val invalid =
            trackIds.distinct().filter {
                known[it] == null ||
                    known[it]?.available != true ||
                    !known.getValue(it).uri.startsWith("spotify:track:")
            }
        if (invalid.isNotEmpty()) {
            throw ApiFailure(
                422,
                "invalid_track_ids",
                "Some track IDs are unknown, unavailable, or not tracks",
                mapOf(
                    "ids" to invalid.joinToString(","),
                ),
            )
        }
    }

    private fun boundedLimit(value: String?): Int {
        val limit = value?.toIntOrNull() ?: DEFAULT_PAGE_SIZE
        if (limit !in
            1..MAX_PAGE_SIZE
        ) {
            throw ApiFailure(
                HttpURLConnection.HTTP_BAD_REQUEST,
                "invalid_limit",
                "limit must be between 1 and $MAX_PAGE_SIZE",
            )
        }
        return limit
    }

    private fun decodeCursor(
        cursor: String?,
        cacheRevision: String,
        definitionId: String,
    ): Int {
        if (cursor.isNullOrBlank()) return 0
        val decoded =
            runCatching { String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split('|') }.getOrNull()
                ?: throw ApiFailure(HttpURLConnection.HTTP_CONFLICT, "cursor_stale", "The cursor is invalid or stale")
        if (decoded.size != 3 || decoded[0] != cacheRevision || decoded[1] != definitionId) {
            throw ApiFailure(HttpURLConnection.HTTP_CONFLICT, "cursor_stale", "The cursor is invalid or stale")
        }
        return decoded[2].toIntOrNull()?.takeIf { it >= 0 }
            ?: throw ApiFailure(HttpURLConnection.HTTP_CONFLICT, "cursor_stale", "The cursor is invalid or stale")
    }

    private fun encodeCursor(
        cacheRevision: String,
        definitionId: String,
        offset: Int,
    ): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString("$cacheRevision|$definitionId|$offset".toByteArray())

    private fun multisetDifference(
        left: List<String>,
        right: List<String>,
    ): List<String> {
        val remaining = right.toMutableList()
        return left.filter { value ->
            val index = remaining.indexOf(value)
            if (index < 0) {
                true
            } else {
                remaining.removeAt(index)
                false
            }
        }
    }

    private fun fingerprint(body: String): String =
        MessageDigest.getInstance("SHA-256").digest(body.toByteArray()).joinToString("") {
            "%02x".format(it)
        }

    private fun revision(
        name: String,
        trackIds: List<String>,
    ): String = fingerprint("$name\u0000${trackIds.joinToString("\u0000")}")

    private fun definitionRevision(definition: DefinitionView): String = fingerprint(definition.id + definition.name)

    private inline fun <reified T> json(
        status: Int,
        value: T,
    ): ApiResponse = ApiResponse(status, apiJson.encodeToString(value))

    private fun errorResponse(
        failure: ApiFailure,
        requestId: String,
    ): ApiResponse =
        ApiResponse(
            failure.status,
            apiJson.encodeToString(ErrorEnvelope(failure.code, failure.message, requestId, failure.details)),
        )

    private fun requiredIdempotencyKey(request: ApiRequest): String =
        request.headers.header("Idempotency-Key")?.trim()?.takeIf {
            it.isNotEmpty() &&
                it.length <= MAX_IDEMPOTENCY_KEY_LENGTH
        }
            ?: throw ApiFailure(
                HttpURLConnection.HTTP_BAD_REQUEST,
                "idempotency_key_required",
                "Idempotency-Key is required",
            )

    private fun newRequestId(): String =
        "req-${Base64.getUrlEncoder().withoutPadding().encodeToString(
            ByteArray(12).also(java.security.SecureRandom()::nextBytes),
        )}"

    private fun validRequestId(value: String): Boolean =
        value.length in 1..100 && value.all { it.isLetterOrDigit() || it in "-_" }

    private fun Map<String, String>.header(name: String): String? =
        entries.firstOrNull { it.key.equals(name, true) }?.value

    private fun Map<String, String>.cookie(name: String): String? =
        header("Cookie")
            ?.split(';')
            ?.map {
                it.trim().split('=', limit = 2)
            }?.firstOrNull { it.firstOrNull() == name }
            ?.getOrNull(1)

    private data class DefinitionView(
        val id: String,
        val name: String,
        val builtin: PlaylistDefinition?,
        val trackIds: List<String>,
    )

    private data class UserPlaylistDefinition(
        val id: String,
        val name: String,
        val trackIds: List<String>,
        val revision: String,
    ) {
        fun asView(): DefinitionView = DefinitionView(id, name, null, trackIds)
    }

    private data class ApiFailure(
        val status: Int,
        val code: String,
        override val message: String,
        val details: Map<String, String> = emptyMap(),
    ) : RuntimeException(message)

    companion object {
        private const val MIN_DISCOVER_WEEKLY_YEAR = 2018
        private const val DEFAULT_PAGE_SIZE = 50
        private const val MAX_PAGE_SIZE = 100
        private const val MAX_SONG_IDS = 50
        private const val MAX_PLAYLIST_TRACKS = 5000
        private const val MAX_NAME_LENGTH = 200
        private const val MAX_IDEMPOTENCY_KEY_LENGTH = 200
        private val TRACK_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,200}")
    }
}

private fun SpotifyTrack.toSongWire(cacheRevision: String?): SongWire =
    SongWire(
        id = id,
        name = name,
        href = href,
        uri = uri,
        album = AlbumWire(albumId, albumName, albumHref, albumUri, releaseDate),
        artists = artists.map { ArtistWire(it.id, it.name, it.href, it.uri) },
        durationMs = durationMs,
        explicit = explicit,
        available = available,
        cacheRevision = cacheRevision,
    )

private fun OperationRecord.toWire(): OperationWire =
    OperationWire(
        id = id,
        type = type,
        status = status.name.lowercase(),
        createdAt = createdAt.toString(),
        finishedAt = finishedAt?.toString(),
        result = result?.let { apiJson.parseToJsonElement(it) },
        error = errorCode?.let { ErrorEnvelope(it, errorMessage ?: "Operation failed", "operation-$id") },
    )
