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
import kotlin.test.assertTrue

class ApiApplicationTargetContractTest {
    @Test
    fun removedRoutesAndGlobalFieldsAreAbsent() {
        withApplication { app, session, _, _ ->
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
    fun destinationCreationAndOneTimeUpdateHaveSeparateSideEffects() {
        withApplication { app, session, gateway, store ->
            val create = app.handle(request("POST", "/api/v1/playlists/RECENT_LIKED_100/destinations", session, "{}"))
            assertEquals(201, create.status, create.body)
            assertEquals(1, store.managedPlaylists(OWNER).size)
            val mappingBefore = store.managedPlaylist("RECENT_LIKED_100", OWNER)
            val oneTime =
                app.handle(
                    request(
                        "POST",
                        "/api/v1/playlists/RECENT_LIKED_100/one-time-updates",
                        session,
                        "{\"spotifyPlaylistId\":\"existing-playlist\",\"trackIds\":[\"one\"]}",
                    ),
                )
            assertEquals(200, oneTime.status, oneTime.body)
            assertTrue(oneTime.body.contains("\"tracked\":false"))
            assertEquals(mappingBefore, store.managedPlaylist("RECENT_LIKED_100", OWNER))
            assertEquals("existing-playlist", gateway.lastPlaylistId)
        }
    }

    private fun withApplication(block: (ApiApplication, ButlerSession, RecordingGateway, SpotifyStore) -> Unit) {
        val path = Files.createTempDirectory("api-target-").resolve("cache.db")
        SpotifyStore.open(path).use { store ->
            store.replaceCache(
                SpotifyCacheSnapshot(
                    listOf(SavedTrack("2026-01-01T00:00:00Z", track("one"))),
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
            val app =
                ApiApplication(
                    SpotifyCacheService(StaticFetcher(), store),
                    store,
                    sessions,
                    gateway,
                    trustedOrigins = setOf(ORIGIN),
                )
            block(app, session, gateway, store)
        }
    }

    private fun request(
        method: String,
        path: String,
        session: ButlerSession,
        body: String? = null,
    ) = ApiRequest(
        method,
        path,
        headers =
            mapOf(
                "Cookie" to "butler_session=" + session.id,
                "X-CSRF-Token" to session.csrfToken,
                "Origin" to ORIGIN,
                "Content-Type" to "application/json",
            ),
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
            request: com.philipwilcox.spotifybutler.service.DestinationCreateRequest,
        ) = "created-playlist"
    }

    private companion object {
        const val OWNER = "owner"
        const val ORIGIN = "https://app.example.test"

        fun track(id: String) =
            SpotifyTrack(
                id,
                id,
                "href:" + id,
                "spotify:track:" + id,
                "2026",
                "artist",
                "{\"name\":\"" + id + "\",\"id\":\"" + id + "\",\"href\":\"href:" + id + "\",\"uri\":\"spotify:track:" +
                    id +
                    "\"}",
            )
    }
}
