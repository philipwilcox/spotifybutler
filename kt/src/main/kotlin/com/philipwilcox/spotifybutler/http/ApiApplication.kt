@file:Suppress(
    "TooManyFunctions",
    "LargeClass",
    "CyclomaticComplexMethod",
    "TooGenericExceptionCaught",
    "MagicNumber",
    "SwallowedException",
    "ThrowsCount",
)

package com.philipwilcox.spotifybutler.http

import com.philipwilcox.spotifybutler.db.SpotifyStore
import com.philipwilcox.spotifybutler.db.StoredPlaylistItem
import com.philipwilcox.spotifybutler.db.StoredSong
import com.philipwilcox.spotifybutler.db.StoredUserPlaylistDefinition
import com.philipwilcox.spotifybutler.service.AuthoritativePlaylistState
import com.philipwilcox.spotifybutler.service.CacheSourceKey
import com.philipwilcox.spotifybutler.service.DestinationConflictException
import com.philipwilcox.spotifybutler.service.LibraryViewService
import com.philipwilcox.spotifybutler.service.MissingDestinationException
import com.philipwilcox.spotifybutler.service.OperationProgress
import com.philipwilcox.spotifybutler.service.OwnerMismatchException
import com.philipwilcox.spotifybutler.service.PlaylistDefinitionView
import com.philipwilcox.spotifybutler.service.PlaylistDestinationGateway
import com.philipwilcox.spotifybutler.service.PlaylistDestinationService
import com.philipwilcox.spotifybutler.service.PlaylistPreview
import com.philipwilcox.spotifybutler.service.PlaylistPreviewService
import com.philipwilcox.spotifybutler.service.PlaylistRecipeCodec
import com.philipwilcox.spotifybutler.service.PublishAction
import com.philipwilcox.spotifybutler.service.SpotifyCacheService
import com.philipwilcox.spotifybutler.spotify.SpotifyApiClient
import com.philipwilcox.spotifybutler.spotify.SpotifyAuthClient
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.time.Clock
import java.time.Duration
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors

data class PlaylistRemoteState(
    val trackIds: List<String>,
    val snapshotId: String? = null,
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

    fun replaceTracksAuthoritative(
        accessToken: String,
        playlistId: String,
        trackIds: List<String>,
    ): PlaylistRemoteState {
        replaceTracks(accessToken, playlistId, trackIds)
        return current(accessToken, playlistId)
    }

    fun createPlaylist(
        accessToken: String,
        name: String,
    ): String = error("Playlist creation is not supported")

    fun ownsPlaylist(
        accessToken: String,
        playlistId: String,
    ): Boolean = true

    fun ownsPlaylist(
        accessToken: String,
        playlistId: String,
        ownerSpotifyUserId: String,
    ): Boolean = ownsPlaylist(accessToken, playlistId)
}

interface SpotifySessionAuthenticator {
    fun refresh(refreshToken: String): TokenRefreshResult

    fun currentUserId(accessToken: String): String
}

