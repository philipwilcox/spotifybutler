package com.philipwilcox.spotifybutler.http

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

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
    fun `session refresh keeps a lock entry for callers waiting on a failed refresh`() {
        val sessions = SessionStore(fixedClock())
        val original = sessions.create("owner-one", "access-secret", "refresh-secret")
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val releaseSecond = CountDownLatch(1)
        val calls = AtomicInteger()
        val activeCalls = AtomicInteger()
        val maxActiveCalls = AtomicInteger()
        val firstFailure = AtomicReference<Throwable?>()
        val exchange: (String) -> Pair<String, String?> = {
            val call = calls.incrementAndGet()
            val active = activeCalls.incrementAndGet()
            maxActiveCalls.updateAndGet { current -> maxOf(current, active) }
            try {
                when (call) {
                    1 -> {
                        firstStarted.countDown()
                        assertTrue(releaseFirst.await(1, TimeUnit.SECONDS))
                        error("first refresh failed")
                    }

                    2 -> {
                        secondStarted.countDown()
                        assertTrue(releaseSecond.await(1, TimeUnit.SECONDS))
                    }
                }
                "new-access-$call" to "new-refresh-$call"
            } finally {
                activeCalls.decrementAndGet()
            }
        }

        val first =
            thread(start = true) {
                runCatching { sessions.refresh(original, exchange) }.onFailure(firstFailure::set)
            }
        assertTrue(firstStarted.await(1, TimeUnit.SECONDS))
        val second = thread(start = true) { runCatching { sessions.refresh(original, exchange) } }
        Thread.sleep(100)
        releaseFirst.countDown()
        assertTrue(secondStarted.await(1, TimeUnit.SECONDS))

        val thirdStarted = CountDownLatch(1)
        val third =
            thread(start = true) {
                runCatching {
                    sessions.refresh(original) {
                        thirdStarted.countDown()
                        "unexpected-access" to "unexpected-refresh"
                    }
                }
            }

        assertFalse(thirdStarted.await(100, TimeUnit.MILLISECONDS))
        releaseSecond.countDown()
        first.join(1_000)
        second.join(1_000)
        third.join(1_000)

        assertEquals("first refresh failed", firstFailure.get()?.message)
        assertEquals(1, maxActiveCalls.get())
    }

    @Test
    private fun fixedClock(): Clock = Clock.fixed(Instant.parse("2030-01-01T00:00:00Z"), ZoneOffset.UTC)
}
