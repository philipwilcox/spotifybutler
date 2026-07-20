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
import com.philipwilcox.spotifybutler.db.StoredPlaylistItem
import com.philipwilcox.spotifybutler.service.PlaylistDefinition
import com.philipwilcox.spotifybutler.service.PlaylistQueries
import com.philipwilcox.spotifybutler.service.SpotifyCacheService
import com.philipwilcox.spotifybutler.spotify.SpotifyApiClient
import com.philipwilcox.spotifybutler.spotify.SpotifyAuthClient
import com.philipwilcox.spotifybutler.spotify.SpotifyTrack
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.encodeToString
import java.net.HttpURLConnection
import java.security.MessageDigest
import java.time.Clock
import java.time.Year
import java.util.Base64

data class PlaylistRemoteState(
    val trackIds: List<String>,
)

interface PlaylistSyncGateway {
    fun current(
        accessToken: String,
        playlistId: String,
    ): PlaylistRemoteState

    fun replaceTracks(
        accessToken: String,
        playlistId: String,
        trackIds: List<String>,
    )
}

class SpotifyPlaylistSyncGateway(
    private val apiClient: SpotifyApiClient,
) : PlaylistSyncGateway {
    override fun current(
        accessToken: String,
        playlistId: String,
    ): PlaylistRemoteState =
        apiClient.getPlaylistCurrent(accessToken, playlistId).let { PlaylistRemoteState(it.trackIds) }

    override fun replaceTracks(
        accessToken: String,
        playlistId: String,
        trackIds: List<String>,
    ) = apiClient.replaceTrackIds(accessToken, playlistId, trackIds)
}

