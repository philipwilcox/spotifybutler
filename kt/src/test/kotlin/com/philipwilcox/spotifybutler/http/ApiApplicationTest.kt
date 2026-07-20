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
    fun `current view is cache backed, ordered, and preserves duplicate positions`() {
        withApplication { app, session, gateway, _ ->
            val current = app.handle(request("GET", "/api/v1/playlists/RECENT_LIKED_100/current", session))
            assertEquals(200, current.status)
            val currentWire = apiJson.decodeFromString<PlaylistCurrentEnvelopeWire>(current.body)
            assertEquals(listOf("track-one", "track-two", "track-one"), currentWire.current?.trackIds)
            assertFalse(current.body.contains("Track One"))
            assertFalse(current.body.contains("snapshotId"))
            assertFalse(current.body.contains("cacheRevision"))
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
            assertFalse(response.body.contains("cacheRevision"))
        }
    }

    @Test
    fun `song enrichment trims requests preserves found duplicates and deduplicates missing IDs`() {
        withApplication { app, session, _, _ ->
            val response =
                app.handle(
                    request(
                        "GET",
                        "/api/v1/songs",
                        session,
                        query = mapOf("ids" to " track-two, track-one, track-two, missing, missing, "),
                    ),
                )
            val songs = apiJson.decodeFromString<SongsWire>(response.body)

            assertEquals(200, response.status)
            assertEquals(listOf("track-two", "track-one", "track-two"), songs.items.map(SongWire::id))
            assertEquals(listOf("missing"), songs.missingIds)
        }
    }

    @Test
    fun `song enrichment rejects empty and more than fifty normalized IDs`() {
        withApplication { app, session, _, _ ->
            val empty = app.handle(request("GET", "/api/v1/songs", session, query = mapOf("ids" to " ,  ,")))
            val tooMany =
                app.handle(
                    request(
                        "GET",
                        "/api/v1/songs",
                        session,
                        query = mapOf("ids" to (1..51).joinToString(",") { "missing-$it" }),
                    ),
                )

            assertEquals(400, empty.status)
            assertTrue(empty.body.contains("malformed_request"))
            assertEquals(400, tooMany.status)
            assertTrue(tooMany.body.contains("malformed_request"))
        }
    }

    @Test
    fun `cache-only current reads return null for missing mapping and reject another cache owner`() {
        withApplication { app, session, _, _ ->
            val missingMapping = app.handle(request("GET", "/api/v1/playlists/RANDOM_LIKED_100/current", session))
            assertEquals(200, missingMapping.status)
            assertEquals(null, apiJson.decodeFromString<PlaylistCurrentEnvelopeWire>(missingMapping.body).current)
        }

        withOwnerMismatchApplication { app, otherSession ->
            val ownerMismatch = app.handle(request("GET", "/api/v1/library", otherSession))
            assertEquals(403, ownerMismatch.status)
            assertTrue(ownerMismatch.body.contains("owner_mismatch"))
        }
    }

    @Test
    fun `uninitialized cache reads stay empty without calling the Spotify gateway`() {
        withUninitializedApplication { app, session ->
            val current = app.handle(request("GET", "/api/v1/playlists/RECENT_LIKED_100/current", session))
            val songs = app.handle(request("GET", "/api/v1/songs", session, query = mapOf("ids" to "track-one")))

            assertEquals(200, current.status)
            assertEquals(null, apiJson.decodeFromString<PlaylistCurrentEnvelopeWire>(current.body).current)
            assertEquals(200, songs.status)
            assertEquals(listOf("track-one"), apiJson.decodeFromString<SongsWire>(songs.body).missingIds)
        }
    }

    @Test
    fun `state changes require CSRF and sync returns authoritative duplicate preserving state`() {
        withApplication { app, session, gateway, _ ->
            val missingCsrfRequest = request("POST", "/api/v1/library/refresh", session)
            val missingCsrf = app.handle(missingCsrfRequest.copy(headers = missingCsrfRequest.headers - "X-CSRF-Token"))
            assertEquals(403, missingCsrf.status)
            assertTrue(missingCsrf.body.contains("csrf_failed"))
            val syncRequest = """{"trackIds":["track-two","track-two","track-one"]}"""
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
            assertEquals(1, gateway.replaceCalls)
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
            waitForBlockedThread(second)

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

    private fun withOwnerMismatchApplication(block: (ApiApplication, ButlerSession) -> Unit) {
        val path = Files.createTempDirectory("api-owner-mismatch-").resolve("cache.db")
        SpotifyStore.open(path).use { store ->
            store.replaceCache(snapshot(), 1_700_000_000_000L, OWNER_ID)
            val sessions = SessionStore(fixedClock())
            sessions.create(OWNER_ID, "server-access-token", "server-refresh-token")
            val otherSession = sessions.create("owner-two", "other-access-token", "other-refresh-token")
            val app =
                ApiApplication(
                    cacheService = SpotifyCacheService(StaticFetcher(), store, fixedClock()),
                    store = store,
                    sessionStore = sessions,
                    syncGateway = ThrowingSyncGateway(),
                    clock = fixedClock(),
                    trustedOrigins = setOf("https://app.example.test"),
                )
            block(app, otherSession)
        }
    }

    private fun withUninitializedApplication(block: (ApiApplication, ButlerSession) -> Unit) {
        val path = Files.createTempDirectory("api-empty-cache-").resolve("cache.db")
        SpotifyStore.open(path).use { store ->
            val sessions = SessionStore(fixedClock())
            val session = sessions.create(OWNER_ID, "server-access-token", "server-refresh-token")
            val app =
                ApiApplication(
                    cacheService = SpotifyCacheService(StaticFetcher(), store, fixedClock()),
                    store = store,
                    sessionStore = sessions,
                    syncGateway = ThrowingSyncGateway(),
                    clock = fixedClock(),
                    trustedOrigins = setOf("https://app.example.test"),
                )
            block(app, session)
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
                item(playlist, 5, one).copy(isLocal = true),
                item(playlist, 6, one.copy(available = false)).copy(isPlayable = false, status = "unavailable"),
                item(playlist, 7, one).copy(itemId = null, itemUri = null),
                item(playlist, 8, one).copy(itemUri = "spotify:episode:track-one"),
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

    private fun waitForBlockedThread(thread: Thread) {
        val timeout = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (thread.state != Thread.State.BLOCKED && System.nanoTime() < timeout) {
            Thread.sleep(1)
        }
        assertEquals(Thread.State.BLOCKED, thread.state)
    }

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
                PlaylistRemoteState(listOf("track-one", "track-two", "track-one"))
            } else {
                PlaylistRemoteState(submittedTrackIds)
            }
        }

        override fun replaceTracks(
            accessToken: String,
            playlistId: String,
            trackIds: List<String>,
        ) {
            replaceCalls++
            submittedTrackIds = trackIds
        }
    }

    private class ThrowingSyncGateway : PlaylistSyncGateway {
        override fun current(
            accessToken: String,
            playlistId: String,
        ): PlaylistRemoteState = error("current must not be called by cache-backed reads")

        override fun replaceTracks(
            accessToken: String,
            playlistId: String,
            trackIds: List<String>,
        ) = error("replaceTracks must not be called by cache-backed reads")
    }

    private companion object {
        const val OWNER_ID = "owner-one"
    }
}
