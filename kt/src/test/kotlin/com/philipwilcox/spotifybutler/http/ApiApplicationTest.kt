package com.philipwilcox.spotifybutler.http

import com.philipwilcox.spotifybutler.db.SpotifyStore
import com.philipwilcox.spotifybutler.service.SpotifyCacheService
import com.philipwilcox.spotifybutler.spotify.SpotifyCacheFetcher
import com.philipwilcox.spotifybutler.spotify.SpotifyCacheSnapshot
import com.philipwilcox.spotifybutler.spotify.SpotifyPlaylist
import com.philipwilcox.spotifybutler.spotify.SpotifyPlaylistItem
import com.philipwilcox.spotifybutler.spotify.SpotifyTrack
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApiApplicationTest {
    @Test
    fun `openapi documents only direct cache backed synchronization`() {
        val openApi = requireNotNull(javaClass.getResource("/openapi.yaml")).readText()

        assertTrue("/api/v1/playlists/{definitionId}/syncs:" in openApi)
        assertTrue("'200':\n          description: Authoritative cache-backed playlist state" in openApi)
        assertTrue("required: [trackIds, baseSnapshotId, baseCacheRevision]" in openApi)
        assertFalse("/api/v1/operations" in openApi)
        assertFalse("Idempotency-Key" in openApi)
        assertFalse("current/items" in openApi)
        assertFalse("cursor" in openApi)
        assertFalse("syncs/preview" in openApi)
    }

    @Test
    fun `current view is cache backed, ordered, and preserves duplicate positions`() {
        withApplication { app, session, gateway, _ ->
            val current = app.handle(request("GET", "/api/v1/playlists/RECENT_LIKED_100/current", session))
            assertEquals(200, current.status)
            val currentWire = apiJson.decodeFromString<PlaylistCurrentEnvelopeWire>(current.body)
            assertEquals(listOf("track-one", "track-two", "track-one"), currentWire.current?.trackIds)
            assertFalse(current.body.contains("Track One"))
            assertEquals(0, gateway.currentCalls)

            val removed = app.handle(request("GET", "/api/v1/playlists/RECENT_LIKED_100/current/items", session))
            assertEquals(404, removed.status)

            val sessionResponse = app.handle(request("GET", "/api/v1/session", session))
            assertFalse(sessionResponse.body.contains("server-access-token"))
        }
    }

    @Test
    fun `song enrichment preserves requested order and reports missing IDs`() {
        withApplication { app, session, _, _ ->
            val response =
                app.handle(
                    request(
                        "GET",
                        "/api/v1/songs",
                        session,
                        query = mapOf("ids" to "track-two,track-one,missing"),
                    ),
                )
            val songs = apiJson.decodeFromString<SongsWire>(response.body)
            assertEquals(200, response.status)
            assertEquals(listOf("track-two", "track-one"), songs.items.map(SongWire::id))
            assertEquals(listOf("missing"), songs.missingIds)
            assertFalse(response.body.contains("track_json"))
        }
    }

    @Test
    fun `state changes require CSRF and sync returns authoritative duplicate preserving state`() {
        withApplication { app, session, gateway, _ ->
            val missingCsrfRequest = request("POST", "/api/v1/library/refresh", session)
            val missingCsrf = app.handle(missingCsrfRequest.copy(headers = missingCsrfRequest.headers - "X-CSRF-Token"))
            assertEquals(403, missingCsrf.status)
            assertTrue(missingCsrf.body.contains("csrf_failed"))
            val cacheRevision =
                apiJson
                    .decodeFromString<PlaylistCurrentEnvelopeWire>(
                        app.handle(request("GET", "/api/v1/playlists/RECENT_LIKED_100/current", session)).body,
                    ).current
                    ?.cacheRevision
                    ?: error("Expected a cached playlist")

            val syncRequest =
                """{"trackIds":["track-two","track-two","track-one"],"baseSnapshotId":"snapshot-1","baseCacheRevision":"$cacheRevision"}"""
            val first =
                app.handle(
                    request(
                        "POST",
                        "/api/v1/playlists/RECENT_LIKED_100/syncs",
                        session,
                        body = syncRequest,
                    ),
                )
            assertEquals(200, first.status, first.body)
            val current = apiJson.decodeFromString<PlaylistCurrentEnvelopeWire>(first.body).current
            assertEquals(listOf("track-two", "track-two", "track-one"), gateway.submittedTrackIds)
            assertEquals(listOf("track-two", "track-two", "track-one"), current?.trackIds)
            assertEquals("snapshot-2", current?.snapshotId)
            assertEquals(1, gateway.replaceCalls)

            val stale =
                app.handle(
                    request(
                        "POST",
                        "/api/v1/playlists/RECENT_LIKED_100/syncs",
                        session,
                        body = syncRequest,
                    ),
                )
            assertEquals(409, stale.status)
            assertTrue(stale.body.contains("cache_revision_stale"))
        }
    }

    @Test
    fun `concurrent refresh requests fetch once and share the published revision`() {
        val fetchStarted = CountDownLatch(1)
        val releaseFetch = CountDownLatch(1)
        val fetchCalls = AtomicInteger()
        val fetcher =
            BlockingFetcher(
                snapshot = snapshot(),
                fetchStarted = fetchStarted,
                releaseFetch = releaseFetch,
                fetchCalls = fetchCalls,
            )

        withApplication(fetcher) { app, session, _, _ ->
            val responses = arrayOfNulls<ApiResponse>(2)
            val first =
                thread(start = true) {
                    responses[0] =
                        app.handle(request("POST", "/api/v1/library/refresh", session))
                }
            assertTrue(fetchStarted.await(5, TimeUnit.SECONDS))
            val second =
                thread(start = true) {
                    responses[1] =
                        app.handle(request("POST", "/api/v1/library/refresh", session))
                }

            releaseFetch.countDown()
            first.join(5_000)
            second.join(5_000)

            assertFalse(first.isAlive)
            assertFalse(second.isAlive)
            assertEquals(1, fetchCalls.get())
            val firstLibrary = apiJson.decodeFromString<LibraryWire>(requireNotNull(responses[0]).body)
            val secondLibrary = apiJson.decodeFromString<LibraryWire>(requireNotNull(responses[1]).body)
            assertEquals(200, responses[0]?.status)
            assertEquals(200, responses[1]?.status)
            assertEquals(firstLibrary, secondLibrary)
            assertEquals("ready", firstLibrary.status)
        }
    }

    @Test
    fun `refresh failure leaves the prior revision readable as stale`() {
        val failureMessage = "upstream secret failure"
        val fetcher = FailingFetcher(failureMessage)

        withApplication(fetcher) { app, session, _, store ->
            val priorMetadata = requireNotNull(store.cacheMetadata())
            val response = app.handle(request("POST", "/api/v1/library/refresh", session))

            assertEquals(502, response.status)
            assertTrue(response.body.contains("spotify_failure"))
            assertFalse(response.body.contains(failureMessage))
            assertFalse(response.body.contains(session.accessToken))

            val libraryResponse = app.handle(request("GET", "/api/v1/library", session))
            val library = apiJson.decodeFromString<LibraryWire>(libraryResponse.body)
            assertEquals(200, libraryResponse.status)
            assertEquals("stale", library.status)
            assertEquals(priorMetadata.revision, library.cacheRevision)
            assertEquals(priorMetadata.revision, store.cacheMetadata()?.revision)
        }
    }

    @Test
    fun `legacy run is only a compatibility adapter over shared refresh behavior`() {
        val fetcher = FailingFetcher("legacy upstream secret failure")

        withApplication(fetcher) { app, session, _, _ ->
            val response = app.handle(request("POST", "/api/v1/run", session))

            assertEquals(502, response.status)
            assertTrue(response.body.contains("spotify_failure"))
            assertTrue(response.body.contains("Legacy run failed"))
            assertFalse(response.body.contains("legacy upstream secret failure"))
        }
    }

    @Test
    fun `user playlist update replaces its ordered definition durably`() {
        withApplication { app, session, _, store ->
            val created =
                app.handle(
                    request(
                        "POST",
                        "/api/v1/playlists",
                        session,
                        body = "{\"name\":\"Editable\",\"trackIds\":[\"track-one\",\"track-two\"]}",
                    ),
                )
            assertEquals(201, created.status)
            val id = apiJson.decodeFromString<PlaylistReferenceWire>(created.body).id

            val updated =
                app.handle(
                    request(
                        "PUT",
                        "/api/v1/playlists/$id",
                        session,
                        body = "{\"name\":\"Edited\",\"trackIds\":[\"track-two\",\"track-one\",\"track-two\"]}",
                    ),
                )
            assertEquals(200, updated.status)
            assertEquals(
                listOf("track-two", "track-one", "track-two"),
                store.userPlaylistDefinition(id, OWNER_ID)?.trackIds,
            )
            assertEquals("Edited", store.userPlaylistDefinition(id, OWNER_ID)?.name)
        }
    }

    private fun withApplication(
        fetcher: SpotifyCacheFetcher = StaticFetcher(),
        block: (ApiApplication, ButlerSession, RecordingSyncGateway, SpotifyStore) -> Unit,
    ) {
        val path = Files.createTempDirectory("api-application-").resolve("cache.db")
        SpotifyStore.open(path).use { store ->
            store.replaceCache(snapshot(), 1_700_000_000_000L, OWNER_ID)
            val sessions = SessionStore(fixedClock())
            val session = sessions.create(OWNER_ID, "server-access-token", "server-refresh-token")
            val gateway = RecordingSyncGateway()
            val app =
                ApiApplication(
                    cacheService = SpotifyCacheService(fetcher, store, fixedClock()),
                    store = store,
                    sessionStore = sessions,
                    syncGateway = gateway,
                    clock = fixedClock(),
                    trustedOrigins = setOf("https://app.example.test"),
                )
            block(app, session, gateway, store)
        }
    }

    private fun request(
        method: String,
        path: String,
        session: ButlerSession,
        body: String? = null,
        query: Map<String, String> = emptyMap(),
    ): ApiRequest =
        ApiRequest(
            method = method,
            path = path,
            query = query,
            headers =
                buildMap {
                    put("Cookie", "butler_session=${session.id}")
                    put("X-CSRF-Token", session.csrfToken)
                    put("Origin", "https://app.example.test")
                    if (body != null) put("Content-Type", "application/json")
                },
            body = body,
        )

    private fun snapshot(): SpotifyCacheSnapshot {
        val one = track("track-one", "Track One")
        val two = track("track-two", "Track Two")
        val playlist =
            SpotifyPlaylist(
                name = "100 Most Recent Liked Songs",
                id = "playlist-one",
                href = "https://api.example.test/playlists/playlist-one",
                uri = "spotify:playlist:playlist-one",
                tracksHref = "https://api.example.test/playlists/playlist-one/items",
                snapshotId = "snapshot-1",
                ownerId = OWNER_ID,
                itemCount = 5,
            )
        val items =
            listOf(
                item(playlist, 0, one),
                item(playlist, 1, two),
                item(playlist, 2, one),
                SpotifyPlaylistItem(
                    playlistId = playlist.id,
                    playlistName = playlist.name,
                    position = 3,
                    addedAt = null,
                    addedById = null,
                    isLocal = false,
                    itemType = "episode",
                    isPlayable = false,
                    itemId = "episode-one",
                    itemUri = "spotify:episode:episode-one",
                    status = "unsupported_type",
                    rawJson = "{\"item\":{\"type\":\"episode\"}}",
                ),
                SpotifyPlaylistItem(
                    playlistId = playlist.id,
                    playlistName = playlist.name,
                    position = 4,
                    addedAt = null,
                    addedById = null,
                    isLocal = false,
                    itemType = null,
                    isPlayable = false,
                    itemId = null,
                    itemUri = null,
                    status = "inaccessible",
                    rawJson = "{\"item\":null}",
                ),
            )
        return SpotifyCacheSnapshot(
            savedTracks = emptyList(),
            topTracks = emptyList(),
            topArtists = emptyList(),
            playlists = listOf(playlist),
            playlistTracks = emptyList(),
            playlistItems = items,
        )
    }

    private fun item(
        playlist: SpotifyPlaylist,
        position: Int,
        track: SpotifyTrack,
    ) = SpotifyPlaylistItem(
        playlistId = playlist.id,
        playlistName = playlist.name,
        position = position,
        addedAt = "2030-01-01T00:00:00Z",
        addedById = OWNER_ID,
        isLocal = false,
        itemType = "track",
        isPlayable = true,
        itemId = track.id,
        itemUri = track.uri,
        status = "playable",
        rawJson = "{\"item\":${track.rawJson}}",
        track = track,
    )

    private fun track(
        id: String,
        name: String,
    ) = SpotifyTrack(
        name = name,
        id = id,
        href = "https://api.example.test/tracks/$id",
        uri = "spotify:track:$id",
        releaseDate = "2024-01-01",
        primaryArtistId = "artist-one",
        rawJson =
            "{\"album\":{\"id\":\"album-one\",\"name\":\"Album\",\"release_date\":\"2024-01-01\"}," +
                "\"artists\":[{\"id\":\"artist-one\",\"name\":\"Artist\"}]," +
                "\"href\":\"https://api.example.test/tracks/$id\",\"id\":\"$id\",\"name\":\"$name\"," +
                "\"uri\":\"spotify:track:$id\"}",
        albumId = "album-one",
        durationMs = 180_000,
        explicit = false,
        artistIds = listOf("artist-one"),
    )

    private fun fixedClock(): Clock = Clock.fixed(Instant.parse("2030-01-01T00:00:00Z"), ZoneOffset.UTC)

    private class StaticFetcher : SpotifyCacheFetcher {
        override fun fetchCache(accessToken: String): SpotifyCacheSnapshot = error("refresh is not part of this test")
    }

    private class BlockingFetcher(
        private val snapshot: SpotifyCacheSnapshot,
        private val fetchStarted: CountDownLatch,
        private val releaseFetch: CountDownLatch,
        private val fetchCalls: AtomicInteger,
    ) : SpotifyCacheFetcher {
        override fun fetchCache(accessToken: String): SpotifyCacheSnapshot {
            fetchCalls.incrementAndGet()
            fetchStarted.countDown()
            check(releaseFetch.await(5, TimeUnit.SECONDS)) { "Timed out waiting to release cache fetch" }
            return snapshot
        }
    }

    private class FailingFetcher(
        private val failureMessage: String,
    ) : SpotifyCacheFetcher {
        override fun fetchCache(accessToken: String): SpotifyCacheSnapshot = error(failureMessage)
    }

    private class RecordingSyncGateway : PlaylistSyncGateway {
        var currentCalls = 0
        var replaceCalls = 0
        var submittedTrackIds: List<String> = emptyList()

        override fun current(
            accessToken: String,
            playlistId: String,
        ): PlaylistRemoteState {
            currentCalls++
            return if (replaceCalls == 0) {
                PlaylistRemoteState("snapshot-1", listOf("track-one", "track-two", "track-one"))
            } else {
                PlaylistRemoteState("snapshot-2", submittedTrackIds)
            }
        }

        override fun replaceTracks(
            accessToken: String,
            playlistId: String,
            trackIds: List<String>,
        ): String {
            replaceCalls++
            submittedTrackIds = trackIds
            return "snapshot-2"
        }
    }

    private companion object {
        const val OWNER_ID = "owner-one"
    }
}