class ApiApplication(
    private val cacheService: SpotifyCacheService,
    private val store: SpotifyStore,
    private val sessionStore: SessionStore,
    private val syncGateway: PlaylistSyncGateway,
    private val clock: Clock = Clock.systemUTC(),
    private val trustedOrigins: Set<String> = emptySet(),
    private val authClient: SpotifyAuthClient? = null,
    private val allowedSpotifyUserId: String? = null,
    private val secureCookies: Boolean = false,
) {
    private val logger = KotlinLogging.logger {}
    private val refreshLocks = KeyedLock()

    constructor(
        cacheService: SpotifyCacheService,
        store: SpotifyStore,
        sessionStore: SessionStore,
        apiClient: SpotifyApiClient,
        clock: Clock = Clock.systemUTC(),
        trustedOrigins: Set<String> = emptySet(),
        authClient: SpotifyAuthClient? = null,
        allowedSpotifyUserId: String? = null,
        secureCookies: Boolean = false,
    ) : this(
        cacheService,
        store,
        sessionStore,
        SpotifyPlaylistSyncGateway(apiClient),
        clock,
        trustedOrigins,
        authClient,
        allowedSpotifyUserId,
        secureCookies,
    )

    fun handle(request: ApiRequest): ApiResponse {
        val requestId = request.headers.header("X-Request-Id")?.takeIf(::validRequestId) ?: newRequestId()
        return try {
            require(request.path.startsWith("/api/v1/")) {
                ApiFailure(HttpURLConnection.HTTP_NOT_FOUND, "not_found", "Route not found")
            }
            val session = requireSession(request)
            route(request, session).withRequestId(requestId)
        } catch (failure: ApiFailure) {
            errorResponse(failure, requestId).withRequestId(requestId)
        } catch (exception: IllegalArgumentException) {
            errorResponse(
                ApiFailure(
                    HttpURLConnection.HTTP_BAD_REQUEST,
                    "malformed_request",
                    exception.message ?: "Invalid request",
                ),
                requestId,
            ).withRequestId(requestId)
        } catch (exception: Exception) {
            logger.error(exception) { "API request failed path=${request.path}" }
            errorResponse(
                ApiFailure(
                    HttpURLConnection.HTTP_INTERNAL_ERROR,
                    "internal_error",
                    "The request could not be completed",
                ),
                requestId,
            ).withRequestId(requestId)
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
            parts.size == 4 && parts.take(3) == listOf("api", "v1", "playlists") && request.method == "PUT" ->
                updatePlaylist(parts[3], request, session)
            parts.size == 5 &&
                parts.take(3) == listOf("api", "v1", "playlists") &&
                parts[4] == "current" &&
                request.method == "GET" -> current(parts[3], session)
            parts.size == 5 &&
                parts.take(3) == listOf("api", "v1", "playlists") &&
                parts[4] == "syncs" &&
                request.method == "POST" -> sync(parts[3], request, session)
            parts == listOf("api", "v1", "library") && request.method == "GET" -> library(session)
            parts == listOf("api", "v1", "library", "refresh") && request.method == "POST" -> refresh(request, session)
            parts == listOf("api", "v1", "songs") && request.method == "GET" -> songs(request, session)
            parts.size == 4 && parts.take(3) == listOf("api", "v1", "songs") && request.method == "GET" ->
                song(parts[3], session)
            parts == listOf("api", "v1", "run") && request.method == "POST" -> run(request, session)
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
        val client =
            authClient
                ?: throw ApiFailure(
                    HttpURLConnection.HTTP_NOT_IMPLEMENTED,
                    "refresh_unavailable",
                    "Session refresh is not configured",
                )
        val rotated =
            try {
                sessionStore.refresh(session) { refreshToken ->
                    client.refreshAccessToken(refreshToken).let { it.accessToken to it.refreshToken }
                }
            } catch (exception: Exception) {
                throw ApiFailure(
                    HttpURLConnection.HTTP_BAD_GATEWAY,
                    "spotify_auth_failure",
                    "Spotify session refresh failed",
                )
            }
        return ApiResponse(
            HttpURLConnection.HTTP_OK,
            apiJson.encodeToString(
                SessionWire(rotated.ownerSpotifyUserId, rotated.csrfToken, rotated.expiresAt.toString()),
            ),
            mapOf(
                "Content-Type" to "application/json; charset=utf-8",
                "Set-Cookie" to sessionCookie(rotated.id),
            ),
        )
    }

    private fun playlists(session: ButlerSession): ApiResponse {
        requireCacheOwner(session)
        val definitions = builtInDefinitions() + userDefinitions(session.ownerSpotifyUserId)
        val response = PlaylistListWire(definitions.map { definition -> playlistReference(definition, session) })
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
            UserPlaylistDefinition(
                id,
                session.ownerSpotifyUserId,
                input.name,
                input.trackIds.toList(),
            )
        store.saveUserPlaylistDefinition(definition.toStored())
        return json(
            HttpURLConnection.HTTP_CREATED,
            playlistReference(definition.asView(), session),
        )
    }

    private fun playlist(
        definitionId: String,
        session: ButlerSession,
    ): ApiResponse {
        val definition = definition(definitionId, session.ownerSpotifyUserId)
        return json(HttpURLConnection.HTTP_OK, playlistReference(definition, session))
    }

    private fun updatePlaylist(
        definitionId: String,
        request: ApiRequest,
        session: ButlerSession,
    ): ApiResponse {
        requireStateChange(request, session)
        val existing =
            store.userPlaylistDefinition(definitionId, session.ownerSpotifyUserId)
                ?: throw ApiFailure(HttpURLConnection.HTTP_NOT_FOUND, "not_found", "User playlist definition not found")
        val input = body<CreatePlaylistRequest>(request)
        require(input.name.isNotBlank()) { "name must not be blank" }
        require(input.name.length <= MAX_NAME_LENGTH) { "name is too long" }
        validateTrackIds(input.trackIds)
        val updated =
            existing.copy(
                name = input.name,
                trackIds = input.trackIds.toList(),
            )
        store.saveUserPlaylistDefinition(updated)
        return json(
            HttpURLConnection.HTTP_OK,
            playlistReference(updated.toView(), session),
        )
    }

    private fun current(
        definitionId: String,
        session: ButlerSession,
    ): ApiResponse {
        requireCacheOwner(session)
        val definition = definition(definitionId, session.ownerSpotifyUserId)
        store.cacheMetadata() ?: return json(HttpURLConnection.HTTP_OK, PlaylistCurrentEnvelopeWire(null))
        val playlistId =
            resolvePlaylistId(definition, session)
                ?: return json(HttpURLConnection.HTTP_OK, PlaylistCurrentEnvelopeWire(null))
        val trackIds =
            store
                .playlistItems(
                    playlistId,
                ).filter(StoredPlaylistItem::isPlayableCurrentTrack)
                .map { it.itemId!! }
        return json(
            HttpURLConnection.HTTP_OK,
            PlaylistCurrentEnvelopeWire(
                PlaylistCurrentWire(playlistId, trackIds),
            ),
        )
    }

    private fun library(session: ButlerSession): ApiResponse {
        requireCacheOwner(session)
        val metadata = store.cacheMetadata()
        val status = metadata?.completionState ?: "empty"
        return json(
            HttpURLConnection.HTTP_OK,
            LibraryWire(
                status = status,
                ownerId = metadata?.ownerSpotifyUserId,
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
                ?.map(String::trim)
                ?.filter(String::isNotBlank)
                .orEmpty()
        require(ids.isNotEmpty()) { "ids must contain at least one track ID" }
        require(ids.size <= MAX_SONG_IDS) { "ids contains too many track IDs" }
        val tracksById = store.songEnrichment(ids).associateBy { it.id }
        val tracks = ids.mapNotNull(tracksById::get)
        return json(
            HttpURLConnection.HTTP_OK,
            SongsWire(
                items = tracks.map { it.toSongWire() },
                missingIds = ids.distinct().filterNot(tracksById::containsKey),
            ),
        )
    }

    private fun song(
        trackId: String,
        session: ButlerSession,
    ): ApiResponse {
        requireCacheOwner(session)
        val track =
            store.songEnrichment(trackId)
                ?: throw ApiFailure(HttpURLConnection.HTTP_NOT_FOUND, "song_not_found", "Song not found")
        return json(HttpURLConnection.HTTP_OK, track.toSongWire())
    }

    private fun refresh(
        request: ApiRequest,
        session: ButlerSession,
    ): ApiResponse = refreshLibrary(request, session, "Library refresh failed")

    private fun run(
        request: ApiRequest,
        session: ButlerSession,
    ): ApiResponse = refreshLibrary(request, session, "Legacy run failed")

    private fun refreshLibrary(
        request: ApiRequest,
        session: ButlerSession,
        failureMessage: String,
    ): ApiResponse {
        requireStateChange(request, session)
        val initialRevision = store.cacheMetadata()?.revision
        return refreshLocks.withLock(session.ownerSpotifyUserId) {
            if (store.cacheMetadata()?.revision != initialRevision) return@withLock library(session)
            store.markCacheRefreshing(session.ownerSpotifyUserId)
            try {
                cacheService.loadIfNeeded(
                    session.accessToken,
                    refresh = true,
                    ownerSpotifyUserId = session.ownerSpotifyUserId,
                )
            } catch (exception: Exception) {
                store.markCacheStale()
                throw ApiFailure(HttpURLConnection.HTTP_BAD_GATEWAY, "spotify_failure", failureMessage)
            }
            library(session)
        }
    }

    private fun sync(
        definitionId: String,
        request: ApiRequest,
        session: ButlerSession,
    ): ApiResponse {
        requireStateChange(request, session)
        requireCacheOwner(session)
        val input = body<SyncPlaylistRequest>(request)
        store.cacheMetadata()
            ?: throw ApiFailure(HttpURLConnection.HTTP_CONFLICT, "cache_not_ready", "The library cache is not ready")
        val definition = definition(definitionId, session.ownerSpotifyUserId)
        val playlistId =
            resolvePlaylistId(definition, session)
                ?: throw ApiFailure(HttpURLConnection.HTTP_CONFLICT, "mapping_missing", "The playlist is not mapped")
        validateTrackIds(input.trackIds)
        spotifyCall { syncGateway.replaceTracks(session.accessToken, playlistId, input.trackIds) }
        val authoritative = spotifyCall { syncGateway.current(session.accessToken, playlistId) }
        store.publishPlaylistTrackIds(
            playlistId,
            authoritative.trackIds,
            clock.millis(),
        )
        return json(
            HttpURLConnection.HTTP_OK,
            PlaylistCurrentEnvelopeWire(PlaylistCurrentWire(playlistId, authoritative.trackIds)),
        )
    }

    private fun definition(
        id: String,
        ownerSpotifyUserId: String,
    ): DefinitionView =
        store.userPlaylistDefinition(id, ownerSpotifyUserId)?.toView()
            ?: builtInDefinitions().firstOrNull { it.id == id }
            ?: throw ApiFailure(HttpURLConnection.HTTP_NOT_FOUND, "not_found", "Playlist definition not found")

    private fun userDefinitions(ownerSpotifyUserId: String): List<DefinitionView> =
        store.userPlaylistDefinitions(ownerSpotifyUserId).map { it.toView() }

    private fun com.philipwilcox.spotifybutler.db.StoredUserPlaylistDefinition.toView(): DefinitionView =
        DefinitionView(id, name, null, trackIds)

    private fun builtInDefinitions(): List<DefinitionView> =
        PlaylistQueries.definitions(Year.now(clock).value, MIN_DISCOVER_WEEKLY_YEAR).map { definition ->
            DefinitionView(definition.id.name, definition.name, definition, emptyList())
        }

    private fun playlistReference(
        definition: DefinitionView,
        session: ButlerSession,
    ): PlaylistReferenceWire {
        val mapping = resolvePlaylistId(definition, session)
        return PlaylistReferenceWire(
            id = definition.id,
            name = definition.name,
            state = if (mapping == null) "unmapped" else "mapped",
            spotifyPlaylistId = mapping,
            trackIds = definition.trackIds,
        )
    }

    private fun resolvePlaylistId(
        definition: DefinitionView,
        session: ButlerSession,
    ): String? {
        store.managedPlaylist(definition.id, session.ownerSpotifyUserId)?.let { return it.spotifyPlaylistId }
        val matches =
            store.playlistMatchesByName(definition.name).filter { match ->
                store.playlistDetails(match.id)?.ownerId == session.ownerSpotifyUserId
            }
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
        store.saveManagedPlaylist(definition.id, cachedId, session.ownerSpotifyUserId)
        return cachedId
    }

    private fun requireSession(request: ApiRequest): ButlerSession {
        val session =
            sessionStore.find(request.headers.cookie("butler_session"))
                ?: throw ApiFailure(HttpURLConnection.HTTP_UNAUTHORIZED, "unauthorized", "A Butler session is required")
        if (allowedSpotifyUserId != null && session.ownerSpotifyUserId != allowedSpotifyUserId) {
            throw ApiFailure(
                HttpURLConnection.HTTP_FORBIDDEN,
                "user_not_allowed",
                "This Spotify account is not allowed",
            )
        }
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
        if (trustedOrigins.isEmpty() || origin !in trustedOrigins) {
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

    private fun sessionCookie(sessionId: String): String =
        buildString {
            append("butler_session=$sessionId; Path=/; Max-Age=43200; SameSite=Lax; HttpOnly")
            if (secureCookies) append("; Secure")
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

    private fun fingerprint(body: String): String =
        MessageDigest.getInstance("SHA-256").digest(body.toByteArray()).joinToString("") {
            "%02x".format(it)
        }

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

    private inline fun <T> spotifyCall(action: () -> T): T =
        try {
            action()
        } catch (exception: Exception) {
            logger.warn(exception) { "Spotify playlist synchronization failed" }
            throw ApiFailure(HttpURLConnection.HTTP_BAD_GATEWAY, "spotify_failure", "Playlist synchronization failed")
        }

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
        val ownerSpotifyUserId: String,
        val name: String,
        val trackIds: List<String>,
    ) {
        fun asView(): DefinitionView = DefinitionView(id, name, null, trackIds)

        fun toStored() =
            com.philipwilcox.spotifybutler.db.StoredUserPlaylistDefinition(
                id,
                ownerSpotifyUserId,
                name,
                trackIds,
            )
    }

    private data class ApiFailure(
        val status: Int,
        val code: String,
        override val message: String,
        val details: Map<String, String> = emptyMap(),
    ) : RuntimeException(message)

    companion object {
        private const val MIN_DISCOVER_WEEKLY_YEAR = 2018
        private const val MAX_SONG_IDS = 50
        private const val MAX_PLAYLIST_TRACKS = 5000
        private const val MAX_NAME_LENGTH = 200
        private val TRACK_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,200}")
    }
}

private fun StoredPlaylistItem.isPlayableCurrentTrack(): Boolean =
    isPlayable &&
        !isLocal &&
        itemType == "track" &&
        status == "playable" &&
        !itemId.isNullOrBlank() &&
        itemUri?.startsWith("spotify:track:") == true

private fun SpotifyTrack.toSongWire(): SongWire =
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
    )

private fun com.philipwilcox.spotifybutler.db.StoredSong.toSongWire(): SongWire =
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
    )

private fun ApiResponse.withRequestId(requestId: String): ApiResponse =
    copy(headers = headers + ("X-Request-Id" to requestId))
