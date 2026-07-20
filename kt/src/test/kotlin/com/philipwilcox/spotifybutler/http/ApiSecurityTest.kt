package com.philipwilcox.spotifybutler.http

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class ApiSecurityTest {
    @Test
    fun `session rotation invalidates the old opaque session and keeps credentials server side`() {
        val sessions = SessionStore(fixedClock())
        val original = sessions.create("owner-one", "access-secret", "refresh-secret")

        val rotated = sessions.rotate(original, "new-access-secret", "new-refresh-secret")

        assertNull(sessions.find(original.id))
        assertSame(rotated, sessions.find(rotated.id))
        assertNotEquals(original.id, rotated.id)
        assertNotEquals(original.csrfToken, rotated.csrfToken)
        assertEquals("new-access-secret", rotated.accessToken)
        assertEquals("new-refresh-secret", rotated.refreshToken)
    }

    @Test
    fun `operation idempotency is owner scoped and detects a changed request`() {
        val operations = OperationStore(fixedClock())
        val first = operations.create("owner-one", "playlist_sync", "key-one", "request-one")

        assertEquals(
            IdempotencyLookup.Existing(first),
            operations.lookup("owner-one", "playlist_sync", "key-one", "request-one"),
        )
        assertEquals(
            IdempotencyLookup.Conflict,
            operations.lookup("owner-one", "playlist_sync", "key-one", "request-two"),
        )
        assertEquals(
            IdempotencyLookup.New,
            operations.lookup("owner-two", "playlist_sync", "key-one", "request-two"),
        )
    }

    private fun fixedClock(): Clock = Clock.fixed(Instant.parse("2030-01-01T00:00:00Z"), ZoneOffset.UTC)
}
