package com.philipwilcox.spotifybutler.http

import com.philipwilcox.spotifybutler.db.SpotifyStore
import com.philipwilcox.spotifybutler.service.SpotifyCacheService
import com.philipwilcox.spotifybutler.spotify.SavedTrack
import com.philipwilcox.spotifybutler.spotify.SpotifyCacheFetcher
import com.philipwilcox.spotifybutler.spotify.SpotifyCacheSnapshot
import com.philipwilcox.spotifybutler.spotify.SpotifyTrack
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ApiApplicationTargetContractTest {
    @Test
    fun removedRoutesAndGlobalFieldsAreAbsent() {
        withApplication { app, session, _, _, _ ->
            val run = app.handle(request("POST", "/api/v1/run", session))
            val singular = app.handle(request("GET", "/api/v1/songs/one", session))
            val library = app.handle(request("GET", "/api/v1/library", session))
            assertEquals(404, run.status)
            assertEquals(404, singular.status)
            assertFalse(library.body.contains("cacheRevision"))
            assertFalse(library.body.contains("completedAt"))
            assertFalse(library.body.contains("counts"))
        }
    }

    @Test
    fun publishPlansAndPublishesManagedDestination() {
        withApplication { app, session, gateway, store, registry ->
            val plan = app.handle(request("POST", "/api/v1/playlists/RECENT_LIKED_100/publish-plan", session, "{}"))
            assertEquals(202, plan.status, plan.body)
            assertTrue(plan.body.contains("\"kind\":\"publish_plan\""))
            val planOperation = acceptedOperationId(plan.body)
            assertEquals(OperationPhase.succeeded, awaitTerminal(registry, planOperation).phase)
            val publish =
                app.handle(
                    request(
                        "POST",
                        "/api/v1/playlists/RECENT_LIKED_100/publish",
                        session,
                        "{\"action\":\"create\",\"trackIds\":[\"one\"]}",
                    ),
                )
            assertEquals(202, publish.status, publish.body)
            assertTrue(publish.body.contains("\"kind\":\"publish_create\""))
            val publishOperation = acceptedOperationId(publish.body)
            assertEquals(OperationPhase.succeeded, awaitTerminal(registry, publishOperation).phase)
            assertEquals(1, store.managedPlaylists(OWNER).size)
            assertEquals("created-playlist", store.managedPlaylist("RECENT_LIKED_100", OWNER)?.spotifyPlaylistId)
            assertEquals("created-playlist", gateway.lastPlaylistId)
            assertEquals(listOf("one"), gateway.tracks)
            assertEquals(
                404,
                app.handle(request("POST", "/api/v1/playlists/RECENT_LIKED_100/destinations", session, "{}")).status,
            )
            assertEquals(
                404,
                app
                    .handle(
                        request(
                            "POST",
                            "/api/v1/playlists/RECENT_LIKED_100/one-time-updates",
                            session,
                            "{}",
                        ),
                    ).status,
            )
        }
    }

    @Test
    fun stateChangeStartsReturnAcceptedOperationsAndLocalFailuresRemainSynchronous() {
        withApplication { app, session, _, _, registry ->
            val refresh = app.handle(request("POST", "/api/v1/library/refresh", session, "{}"))
            assertEquals(202, refresh.status, refresh.body)
            assertTrue(refresh.body.contains("\"kind\":\"library_refresh\""))
            val refreshOperation = acceptedOperationId(refresh.body)
            assertEquals(OperationPhase.succeeded, awaitTerminal(registry, refreshOperation).phase)

            val invalidPublish =
                app.handle(
                    request(
                        "POST",
                        "/api/v1/playlists/RECENT_LIKED_100/publish",
                        session,
                        "{\"action\":\"invalid\",\"trackIds\":[\"one\"]}",
                    ),
                )
            assertEquals(400, invalidPublish.status)
            assertTrue(invalidPublish.body.contains("\"code\":\"invalid_publish\""))

            val missingDestination =
                app.handle(
                    request(
                        "POST",
                        "/api/v1/playlists/RECENT_LIKED_100/syncs",
                        session,
                        "{\"trackIds\":[\"one\"]}",
                    ),
                )
            assertEquals(409, missingDestination.status)
            assertTrue(missingDestination.body.contains("\"code\":\"destination_missing\""))
        }
    }

    @Test
    fun operationSocketAuthorizationRequiresSessionAndTrustedOrigin() {
        withApplication { app, session, _, _, _ ->
            val accepted =
                app.authorizeOperationSocket(
                    request("GET", "/api/v1/operations/op/events", session),
                    "req-test",
                )
            assertEquals(OperationSocketAuthorization.Accepted(OWNER), accepted)

            val noOrigin =
                app.authorizeOperationSocket(
                    request("GET", "/api/v1/operations/op/events", session).copy(headers = sessionHeaders(session)),
                    "req-test",
                )
            assertRejected(noOrigin, "origin_not_trusted")

            val noSession =
                app.authorizeOperationSocket(
                    ApiRequest("GET", "/api/v1/operations/op/events", headers = mapOf("Origin" to ORIGIN)),
                    "req-test",
                )
            assertRejected(noSession, "unauthorized")
        }
    }

    @Test
    fun recipeSettingsCanUpdateBuiltInShufflePreference() {
        withApplication { app, session, _, store, _ ->
            val response =
                app.handle(
                    request(
                        "PUT",
                        "/api/v1/playlists/RECENT_LIKED_100/recipe-settings",
                        session,
                        "{\"shuffleAfterGeneration\":true}",
                    ),
                )
            assertEquals(200, response.status, response.body)
            assertTrue(response.body.contains("\"shuffleAfterGeneration\":true"))
            assertEquals(true, store.playlistRecipePreference("RECENT_LIKED_100", OWNER))
        }
    }

    @Test
    fun bulkSongsReturnsUniqueKnownTracksAndMissingIdsInRequestOrder() {
        withApplication { app, session, _, _, _ ->
            val response =
                app.handle(
                    request(
                        "POST",
                        "/api/v1/songs/bulk",
                        session,
                        "{\"trackIds\":[\"missing\",\"one\",\"one\",\"two\"]}",
                    ),
                )
            assertEquals(200, response.status, response.body)
            assertTrue(response.body.contains("\"imageUrl\":\"https://example.invalid/art\""))
            assertTrue(response.body.contains("\"id\":\"two\""))
            assertTrue(response.body.contains("\"imageUrl\":null"))
            assertTrue(response.body.contains("\"missingIds\":[\"missing\"]"))
        }
    }

    private fun withApplication(
        block: (ApiApplication, ButlerSession, RecordingGateway, SpotifyStore, OperationRegistry) -> Unit,
    ) {
        val path = Files.createTempDirectory("api-target-").resolve("cache.db")
        SpotifyStore.open(path).use { store ->
            store.replaceCache(
                SpotifyCacheSnapshot(
                    listOf(
                        SavedTrack("2026-01-01T00:00:00Z", track("one", "https://example.invalid/art")),
                        SavedTrack("2026-01-01T00:00:00Z", track("two")),
                    ),
                    emptyList(),
                    emptyList(),
                    emptyList(),
                    emptyList(),
                ),
                1L,
                OWNER,
            )
            val sessions = SessionStore()
            val session = sessions.create(OWNER, "token", "refresh")
            val gateway = RecordingGateway()
            val registry = OperationRegistry()
            val app =
                ApiApplication(
                    SpotifyCacheService(StaticFetcher(), store),
                    store,
                    sessions,
                    gateway,
                    trustedOrigins = setOf(ORIGIN),
                    operationRegistry = registry,
                )
            try {
                block(app, session, gateway, store, registry)
            } finally {
                registry.close()
            }
        }
    }

    private fun acceptedOperationId(body: String): String =
        Regex("\\\"operationId\\\":\\\"([^\\\"]+)\\\"").find(body)?.groupValues?.get(1)
            ?: error("Expected operation ID in $body")

    private fun awaitTerminal(
        registry: OperationRegistry,
        operationId: String,
    ): OperationStatusWire {
        repeat(100) {
            val status = registry.updates(OWNER, operationId)?.value
            if (status?.phase in setOf(OperationPhase.succeeded, OperationPhase.failed)) return assertNotNull(status)
            Thread.sleep(10)
        }
        return assertNotNull(registry.updates(OWNER, operationId)?.value)
    }

    private fun assertRejected(
        authorization: OperationSocketAuthorization,
        code: String,
    ) {
        val response = (authorization as? OperationSocketAuthorization.Rejected)?.response
        assertNotNull(response)
        assertTrue(response.body.contains("\"code\":\"$code\""), response.body)
    }

    private fun sessionHeaders(session: ButlerSession) =
        mapOf(
            "Cookie" to "butler_session=" + session.id,
            "X-CSRF-Token" to session.csrfToken,
            "Content-Type" to "application/json",
        )

    private fun request(
        method: String,
        path: String,
        session: ButlerSession,
        body: String? = null,
    ) = ApiRequest(
        method,
        path,
        headers = sessionHeaders(session) + ("Origin" to ORIGIN),
        body = body,
    )

    private class StaticFetcher : SpotifyCacheFetcher {
        override fun fetchCache(accessToken: String) =
            SpotifyCacheSnapshot(emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
    }

    private class RecordingGateway : PlaylistSyncGateway {
        var lastPlaylistId: String? = null
        var tracks = emptyList<String>()

        override fun current(
            accessToken: String,
            playlistId: String,
        ) = PlaylistRemoteState(tracks, "snapshot")

        override fun replaceTracks(
            accessToken: String,
            playlistId: String,
            trackIds: List<String>,
        ) {
            lastPlaylistId = playlistId
            tracks = trackIds
        }

        override fun createPlaylist(
            accessToken: String,
            name: String,
        ) = "created-playlist"
    }

    private companion object {
        const val OWNER = "owner"
        const val ORIGIN = "https://app.example.test"

        fun track(
            id: String,
            imageUrl: String? = null,
        ) = SpotifyTrack(
            id,
            id,
            "href:" + id,
            "spotify:track:" + id,
            "2026",
            "artist",
            "{\"name\":\"" + id + "\",\"id\":\"" + id + "\",\"href\":\"href:" + id + "\",\"uri\":\"spotify:track:" +
                id +
                "\",\"album\":{\"images\":" + (if (imageUrl == null) "[]" else "[{\"url\":\"$imageUrl\"}]") + "}}",
        )
    }
}
