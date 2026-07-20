package com.philipwilcox.spotifybutler.http

import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

private const val OPAQUE_TOKEN_BYTES = 32
private const val SESSION_LIFETIME_HOURS = 12

data class ButlerSession(
    val id: String,
    val ownerSpotifyUserId: String,
    val accessToken: String,
    val refreshToken: String?,
    val csrfToken: String,
    val expiresAt: Instant,
)

class SessionStore(
    private val clock: Clock = Clock.systemUTC(),
    private val lifetime: Duration = Duration.ofHours(SESSION_LIFETIME_HOURS.toLong()),
) {
    private val sessions = ConcurrentHashMap<String, ButlerSession>()
    private val refreshLocks = ConcurrentHashMap<String, RefreshLock>()
    private val random = SecureRandom()

    fun create(
        ownerSpotifyUserId: String,
        accessToken: String,
        refreshToken: String?,
    ): ButlerSession {
        val session = newSession(ownerSpotifyUserId, accessToken, refreshToken)
        sessions[session.id] = session
        return session
    }

    fun find(sessionId: String?): ButlerSession? {
        if (sessionId.isNullOrBlank()) return null
        val session = sessions[sessionId] ?: return null
        if (!session.expiresAt.isAfter(clock.instant())) {
            sessions.remove(sessionId, session)
            return null
        }
        return session
    }

    fun rotate(
        session: ButlerSession,
        accessToken: String,
        refreshToken: String?,
    ): ButlerSession {
        sessions.remove(session.id)
        return create(session.ownerSpotifyUserId, accessToken, refreshToken)
    }

    fun refresh(
        session: ButlerSession,
        exchange: (String) -> Pair<String, String?>,
    ): ButlerSession {
        val lock = acquireRefreshLock(session.id)
        return synchronized(lock.monitor) {
            try {
                val current =
                    sessions[session.id]
                        ?: throw IllegalStateException("The session was already rotated or invalidated")
                val refreshToken = current.refreshToken ?: throw IllegalStateException("No refresh token is available")
                val (accessToken, rotatedRefreshToken) = exchange(refreshToken)
                rotate(current, accessToken, rotatedRefreshToken ?: refreshToken)
            } finally {
                releaseRefreshLock(session.id, lock)
            }
        }
    }

    fun remove(sessionId: String?) {
        if (!sessionId.isNullOrBlank()) sessions.remove(sessionId)
    }

    private fun newSession(
        ownerSpotifyUserId: String,
        accessToken: String,
        refreshToken: String?,
    ): ButlerSession =
        ButlerSession(
            id = opaqueToken(),
            ownerSpotifyUserId = ownerSpotifyUserId,
            accessToken = accessToken,
            refreshToken = refreshToken,
            csrfToken = opaqueToken(),
            expiresAt = clock.instant().plus(lifetime),
        )

    private fun opaqueToken(): String =
        ByteArray(
            OPAQUE_TOKEN_BYTES,
        ).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    private fun acquireRefreshLock(sessionId: String): RefreshLock =
        refreshLocks.compute(sessionId) { _, existing ->
            val lock = existing ?: RefreshLock()
            lock.users++
            lock
        }!!

    private fun releaseRefreshLock(
        sessionId: String,
        lock: RefreshLock,
    ) {
        refreshLocks.compute(sessionId) { _, existing ->
            if (existing !== lock) {
                existing
            } else {
                existing.users--
                if (existing.users == 0) null else existing
            }
        }
    }

    private class RefreshLock {
        val monitor = Any()
        var users = 0
    }
}

class KeyedLock {
    private val locks = ConcurrentHashMap<String, LockReference>()

    fun <T> withLock(
        key: String,
        action: () -> T,
    ): T {
        val lock = acquire(key)
        return synchronized(lock.monitor) {
            try {
                action()
            } finally {
                release(key, lock)
            }
        }
    }

    private fun acquire(key: String): LockReference =
        locks.compute(key) { _, existing ->
            val lock = existing ?: LockReference()
            lock.users++
            lock
        }!!

    private fun release(
        key: String,
        lock: LockReference,
    ) {
        locks.compute(key) { _, existing ->
            if (existing !== lock) {
                existing
            } else {
                lock.users--
                if (lock.users == 0) null else lock
            }
        }
    }

    private class LockReference {
        val monitor = Any()
        var users = 0
    }
}