class SpotifyPlaylistSyncGateway(
    private val apiClient: SpotifyApiClient,
) : PlaylistSyncGateway {
    override fun current(
        accessToken: String,
        playlistId: String,
    ) = apiClient.getPlaylistCurrent(accessToken, playlistId).let { PlaylistRemoteState(it.trackIds, it.snapshotId) }

    override fun replaceTracks(
        accessToken: String,
        playlistId: String,
        trackIds: List<String>,
    ) = apiClient.replaceTrackIds(accessToken, playlistId, trackIds)

    override fun replaceTracksAuthoritative(
        accessToken: String,
        playlistId: String,
        trackIds: List<String>,
    ) = apiClient.replaceTrackIdsAuthoritative(accessToken, playlistId, trackIds).let {
        PlaylistRemoteState(it.trackIds, it.snapshotId)
    }

    override fun createPlaylist(
        accessToken: String,
        name: String,
    ) = apiClient
        .createPlaylistMetadata(
            accessToken,
            name,
        ).id

    override fun ownsPlaylist(
        accessToken: String,
        playlistId: String,
        ownerSpotifyUserId: String,
    ) = apiClient.playlistOwnedByCurrentUser(accessToken, playlistId, ownerSpotifyUserId)
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
    private val spotifyUserIdLookup: ((String) -> String)? = null,
    private val sessionAuthenticator: SpotifySessionAuthenticator? = null,
    private val operationRegistry: OperationRegistry = OperationRegistry(),
) {
    private val logger = KotlinLogging.logger {}
    private val refreshLocks = KeyedLock()
    private val previewService = PlaylistPreviewService(store, clock)
    private val libraryService = LibraryViewService(store, previewService)
    private val destinationService = PlaylistDestinationService(store, gateway(), clock)
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
        operationRegistry: OperationRegistry = OperationRegistry(),
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
        { accessToken -> apiClient.getCurrentUser(accessToken).id },
        authClient?.let { client ->
            object : SpotifySessionAuthenticator {
                override fun refresh(refreshToken: String) =
                    client.refreshAccessToken(refreshToken).let {
                        TokenRefreshResult(it.accessToken, it.expiresInSeconds, it.refreshToken)
                    }

                override fun currentUserId(accessToken: String) = apiClient.getCurrentUser(accessToken).id
            }
        },
        operationRegistry,
    )

    private fun gateway() =
        object : PlaylistDestinationGateway {
            override fun create(
                accessToken: String,
                name: String,
            ) = syncGateway.createPlaylist(accessToken, name)

            override fun owns(
                accessToken: String,
                playlistId: String,
                ownerSpotifyUserId: String,
            ) = syncGateway.ownsPlaylist(accessToken, playlistId, ownerSpotifyUserId)

            override fun replace(
                accessToken: String,
                playlistId: String,
                trackIds: List<String>,
            ): AuthoritativePlaylistState =
                syncGateway.replaceTracksAuthoritative(accessToken, playlistId, trackIds).let {
                    AuthoritativePlaylistState(playlistId, it.trackIds, it.snapshotId)
                }

            override fun current(
                accessToken: String,
                playlistId: String,
            ) = syncGateway.current(accessToken, playlistId).let {
                AuthoritativePlaylistState(playlistId, it.trackIds, it.snapshotId)
            }
        }

    fun handle(request: ApiRequest): ApiResponse {
        val requestId = request.headers.header("X-Request-Id")?.takeIf(::validRequestId) ?: newRequestId()
        return try {
            require(request.path.startsWith("/api/v1/")) { ApiFailure(404, "not_found", "Route not found") }
            val resolution = resolveSession(request)
            route(request, resolution.session, requestId).withRequestId(requestId).let { response ->
                resolution.rotatedSessionId?.let { sessionId ->
                    response.copy(headers = response.headers + ("Set-Cookie" to sessionCookie(sessionId)))
                } ?: response
            }
        } catch (failure: ApiFailure) {
            errorResponse(failure, requestId).withRequestId(requestId)
        } catch (failure: MissingDestinationException) {
            errorResponse(
                ApiFailure(
                    409,
                    "destination_missing",
                    failure.message ?: "Destination is missing",
                ),
                requestId,
            ).withRequestId(requestId)
        } catch (failure: DestinationConflictException) {
            errorResponse(
                ApiFailure(
                    409,
                    "destination_conflict",
                    failure.message ?: "Destination has changed",
                ),
                requestId,
            ).withRequestId(requestId)
        } catch (failure: OwnerMismatchException) {
            errorResponse(
                ApiFailure(
                    403,
                    "owner_mismatch",
                    failure.message ?: "Playlist ownership failed",
                ),
                requestId,
            ).withRequestId(requestId)
        } catch (exception: IllegalArgumentException) {
            errorResponse(
                ApiFailure(
                    400,
                    "malformed_request",
                    exception.message ?: "Invalid request",
                ),
                requestId,
            ).withRequestId(requestId)
        } catch (
            exception: Exception,
        ) {
            logger.error(exception) { "API request failed" }
            errorResponse(
                ApiFailure(500, "internal_error", "The request could not be completed"),
                requestId,
            ).withRequestId(requestId)
        }
    }

    fun authorizeOperationSocket(
        request: ApiRequest,
        requestId: String,
    ): OperationSocketAuthorization =
        try {
            val resolution = resolveSession(request)
            requireTrustedOrigin(request)
            OperationSocketAuthorization.Accepted(resolution.session.ownerSpotifyUserId)
        } catch (failure: ApiFailure) {
            OperationSocketAuthorization.Rejected(errorResponse(failure, requestId).withRequestId(requestId))
        } catch (exception: Exception) {
            logger.error(exception) { "Operation socket authorization failed" }
            OperationSocketAuthorization.Rejected(
                errorResponse(ApiFailure(401, "unauthorized", "A Butler session is required"), requestId),
            )
        }

    private fun route(
        request: ApiRequest,
        session: ButlerSession,
        requestId: String,
    ): ApiResponse {
        val parts = request.path.trim('/').split('/')
        return when {
            parts == listOf("api", "v1", "session") && request.method == "GET" -> session(session)
            parts == listOf("api", "v1", "session") && request.method == "DELETE" -> deleteSession(request, session)
            parts ==
                listOf(
                    "api",
                    "v1",
                    "session",
                    "refresh",
                ) &&
                request.method == "POST" -> refreshSession(request, session)
            parts == listOf("api", "v1", "library") && request.method == "GET" -> library(session)
            parts.size == 5 &&
                parts.take(
                    4,
                ) == listOf("api", "v1", "library", "playlists") &&
                request.method == "GET" ->
                libraryPlaylist(parts[4], session)
            parts.size == 6 &&
                parts.take(4) == listOf("api", "v1", "library", "playlists") &&
                parts[5] == "publish" &&
                request.method == "POST" -> libraryPlaylistPublish(parts[4], request, session, requestId)
            parts ==
                listOf(
                    "api",
                    "v1",
                    "library",
                    "refresh",
                ) &&
                request.method == "POST" -> refresh(request, session, requestId)
            parts == listOf("api", "v1", "playlists") && request.method == "GET" -> definitions(session)
            parts == listOf("api", "v1", "playlists") && request.method == "POST" -> createDefinition(request, session)
            parts == listOf("api", "v1", "playlists", "bulk-republish-plan") && request.method == "POST" ->
                bulkRepublishPlan(request, session, requestId)
            parts == listOf("api", "v1", "playlists", "bulk-republish") && request.method == "POST" ->
                bulkRepublish(request, session, requestId)
            parts.size == 4 &&
                parts.take(
                    3,
                ) == listOf("api", "v1", "playlists") &&
                request.method == "GET" -> definition(parts[3], session)
            parts.size == 4 &&
                parts.take(
                    3,
                ) ==
                listOf(
                    "api",
                    "v1",
                    "playlists",
                ) &&
                request.method == "PUT" -> updateDefinition(parts[3], request, session)
            parts.size == 5 &&
                parts.take(3) == listOf("api", "v1", "playlists") &&
                parts[4] == "recipe-settings" &&
                request.method == "PUT" -> updateRecipeSettings(parts[3], request, session)
            parts.size == 5 &&
                parts.take(3) == listOf("api", "v1", "playlists") &&
                parts[4] == "preview" &&
                request.method == "GET" -> preview(parts[3], request, session)
            parts.size == 5 &&
                parts.take(3) == listOf("api", "v1", "playlists") &&
                parts[4] == "publish-plan" &&
                request.method == "POST" -> publishPlan(parts[3], request, session, requestId)
            parts.size == 5 &&
                parts.take(3) == listOf("api", "v1", "playlists") &&
                parts[4] == "publish" &&
                request.method == "POST" -> publish(parts[3], request, session, requestId)
            parts.size == 5 &&
                parts.take(3) == listOf("api", "v1", "playlists") &&
                parts[4] == "current" &&
                request.method == "GET" -> current(parts[3], session)
            parts.size == 5 &&
                parts.take(3) == listOf("api", "v1", "playlists") &&
                parts[4] == "syncs" &&
                request.method == "POST" -> sync(parts[3], request, session, requestId)
            parts == listOf("api", "v1", "songs", "bulk") && request.method == "POST" -> bulkSongs(request, session)
            parts == listOf("api", "v1", "songs") && request.method == "GET" -> songs(request, session)
            else -> throw ApiFailure(404, "not_found", "Route not found")
        }
    }

    private fun session(session: ButlerSession) =
        json(200, SessionWire(session.ownerSpotifyUserId, session.csrfToken, session.expiresAt.toString()))

    private fun deleteSession(
        request: ApiRequest,
        session: ButlerSession,
    ): ApiResponse {
        requireStateChange(request, session)
        sessionStore.signOut(session)
        return ApiResponse(204, "", mapOf("Set-Cookie" to clearSessionCookie()))
    }

    @Suppress("SwallowedException", "TooGenericExceptionCaught")
    private fun refreshSession(
        request: ApiRequest,
        session: ButlerSession,
    ): ApiResponse {
        requireStateChange(request, session)
        val client = authClient ?: throw ApiFailure(501, "refresh_unavailable", "Session refresh is not configured")
        val rotated =
            try {
                sessionStore.refreshWithResult(session) { refreshToken ->
                    client.refreshAccessToken(refreshToken).let {
                        TokenRefreshResult(it.accessToken, it.expiresInSeconds, it.refreshToken)
                    }
                }
            } catch (exception: SpotifyAuthClient.SpotifyAuthException) {
                if (exception.spotifyError == "invalid_grant") sessionStore.invalidate(session.ownerSpotifyUserId)
                throw ApiFailure(
                    401,
                    if (exception.spotifyError ==
                        "invalid_grant"
                    ) {
                        "reauthorization_required"
                    } else {
                        "spotify_auth_failure"
                    },
                    "Spotify authorization is no longer available",
                    clearSessionCookie = exception.spotifyError == "invalid_grant",
                )
            } catch (
                exception: Exception,
            ) {
                throw ApiFailure(502, "spotify_auth_failure", "Spotify session refresh failed")
            }
        return ApiResponse(
            200,
            apiJson.encodeToString(
                SessionWire(rotated.ownerSpotifyUserId, rotated.csrfToken, rotated.expiresAt.toString()),
            ),
            mapOf(
                "Content-Type" to "application/json; charset=utf-8",
                "Set-Cookie" to sessionCookie(rotated.id),
            ),
        )
    }

    private fun library(session: ButlerSession): ApiResponse = json(200, libraryWire(session))

    private fun libraryPlaylist(
        playlistId: String,
        session: ButlerSession,
    ): ApiResponse =
        try {
            json(200, libraryService.playlist(session.ownerSpotifyUserId, playlistId).toWire())
        } catch (_: com.philipwilcox.spotifybutler.service.LibraryPlaylistNotFoundException) {
            throw ApiFailure(404, "not_found", "Library playlist not found")
        }

    private fun libraryPlaylistPublish(
        playlistId: String,
        request: ApiRequest,
        session: ButlerSession,
        requestId: String,
    ): ApiResponse {
        requireStateChange(request, session)
        val playlist =
            try {
                libraryService.playlist(session.ownerSpotifyUserId, playlistId)
            } catch (_: com.philipwilcox.spotifybutler.service.LibraryPlaylistNotFoundException) {
                throw ApiFailure(404, "not_found", "Library playlist not found")
            }
        if (!playlist.summary.editable) {
            throw ApiFailure(403, "owner_mismatch", "The playlist does not belong to the authenticated owner")
        }
        val input = body<PublishLibraryPlaylistRequest>(request)
        validateTrackIds(input.trackIds, session.ownerSpotifyUserId)
        val totalSteps = 1 + ((input.trackIds.size + 99) / 100).coerceAtLeast(1)
        return accepted(session, OperationKind.library_playlist_publish, requestId, totalSteps) {
            destinationService.publishLibraryPlaylist(
                playlistId,
                session.ownerSpotifyUserId,
                session.accessToken,
                input.trackIds,
            )
            LibraryPlaylistPublishResultWire(
                libraryService.playlist(session.ownerSpotifyUserId, playlistId).toWire(),
            )
        }
    }

    @Suppress("SwallowedException", "TooGenericExceptionCaught")
    private fun refresh(
        request: ApiRequest,
        session: ButlerSession,
        requestId: String,
    ): ApiResponse {
        requireStateChange(request, session)
        val input = request.body?.let { body<RefreshLibraryRequest>(request) } ?: RefreshLibraryRequest()
        try {
            input.sourceKeys?.forEach { CacheSourceKey.root(session.ownerSpotifyUserId, it) }
        } catch (exception: IllegalArgumentException) {
            throw ApiFailure(400, "invalid_source_keys", exception.message ?: "Unsupported source key")
        }
        return accepted(
            session,
            OperationKind.library_refresh,
            requestId,
            totalSteps = null,
        ) {
            refreshLocks.withLock(session.ownerSpotifyUserId) {
                cacheService.refreshSources(session.ownerSpotifyUserId, session.accessToken, input.sourceKeys?.toSet())
                LibraryRefreshResultWire(libraryWire(session))
            }
        }
    }

    private fun definitions(session: ButlerSession): ApiResponse =
        json(
            200,
            DefinitionListWire(
                previewService.definitions(session.ownerSpotifyUserId).map {
                    definitionWire(it, session.ownerSpotifyUserId)
                },
            ),
        )

    private fun createDefinition(
        request: ApiRequest,
        session: ButlerSession,
    ): ApiResponse {
        requireStateChange(request, session)
        val input = body<CreateDefinitionRequest>(request)
        validateDefinitionInput(input.name, input.description)
        val definition =
            StoredUserPlaylistDefinition(
                "owner-" + UUID.randomUUID(),
                session.ownerSpotifyUserId,
                input.name,
                emptyList(),
                input.description,
                input.enabled,
                PlaylistRecipeCodec.canonicalize(input.recipe),
            )
        store.saveUserPlaylistDefinition(definition)
        return json(
            201,
            definitionWire(
                previewService.resolve(definition.id, session.ownerSpotifyUserId),
                session.ownerSpotifyUserId,
            ),
        )
    }

    private fun definition(
        definitionId: String,
        session: ButlerSession,
    ): ApiResponse =
        json(
            200,
            definitionWire(resolveDefinition(definitionId, session.ownerSpotifyUserId), session.ownerSpotifyUserId),
        )

    private fun updateDefinition(
        definitionId: String,
        request: ApiRequest,
        session: ButlerSession,
    ): ApiResponse {
        requireStateChange(request, session)
        val current =
            store.userPlaylistDefinition(definitionId, session.ownerSpotifyUserId)
                ?: throw ApiFailure(403, "definition_read_only", "Built-in definitions cannot be edited")
        val input = body<CreateDefinitionRequest>(request)
        validateDefinitionInput(input.name, input.description)
        store.saveUserPlaylistDefinition(
            current.copy(
                name = input.name,
                description = input.description,
                enabled = input.enabled,
                recipe = PlaylistRecipeCodec.canonicalize(input.recipe),
            ),
        )
        return json(
            200,
            definitionWire(resolveDefinition(definitionId, session.ownerSpotifyUserId), session.ownerSpotifyUserId),
        )
    }

    private fun updateRecipeSettings(
        definitionId: String,
        request: ApiRequest,
        session: ButlerSession,
    ): ApiResponse {
        requireStateChange(request, session)
        val input = body<UpdateRecipeSettingsRequest>(request)
        val definition =
            try {
                previewService.updateShuffleAfterGeneration(
                    definitionId,
                    session.ownerSpotifyUserId,
                    input.shuffleAfterGeneration,
                )
            } catch (exception: IllegalArgumentException) {
                throw ApiFailure(404, "not_found", "Playlist definition not found")
            }
        return json(200, definitionWire(definition, session.ownerSpotifyUserId))
    }

    private fun preview(
        definitionId: String,
        request: ApiRequest,
        session: ButlerSession,
    ): ApiResponse =
        json(200, previewService.preview(definitionId, session.ownerSpotifyUserId, request.query["seed"]).toWire())

    private fun publishPlan(
        definitionId: String,
        request: ApiRequest,
        session: ButlerSession,
        requestId: String,
    ): ApiResponse {
        requireStateChange(request, session)
        if (destinationService.current(definitionId, session.ownerSpotifyUserId) != null) {
            throw ApiFailure(409, "destination_exists", "The definition already has a destination")
        }
        val definition = resolveDefinition(definitionId, session.ownerSpotifyUserId)
        val flowId = UUID.randomUUID().toString()
        return accepted(session, OperationKind.publish_plan, requestId, totalSteps = null) {
            refreshLocks.withLock(session.ownerSpotifyUserId) {
                cacheService.refreshSource(
                    session.ownerSpotifyUserId,
                    session.accessToken,
                    CacheSourceKey.PLAYLISTS,
                )
                val plan =
                    destinationService
                        .planPublish(definitionId, session.ownerSpotifyUserId, definition.name)
                        .copy(publishFlowId = flowId)
                PublishPlanResultWire(plan.toWire())
            }
        }
    }

    private fun bulkRepublishPlan(
        request: ApiRequest,
        session: ButlerSession,
        requestId: String,
    ): ApiResponse {
        requireStateChange(request, session)
        return accepted(session, OperationKind.BULK_REPUBLISH_PLAN, requestId, totalSteps = null) {
            refreshLocks.withLock(session.ownerSpotifyUserId) {
                cacheService.refreshSource(session.ownerSpotifyUserId, session.accessToken, CacheSourceKey.PLAYLISTS)
                val items =
                    previewService
                        .definitions(session.ownerSpotifyUserId)
                        .filter(PlaylistDefinitionView::enabled)
                        .map { definition -> bulkPlanItem(definition, session.ownerSpotifyUserId) }
                BulkRepublishPlanResultWire(BulkRepublishPlanWire(items))
            }
        }
    }

    private fun bulkRepublish(
        request: ApiRequest,
        session: ButlerSession,
        requestId: String,
    ): ApiResponse {
        requireStateChange(request, session)
        val input = body<BulkRepublishRequest>(request)
        val definitions = previewService.definitions(session.ownerSpotifyUserId).filter(PlaylistDefinitionView::enabled)
        val definitionsById = definitions.associateBy(PlaylistDefinitionView::definitionId)
        require(input.items.isNotEmpty()) { "items must contain at least one enabled definition" }
        require(
            input.items
                .map(BulkRepublishChoiceWire::definitionId)
                .distinct()
                .size == input.items.size,
        ) {
            "items must not contain duplicate definitions"
        }
        val jobs =
            input.items.map { choice ->
                val definition = definitionsById[choice.definitionId] ?: error("Definition is not enabled")
                BulkRepublishJob(definition, choice)
            }
        return accepted(session, OperationKind.BULK_REPUBLISH, requestId, totalSteps = jobs.size) {
            executeBulkRepublish(jobs, session)
        }
    }

    private fun bulkPlanItem(
        definition: PlaylistDefinitionView,
        ownerSpotifyUserId: String,
    ): BulkRepublishPlanItemWire {
        if (destinationService.current(definition.definitionId, ownerSpotifyUserId) != null) {
            return BulkRepublishPlanItemWire(definition.definitionId, definition.name, "sync")
        }
        val plan = destinationService.planPublish(definition.definitionId, ownerSpotifyUserId, definition.name)
        return when (plan.action) {
            com.philipwilcox.spotifybutler.service.PublishPlanAction.CREATE ->
                BulkRepublishPlanItemWire(definition.definitionId, definition.name, "create")
            com.philipwilcox.spotifybutler.service.PublishPlanAction.ADOPT ->
                BulkRepublishPlanItemWire(
                    definition.definitionId,
                    definition.name,
                    "adopt",
                    plan.candidates.map(::publishCandidateWire),
                )
            com.philipwilcox.spotifybutler.service.PublishPlanAction.CHOOSE ->
                BulkRepublishPlanItemWire(
                    definition.definitionId,
                    definition.name,
                    "choose",
                    plan.candidates.map(::publishCandidateWire),
                )
            com.philipwilcox.spotifybutler.service.PublishPlanAction.BLOCKED ->
                BulkRepublishPlanItemWire(definition.definitionId, definition.name, "skipped", message = plan.message)
        }
    }

    private fun executeBulkRepublish(
        jobs: List<BulkRepublishJob>,
        session: ButlerSession,
    ): BulkRepublishResultWire {
        val rows =
            jobs
                .associate { job ->
                    job.definition.definitionId to
                        BulkRepublishItemWire(job.definition.definitionId, job.definition.name, "queued")
                }.toMutableMap()
        val lock = Any()
        // The parent operation owns the thread-local progress context; capture it for worker threads.
        val progress = OperationProgress.current()

        fun report(
            definitionId: String,
            row: BulkRepublishItemWire,
        ) {
            synchronized(lock) {
                rows[definitionId] = row
                val items = jobs.map { rows.getValue(it.definition.definitionId) }
                progress?.actionStarted(
                    "Republishing playlists",
                    BulkRepublishProgressWire(
                        items.count { it.phase in setOf("succeeded", "failed") },
                        items.size,
                        items,
                    ),
                )
            }
        }

        fun worker(job: BulkRepublishJob) {
            val definition = job.definition
            report(
                definition.definitionId,
                BulkRepublishItemWire(definition.definitionId, definition.name, "generating"),
            )
            try {
                val preview = previewService.preview(definition.definitionId, session.ownerSpotifyUserId)
                if (preview.status.name == "UNAVAILABLE") {
                    report(
                        definition.definitionId,
                        BulkRepublishItemWire(
                            definition.definitionId,
                            definition.name,
                            "failed",
                            message = preview.unavailableReason,
                        ),
                    )
                    return
                }
                val trackIds = preview.generatedTrackIds
                val totalSteps = ((trackIds.size + 99) / 100).coerceAtLeast(1)
                report(
                    definition.definitionId,
                    BulkRepublishItemWire(
                        definition.definitionId,
                        definition.name,
                        "publishing",
                        trackIds.size,
                        0,
                        totalSteps,
                    ),
                )
                when (job.choice.action.lowercase()) {
                    "sync" ->
                        destinationService.sync(
                            definition.definitionId,
                            session.ownerSpotifyUserId,
                            session.accessToken,
                            trackIds,
                        )
                    "create" ->
                        destinationService.publish(
                            definition.definitionId,
                            session.ownerSpotifyUserId,
                            session.accessToken,
                            definition.name,
                            PublishAction.CREATE,
                            null,
                            trackIds,
                        )
                    "adopt" ->
                        destinationService.publish(
                            definition.definitionId,
                            session.ownerSpotifyUserId,
                            session.accessToken,
                            definition.name,
                            PublishAction.ADOPT,
                            job.choice.spotifyPlaylistId,
                            trackIds,
                        )
                    else -> error("Bulk republish action must be sync, create, or adopt")
                }
                report(
                    definition.definitionId,
                    BulkRepublishItemWire(
                        definition.definitionId,
                        definition.name,
                        "succeeded",
                        trackIds.size,
                        totalSteps,
                        totalSteps,
                    ),
                )
            } catch (exception: Exception) {
                logger.warn(exception) { "Bulk republish item failed: definitionId=${definition.definitionId}" }
                report(
                    definition.definitionId,
                    BulkRepublishItemWire(
                        definition.definitionId,
                        definition.name,
                        "failed",
                        message = "Could not republish. Retry this playlist.",
                    ),
                )
            }
        }
        val executor = Executors.newFixedThreadPool(BULK_REPUBLISH_CONCURRENCY)
        try {
            executor.invokeAll(jobs.map { job -> Callable { worker(job) } }).forEach { it.get() }
        } finally {
            executor.shutdown()
        }
        val resultItems = jobs.map { rows.getValue(it.definition.definitionId) }
        progress?.actionStarted(
            "Completed",
            BulkRepublishProgressWire(
                resultItems.count {
                    it.phase in setOf("succeeded", "failed")
                },
                resultItems.size,
                resultItems,
            ),
        )
        return BulkRepublishResultWire(libraryWire(session), resultItems)
    }

    private fun publish(
        definitionId: String,
        request: ApiRequest,
        session: ButlerSession,
        requestId: String,
    ): ApiResponse {
        requireStateChange(request, session)
        val input = body<PublishDestinationRequest>(request)
        val action =
            runCatching { PublishAction.valueOf(input.action.uppercase()) }
                .getOrElse { throw ApiFailure(400, "invalid_publish", "Publish action must be create or adopt") }
        validateTrackIds(input.trackIds, session.ownerSpotifyUserId)
        val definition = resolveDefinition(definitionId, session.ownerSpotifyUserId)
        val kind = if (action == PublishAction.ADOPT) OperationKind.publish_adopt else OperationKind.publish_create
        val totalSteps = 1 + ((input.trackIds.size + 99) / 100).coerceAtLeast(1)
        return accepted(session, kind, requestId, totalSteps) {
            val destination =
                destinationService.publish(
                    definitionId,
                    session.ownerSpotifyUserId,
                    session.accessToken,
                    definition.name,
                    action,
                    input.spotifyPlaylistId,
                    input.trackIds,
                )
            PublishDestinationResultWire(destination.toWire(definitionId))
        }
    }

    private fun current(
        definitionId: String,
        session: ButlerSession,
    ): ApiResponse {
        resolveDefinition(definitionId, session.ownerSpotifyUserId)
        val destination =
            destinationService.current(definitionId, session.ownerSpotifyUserId)
                ?: return json(200, CurrentEnvelopeWire(null))
        val trackIds =
            store
                .playlistItems(
                    destination.spotifyPlaylistId,
                    session.ownerSpotifyUserId,
                ).filter(StoredPlaylistItem::isPlayableCurrentTrack)
                .mapNotNull {
                    it.itemId
                }
        return json(
            200,
            CurrentEnvelopeWire(
                CurrentWire(
                    destination.spotifyPlaylistId,
                    trackIds,
                    destination.lastSyncedAt?.toString(),
                    destination.lastSeenSnapshotId,
                ),
            ),
        )
    }

    private fun sync(
        definitionId: String,
        request: ApiRequest,
        session: ButlerSession,
        requestId: String,
    ): ApiResponse {
        requireStateChange(request, session)
        val input = body<SyncPlaylistRequest>(request)
        resolveDefinition(definitionId, session.ownerSpotifyUserId)
        if (destinationService.current(definitionId, session.ownerSpotifyUserId) == null) {
            throw ApiFailure(409, "destination_missing", "Destination is missing")
        }
        validateTrackIds(input.trackIds, session.ownerSpotifyUserId)
        val totalSteps = ((input.trackIds.size + 99) / 100).coerceAtLeast(1)
        return accepted(session, OperationKind.destination_sync, requestId, totalSteps) {
            val authoritative =
                destinationService.sync(
                    definitionId,
                    session.ownerSpotifyUserId,
                    session.accessToken,
                    input.trackIds,
                    input.expectedDestinationSnapshotId,
                )
            val destination = destinationService.current(definitionId, session.ownerSpotifyUserId)!!
            DestinationSyncResultWire(
                CurrentEnvelopeWire(
                    CurrentWire(
                        authoritative.spotifyPlaylistId,
                        authoritative.trackIds,
                        destination.lastSyncedAt?.toString(),
                        authoritative.snapshotId,
                    ),
                ),
            )
        }
    }

    private fun accepted(
        session: ButlerSession,
        kind: OperationKind,
        requestId: String,
        totalSteps: Int?,
        task: () -> OperationResultWire,
    ): ApiResponse =
        try {
            json(202, operationRegistry.start(session.ownerSpotifyUserId, kind, requestId, totalSteps, task))
        } catch (_: OperationAlreadyRunningException) {
            throw ApiFailure(409, "operation_in_progress", "Another operation is already in progress")
        }

    private fun songs(
        request: ApiRequest,
        session: ButlerSession,
    ): ApiResponse {
        val ids =
            request.query["ids"]
                ?.split(',')
                ?.map(String::trim)
                ?.filter(String::isNotBlank)
                .orEmpty()
        require(ids.isNotEmpty()) { "ids must contain at least one track ID" }
        require(ids.size <= 50) { "ids contains too many track IDs" }
        val tracksById = store.songEnrichment(ids, session.ownerSpotifyUserId).associateBy(StoredSong::id)
        return json(
            200,
            SongsWire(
                ids
                    .mapNotNull {
                        tracksById[it]
                    }.map(StoredSong::toSongWire),
                ids.distinct().filterNot(tracksById::containsKey),
            ),
        )
    }

    private fun bulkSongs(
        request: ApiRequest,
        session: ButlerSession,
    ): ApiResponse {
        requireStateChange(request, session)
        val ids =
            body<BulkSongsRequest>(request)
                .trackIds
                .map(String::trim)
                .filter(String::isNotBlank)
                .distinct()
        require(ids.isNotEmpty()) { "trackIds must contain at least one track ID" }
        require(ids.size <= MAX_BULK_SONG_IDS) { "trackIds contains too many entries" }
        val tracksById = store.songEnrichment(ids, session.ownerSpotifyUserId).associateBy(StoredSong::id)
        return json(
            200,
            SongsWire(
                ids.mapNotNull { tracksById[it] }.map(StoredSong::toSongWire),
                ids.filterNot(tracksById::containsKey),
            ),
        )
    }

    private fun libraryWire(session: ButlerSession): LibraryWire =
        libraryService.library(session.ownerSpotifyUserId).let { view ->
            logger.info {
                "Library API response: owner=${view.ownerSpotifyUserId} sources=${view.sources.size} " +
                    "definitions=${view.definitions.size} playlists=${view.playlists.size}"
            }
            LibraryWire(
                view.ownerSpotifyUserId,
                view.status.name.lowercase(),
                view.sources.map { it.toWire() },
                view.definitions.map { definitionWire(it, session.ownerSpotifyUserId) },
                view.playlists.map { it.toWire() },
            )
        }

    private fun resolveDefinition(
        id: String,
        owner: String,
    ): PlaylistDefinitionView =
        runCatching {
            previewService.resolve(id, owner)
        }.getOrElse { throw ApiFailure(404, "not_found", "Playlist definition not found") }

    private fun definitionWire(
        definition: PlaylistDefinitionView,
        owner: String,
    ): DefinitionWire =
        DefinitionWire(
            definition.definitionId,
            definition.name,
            definition.description,
            definition.kind.name
                .lowercase(),
            definition.editable,
            definition.enabled,
            definition.recipe,
            previewService
                .sourceDependencies(
                    definition,
                    owner,
                ).map {
                    it.toWire()
                },
            store
                .managedPlaylist(
                    definition.definitionId,
                    owner,
                )?.let { mapping ->
                    DestinationSummaryWire(
                        definition.definitionId,
                        mapping.spotifyPlaylistId,
                        java.time.Instant
                            .ofEpochMilli(mapping.createdAtMillis)
                            .toString(),
                        mapping.lastSyncedAtMillis?.let(java.time.Instant::ofEpochMilli)?.toString(),
                        mapping.lastSeenSnapshotId,
                        true,
                    )
                },
        )

    private fun validateDefinitionInput(
        name: String,
        description: String,
    ) {
        require(name.isNotBlank()) { "name must not be blank" }
        require(
            name.length <= 200,
        ) { "name is too long" }
        require(description.length <= 2000) { "description is too long" }
    }

    private fun validateTrackIds(
        trackIds: List<String>,
        owner: String,
    ) {
        require(trackIds.size <= 5000) { "trackIds contains too many entries" }
        require(trackIds.all { it.matches(TRACK_ID_PATTERN) }) { "trackIds must contain Spotify track IDs" }
        val known = store.songs(owner).associateBy { it.id }
        val invalid =
            trackIds.distinct().filter {
                known[it]?.available != true ||
                    !known[it]!!.uri.startsWith("spotify:track:")
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

    private fun resolveSession(request: ApiRequest): SessionResolution {
        val cookieSessionId = request.headers.cookie("butler_session")
        val existing = sessionStore.find(cookieSessionId)
        if (existing != null) {
            val refreshed = proactivelyRefresh(existing)
            checkAllowedUser(refreshed)
            return SessionResolution(refreshed, null)
        }
        if (request.method != "GET" || request.path != "/api/v1/session") {
            throw ApiFailure(401, "unauthorized", "A Butler session is required")
        }
        val authenticator = sessionAuthenticator
        if (authenticator == null && (authClient == null || spotifyUserIdLookup == null)) {
            throw ApiFailure(401, "unauthorized", "A Butler session is required")
        }
        val rehydrated =
            try {
                sessionStore.rehydrate(
                    cookieSessionId,
                    refresh = { refreshToken ->
                        refreshToken(refreshToken)
                    },
                    verifySpotifyUserId = { accessToken -> currentSpotifyUserId(accessToken) },
                )
            } catch (exception: SpotifyAuthClient.SpotifyAuthException) {
                if (exception.spotifyError == "invalid_grant") sessionStore.invalidateSession(cookieSessionId)
                throw ApiFailure(
                    401,
                    if (exception.spotifyError == "invalid_grant") "reauthorization_required" else "unauthorized",
                    "Spotify authorization is no longer available",
                    clearSessionCookie = true,
                )
            } catch (exception: IllegalArgumentException) {
                sessionStore.invalidateSession(cookieSessionId)
                throw ApiFailure(
                    401,
                    "reauthorization_required",
                    "Spotify authorization is no longer available",
                    clearSessionCookie = true,
                )
            } catch (exception: Exception) {
                throw ApiFailure(502, "spotify_auth_failure", "Spotify session rehydration failed")
            }
                ?: throw ApiFailure(401, "unauthorized", "A Butler session is required", clearSessionCookie = true)
        checkAllowedUser(rehydrated)
        return SessionResolution(rehydrated, rehydrated.id)
    }

    private fun proactivelyRefresh(session: ButlerSession): ButlerSession {
        if (authClient == null && sessionAuthenticator == null) return session
        if (session.refreshToken == null) return session
        return try {
            sessionStore.refreshIfNeeded(session, Duration.ofMinutes(5)) { refreshToken ->
                refreshToken(refreshToken)
            }
        } catch (exception: SpotifyAuthClient.SpotifyAuthException) {
            if (exception.spotifyError == "invalid_grant") sessionStore.invalidate(session.ownerSpotifyUserId)
            throw ApiFailure(
                401,
                if (exception.spotifyError == "invalid_grant") "reauthorization_required" else "spotify_auth_failure",
                "Spotify authorization is no longer available",
                clearSessionCookie = exception.spotifyError == "invalid_grant",
            )
        } catch (exception: Exception) {
            throw ApiFailure(502, "spotify_auth_failure", "Spotify session refresh failed")
        }
    }

    private fun checkAllowedUser(session: ButlerSession) {
        if (allowedSpotifyUserId != null &&
            session.ownerSpotifyUserId != allowedSpotifyUserId
        ) {
            throw ApiFailure(403, "user_not_allowed", "This Spotify account is not allowed")
        }
    }

    private fun refreshToken(refreshToken: String): TokenRefreshResult =
        sessionAuthenticator?.refresh(refreshToken)
            ?: authClient?.refreshAccessToken(refreshToken)?.let {
                TokenRefreshResult(it.accessToken, it.expiresInSeconds, it.refreshToken)
            }
            ?: throw IllegalStateException("Spotify session refresh is not configured")

    private fun currentSpotifyUserId(accessToken: String): String =
        sessionAuthenticator?.currentUserId(accessToken)
            ?: spotifyUserIdLookup?.invoke(accessToken)
            ?: throw IllegalStateException("Spotify identity lookup is not configured")

    private data class SessionResolution(
        val session: ButlerSession,
        val rotatedSessionId: String?,
    )

    private fun requireStateChange(
        request: ApiRequest,
        session: ButlerSession,
    ) {
        requireCsrf(request, session)
        requireTrustedOrigin(request)
        requireJsonBody(request)
    }

    private fun requireCsrf(
        request: ApiRequest,
        session: ButlerSession,
    ) {
        if (request.headers.header("X-CSRF-Token") != session.csrfToken) {
            throw ApiFailure(403, "csrf_failed", "The CSRF token is invalid")
        }
    }

    private fun requireTrustedOrigin(request: ApiRequest) {
        if (request.headers.header("Origin") !in trustedOrigins || trustedOrigins.isEmpty()) {
            throw ApiFailure(403, "origin_not_trusted", "The request origin is not trusted")
        }
    }

    private fun requireJsonBody(request: ApiRequest) {
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

    @Suppress("SwallowedException", "TooGenericExceptionCaught")
    private inline fun <reified T> body(request: ApiRequest): T =
        try {
            apiJson.decodeFromString(
                request.body ?: error("A JSON body is required"),
            )
        } catch (
            exception: Exception,
        ) {
            throw ApiFailure(400, "malformed_json", "The request body is not valid JSON")
        }

    private fun sessionCookie(sessionId: String) =
        "butler_session=$sessionId; Path=/; Max-Age=$SESSION_COOKIE_MAX_AGE_SECONDS; SameSite=Strict; HttpOnly" +
            if (secureCookies) "; Secure" else ""

    private fun clearSessionCookie() =
        "butler_session=; Path=/; Max-Age=0; SameSite=Strict; HttpOnly" +
            if (secureCookies) "; Secure" else ""

    private inline fun <reified T> json(
        status: Int,
        value: T,
    ) = ApiResponse(status, apiJson.encodeToString(value))

    private fun errorResponse(
        failure: ApiFailure,
        requestId: String,
    ) = ApiResponse(
        failure.status,
        apiJson.encodeToString(ErrorEnvelope(failure.code, failure.message, requestId, failure.details)),
        mapOf("Content-Type" to "application/json; charset=utf-8") +
            if (failure.clearSessionCookie) mapOf("Set-Cookie" to clearSessionCookie()) else emptyMap(),
    )

    private fun newRequestId() =
        "req-" +
            Base64
                .getUrlEncoder()
                .withoutPadding()
                .encodeToString(UUID.randomUUID().toString().toByteArray())
                .take(22)

    private fun validRequestId(value: String) =
        value.length in 1..100 && value.all { it.isLetterOrDigit() || it in "-_" }

    private fun Map<String, String>.header(name: String) = entries.firstOrNull { it.key.equals(name, true) }?.value

    private fun Map<String, String>.cookie(name: String) =
        header("Cookie")
            ?.split(';')
            ?.map { it.trim().split('=', limit = 2) }
            ?.firstOrNull {
                it.firstOrNull() ==
                    name
            }?.getOrNull(1)

    private fun ApiResponse.withRequestId(requestId: String) = copy(headers = headers + ("X-Request-Id" to requestId))

    private companion object {
        const val MAX_BULK_SONG_IDS = 10_000
        const val SESSION_COOKIE_MAX_AGE_SECONDS = 15_552_000
        val TRACK_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,200}")
    }
}

@Serializable data class RefreshLibraryRequest(
    val sourceKeys: List<String>? = null,
)

private data class ApiFailure(
    val status: Int,
    val code: String,
    override val message: String,
    val details: Map<String, String> = emptyMap(),
    val clearSessionCookie: Boolean = false,
) : RuntimeException(message)

sealed interface OperationSocketAuthorization {
    data class Accepted(
        val ownerSpotifyUserId: String,
    ) : OperationSocketAuthorization

    data class Rejected(
        val response: ApiResponse,
    ) : OperationSocketAuthorization
}

private fun com.philipwilcox.spotifybutler.service.CacheSourceSnapshot.toWire() =
    SourceSnapshotWire(
        sourceKey,
        resourceKind.name.lowercase(),
        status.name.lowercase(),
        sourceRevision,
        lastSyncedAt?.toString(),
        itemCount,
        canRefresh,
        lastErrorCode,
        lastErrorAt?.toString(),
    )

private fun com.philipwilcox.spotifybutler.service.LibraryPlaylistSummary.toWire() =
    LibraryPlaylistWire(
        spotifyPlaylistId,
        name,
        description,
        href,
        uri,
        displayUrl,
        declaredItemCount,
        cachedPlayableTrackCount,
        contentSourceKey,
        contentStatus.name.lowercase(),
        sourceRevision,
        lastSyncedAt?.toString(),
        editable,
    )

private fun com.philipwilcox.spotifybutler.service.LibraryPlaylistDetail.toWire() =
    LibraryPlaylistDetailWire(summary.toWire(), trackIds)

private fun com.philipwilcox.spotifybutler.service.SourceDependency.toWire() =
    SourceDependencyWire(
        sourceKey,
        resourceKind.name.lowercase(),
        sourceRevision,
        lastSyncedAt?.toString(),
        itemCount,
        usable,
    )

private fun com.philipwilcox.spotifybutler.service.DestinationState.toWire(definitionId: String) =
    DestinationSummaryWire(
        definitionId,
        spotifyPlaylistId,
        createdAt.toString(),
        lastSyncedAt?.toString(),
        lastSeenSnapshotId,
        canSync,
    )

private fun com.philipwilcox.spotifybutler.service.PublishPlan.toWire() =
    PublishPlanWire(
        definitionId,
        playlistName,
        action.name.lowercase(),
        candidates.map {
            PublishPlaylistCandidateWire(
                it.spotifyPlaylistId,
                it.name,
                it.description,
                it.itemCount,
                it.displayUrl,
            )
        },
        message,
        publishFlowId ?: error("Publish plan flow ID is required at the API boundary"),
    )

private fun publishCandidateWire(candidate: com.philipwilcox.spotifybutler.service.PublishPlaylistCandidate) =
    PublishPlaylistCandidateWire(
        candidate.spotifyPlaylistId,
        candidate.name,
        candidate.description,
        candidate.itemCount,
        candidate.displayUrl,
    )

private data class BulkRepublishJob(
    val definition: PlaylistDefinitionView,
    val choice: BulkRepublishChoiceWire,
)

private fun ApiResponse.withPublishFlowId(flowId: String) = copy(headers = headers + (PUBLISH_FLOW_ID_HEADER to flowId))

private const val PUBLISH_FLOW_ID_HEADER = "X-Spotify-Butler-Publish-Flow-Id"
private const val BULK_REPUBLISH_CONCURRENCY = 3

private fun PlaylistPreview.toWire() =
    PreviewWire(
        definitionId,
        status.name.lowercase(),
        generatedTrackIds,
        generatedTrackIds.size,
        seed,
        recipeRevision,
        algorithmVersion,
        sourceDependencies
            .map {
                it.toWire()
            },
        generatedAt.toString(),
        unavailableReason,
    )

private fun StoredPlaylistItem.isPlayableCurrentTrack() =
    isPlayable &&
        !isLocal &&
        itemType == "track" &&
        status == "playable" &&
        !itemId.isNullOrBlank() &&
        itemUri?.startsWith("spotify:track:") == true

private fun StoredSong.toSongWire() =
    SongWire(
        id,
        name,
        href,
        uri,
        AlbumWire(albumId, albumName, albumHref, albumUri, releaseDate, albumImageUrl),
        artists.map {
            ArtistWire(it.id, it.name, it.href, it.uri)
        },
        durationMs,
        explicit,
        available,
    )
