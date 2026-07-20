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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApiApplicationTest {
    @Test
    fun `current and item views are id only, ordered, and preserve duplicate positions`() {
        withApplication { app, session, gateway, _ ->
            val current = app.handle(request("GET", "/api/v1/playlists/RECENT_LIKED_100/current", session))
            assertEquals(200, current.status)
            val currentWire = apiJson.decodeFromString<PlaylistCurrentEnvelopeWire>(current.body)
            assertEquals(listOf("track-one", "track-two", "track-one"), currentWire.current?.trackIds)
            assertFalse(current.body.contains("Track One"))
            assertEquals(0, gateway.currentSnapshotCalls)

            val page =
                app.handle(
                    request(
                        "GET",
                        "/api/v1/playlists/RECENT_LIKED_100/current/items",
                        session,
                        query = mapOf("limit" to "2"),
                    ),
                )
            val pageWire = apiJson.decodeFromString<PlaylistItemsWire>(page.body)
            assertEquals(listOf(0, 1), pageWire.items.map(PlaylistItemWire::position))
            assertEquals("track-one", pageWire.items[0].trackId)
            assertEquals("track-two", pageWire.items[1].trackId)
            assertTrue(pageWire.nextCursor != null)

            val stale =
                app.handle(
                    request(
                        "GET",
                        "/api/v1/playlists/RECENT_LIKED_100/current/items",
                        session,
                        query = mapOf("cursor" to "not-a-cursor"),
                    ),
                )
            assertEquals(409, stale.status)
            assertTrue(stale.body.contains("cursor_stale"))

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
    fun `state changes require CSRF and sync idempotency preserves duplicate submitted IDs`() {
        withApplication { app, session, gateway, _ ->
            val missingCsrfRequest = request("POST", "/api/v1/library/refresh", session, idempotencyKey = "refresh-1")
            val missingCsrf = app.handle(missingCsrfRequest.copy(headers = missingCsrfRequest.headers - "X-CSRF-Token"))
            assertEquals(403, missingCsrf.status)
            assertTrue(missingCsrf.body.contains("csrf_failed"))

            val syncRequest =
                """{"trackIds":["track-two","track-two","track-one"],"baseSnapshotId":"snapshot-1"}"""
            val first =
                app.handle(
                    request(
                        "POST",
                        "/api/v1/playlists/RECENT_LIKED_100/syncs",
                        session,
                        body = syncRequest,
                        idempotencyKey = "sync-1",
                    ),
                )
            assertEquals(202, first.status, first.body)
            assertTrue(first.body.contains("succeeded"), first.body)
            val firstOperation = apiJson.decodeFromString<OperationWire>(first.body)
            assertEquals(listOf("track-two", "track-two", "track-one"), gateway.submittedTrackIds)

            val repeated =
                app.handle(
                    request(
                        "POST",
                        "/api/v1/playlists/RECENT_LIKED_100/syncs",
                        session,
                        body = syncRequest,
                        idempotencyKey = "sync-1",
                    ),
                )
            val repeatedOperation = apiJson.decodeFromString<OperationWire>(repeated.body)
            assertEquals(firstOperation.id, repeatedOperation.id)
            assertEquals(1, gateway.replaceCalls)
        }
    }

    private fun withApplication(block: (ApiApplication, ButlerSession, RecordingSyncGateway, SpotifyStore) -> Unit) {
        val path = Files.createTempDirectory("api-application-").resolve("cache.db")
        SpotifyStore.open(path).use { store ->
            store.replaceCache(snapshot(), 1_700_000_000_000L, OWNER_ID)
            val sessions = SessionStore(fixedClock())
            val session = sessions.create(OWNER_ID, "server-access-token", "server-refresh-token")
            val gateway = RecordingSyncGateway()
            val app =
                ApiApplication(
                    cacheService = SpotifyCacheService(StaticFetcher(), store, fixedClock()),
                    store = store,
                    sessionStore = sessions,
                    operationStore = OperationStore(fixedClock()),
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
        idempotencyKey: String? = null,
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
                    if (idempotencyKey != null) put("Idempotency-Key", idempotencyKey)
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

    private class RecordingSyncGateway : PlaylistSyncGateway {
        var currentSnapshotCalls = 0
        var replaceCalls = 0
        var submittedTrackIds: List<String> = emptyList()

        override fun currentSnapshot(
            accessToken: String,
            playlistId: String,
        ): String {
            currentSnapshotCalls++
            return "snapshot-1"
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
