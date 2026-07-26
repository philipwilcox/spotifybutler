package com.philipwilcox.spotifybutler.http

import com.philipwilcox.spotifybutler.db.SpotifyStore
import com.philipwilcox.spotifybutler.service.SpotifyCacheService
import com.philipwilcox.spotifybutler.spotify.SpotifyCacheFetcher
import com.philipwilcox.spotifybutler.spotify.SpotifyCacheSnapshot
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DurableAuthSecurityTest {
    private val clock = Clock.fixed(Instant.parse("2030-01-01T00:00:00Z"), ZoneOffset.UTC)

    @Test
    fun `database reopen rehydrates session and rotates replacement token without resetting auth time`() {
        val path = Files.createTempDirectory("durable-auth-").resolve("cache.db")
        val originalSessionId: String
        val authorizationTime: Long
        SpotifyStore.open(path).use { store ->
            val sessions = SessionStore(clock, authStore = store)
            val original = sessions.create("owner-a", "access-a", "refresh-a")
            originalSessionId = original.id
            authorizationTime = store.spotifyAuthGrant("owner-a")!!.authorizedAtMillis
        }

        SpotifyStore.open(path).use { store ->
            val sessions = SessionStore(clock, authStore = store)
            val rehydrated =
                sessions.rehydrate(
                    originalSessionId,
                    refresh = { token ->
                        assertEquals("refresh-a", token)
                        TokenRefreshResult("access-b", 3_600, "refresh-b")
                    },
                    verifySpotifyUserId = { "owner-a" },
                )!!
            val grant = store.spotifyAuthGrant("owner-a")!!

            assertNotEquals(originalSessionId, rehydrated.id)
            assertEquals("access-b", rehydrated.accessToken)
            assertEquals("refresh-b", grant.refreshToken)
            assertEquals(authorizationTime, grant.authorizedAtMillis)
            assertEquals(clock.instant().toEpochMilli(), grant.lastRefreshedAtMillis)
            assertNull(store.browserSession(originalSessionId))
            assertEquals("owner-a", store.browserSession(rehydrated.id)!!.spotifyUserId)
        }
        java.sql.DriverManager.getConnection("jdbc:sqlite:" + path).use { connection ->
            connection.prepareStatement("SELECT protected_refresh_token FROM spotify_auth_grants").use { statement ->
                statement.executeQuery().use { result ->
                    assertTrue(result.next())
                    assertFalse(result.getString(1).contains("refresh-b"))
                }
            }
        }
    }

    @Test
    fun `omitted refresh token preserves the stored token`() {
        val path = Files.createTempDirectory("durable-auth-preserve-").resolve("cache.db")
        SpotifyStore.open(path).use { store ->
            val sessions = SessionStore(clock, authStore = store)
            val session = sessions.create("owner-a", "access-a", "refresh-a")
            sessions.refreshWithResult(session) { TokenRefreshResult("access-b", 3_600, null) }
            assertEquals("refresh-a", store.spotifyAuthGrant("owner-a")!!.refreshToken)
        }
    }

    @Test
    fun `session endpoint rehydrates and returns a rotated cookie`() {
        val path = Files.createTempDirectory("durable-auth-api-").resolve("cache.db")
        val originalSessionId: String
        SpotifyStore.open(path).use { store ->
            originalSessionId = SessionStore(clock, authStore = store).create("owner-a", "access-a", "refresh-a").id
        }
        SpotifyStore.open(path).use { store ->
            val calls = AtomicInteger()
            val application =
                ApiApplication(
                    cacheService = SpotifyCacheService(EmptyFetcher(), store),
                    store = store,
                    sessionStore = SessionStore(clock, authStore = store),
                    syncGateway = EmptyGateway,
                    sessionAuthenticator =
                        object : SpotifySessionAuthenticator {
                            override fun refresh(refreshToken: String): TokenRefreshResult {
                                calls.incrementAndGet()
                                assertEquals("refresh-a", refreshToken)
                                return TokenRefreshResult("access-b", 3_600, null)
                            }

                            override fun currentUserId(accessToken: String) = "owner-a"
                        },
                )
            val response = application.handle(ApiRequest("GET", "/api/v1/session", headers = cookie(originalSessionId)))

            assertEquals(200, response.status)
            assertEquals(1, calls.get())
            assertTrue(response.headers["Set-Cookie"].orEmpty().contains("SameSite=Strict"))
            assertFalse(response.headers["Set-Cookie"].orEmpty().contains(originalSessionId))
            assertFalse(response.body.contains("access-b"))
        }
    }

    @Test
    fun `invalid grant clears durable authentication state without retrying`() {
        val path = Files.createTempDirectory("durable-auth-revoked-").resolve("cache.db")
        val originalSessionId: String
        SpotifyStore.open(path).use { store ->
            originalSessionId = SessionStore(clock, authStore = store).create("owner-a", "access-a", "refresh-a").id
        }
        SpotifyStore.open(path).use { store ->
            val calls = AtomicInteger()
            val application =
                ApiApplication(
                    cacheService = SpotifyCacheService(EmptyFetcher(), store),
                    store = store,
                    sessionStore = SessionStore(clock, authStore = store),
                    syncGateway = EmptyGateway,
                    sessionAuthenticator =
                        object : SpotifySessionAuthenticator {
                            override fun refresh(refreshToken: String): TokenRefreshResult {
                                calls.incrementAndGet()
                                throw com.philipwilcox.spotifybutler.spotify.SpotifyAuthClient.SpotifyAuthException(
                                    400,
                                    "invalid_grant",
                                    "revoked",
                                )
                            }

                            override fun currentUserId(accessToken: String) = "owner-a"
                        },
                )
            val response = application.handle(ApiRequest("GET", "/api/v1/session", headers = cookie(originalSessionId)))

            assertEquals(401, response.status)
            assertEquals(1, calls.get())
            assertNull(store.spotifyAuthGrant("owner-a"))
            assertNull(store.browserSession(originalSessionId))
            assertTrue(response.headers["Set-Cookie"].orEmpty().contains("Max-Age=0"))
        }
    }

    @Test
    fun `concurrent rehydration refreshes a durable session once`() {
        val path = Files.createTempDirectory("durable-auth-concurrent-").resolve("cache.db")
        SpotifyStore.open(path).use { store ->
            val first = SessionStore(clock, authStore = store).create("owner-a", "access-a", "refresh-a")
            val sessions = SessionStore(clock, authStore = store)
            val calls = AtomicInteger()
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            val results = mutableListOf<ButlerSession?>()
            val action = {
                sessions.rehydrate(
                    first.id,
                    refresh = {
                        calls.incrementAndGet()
                        started.countDown()
                        release.await()
                        TokenRefreshResult("access-b", 3_600, null)
                    },
                    verifySpotifyUserId = { "owner-a" },
                )
            }
            val one = thread(start = true) { synchronized(results) { results += action() } }
            assertTrue(started.await(1, java.util.concurrent.TimeUnit.SECONDS))
            val two = thread(start = true) { synchronized(results) { results += action() } }
            release.countDown()
            one.join(1_000)
            two.join(1_000)
            assertEquals(1, calls.get())
            assertEquals(1, results.count { it != null })
        }
    }

    @Test
    fun `sign out deletes durable grant and browser session while DTO contains no token`() {
        val path = Files.createTempDirectory("durable-auth-signout-").resolve("cache.db")
        SpotifyStore.open(path).use { store ->
            val sessions = SessionStore(clock, authStore = store)
            val session = sessions.create("owner-a", "access-secret", "refresh-secret")
            val application =
                ApiApplication(
                    cacheService = SpotifyCacheService(EmptyFetcher(), store),
                    store = store,
                    sessionStore = sessions,
                    syncGateway = EmptyGateway,
                    trustedOrigins = setOf("https://app.example.test"),
                )
            val read = application.handle(ApiRequest("GET", "/api/v1/session", headers = cookie(session.id)))
            assertEquals(200, read.status)
            assertFalse(read.body.contains("access-secret"))
            assertFalse(read.body.contains("refresh-secret"))
            val deleted =
                application.handle(
                    ApiRequest(
                        "DELETE",
                        "/api/v1/session",
                        headers =
                            cookie(session.id) +
                                mapOf(
                                    "X-CSRF-Token" to session.csrfToken,
                                    "Origin" to ORIGIN,
                                    "Content-Type" to "application/json",
                                ),
                        body = "{}",
                    ),
                )
            assertEquals(204, deleted.status)
            assertNull(store.spotifyAuthGrant("owner-a"))
            assertNull(store.browserSession(session.id))
            assertTrue(deleted.headers["Set-Cookie"].orEmpty().contains("Max-Age=0"))
        }
    }

    private fun cookie(sessionId: String) = mapOf("Cookie" to "butler_session=$sessionId")

    private class EmptyFetcher : SpotifyCacheFetcher {
        override fun fetchCache(accessToken: String) =
            SpotifyCacheSnapshot(emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
    }

    private object EmptyGateway : PlaylistSyncGateway {
        override fun current(
            accessToken: String,
            playlistId: String,
        ) = PlaylistRemoteState(emptyList())

        override fun replaceTracks(
            accessToken: String,
            playlistId: String,
            trackIds: List<String>,
        ) = Unit
    }

    private companion object {
        const val ORIGIN = "https://app.example.test"
    }
}
