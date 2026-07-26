package com.philipwilcox.spotifybutler.http

import com.philipwilcox.spotifybutler.db.SpotifyStore
import com.philipwilcox.spotifybutler.db.StoredBrowserSession
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

private const val OPAQUE_TOKEN_BYTES = 32
private const val SESSION_LIFETIME_DAYS = 180L
private const val DEFAULT_ACCESS_TOKEN_LIFETIME_SECONDS = 3_600L

data class TokenRefreshResult(
    val accessToken: String,
    val expiresInSeconds: Long,
    val refreshToken: String?,
)

data class ButlerSession(
    val id: String,
    val ownerSpotifyUserId: String,
    val accessToken: String,
    val refreshToken: String?,
    val csrfToken: String,
    val expiresAt: Instant,
    val accessTokenExpiresAt: Instant,
)

@Suppress("TooManyFunctions")
class SessionStore(
    private val clock: Clock = Clock.systemUTC(),
    private val lifetime: Duration = Duration.ofDays(SESSION_LIFETIME_DAYS),
    private val authStore: SpotifyStore? = null,
) {
    private val sessions = ConcurrentHashMap<String, ButlerSession>()
    private val refreshLocks = ConcurrentHashMap<String, RefreshLock>()
    private val random = SecureRandom()

    fun create(
        ownerSpotifyUserId: String,
        accessToken: String,
        refreshToken: String?,
        accessTokenLifetimeSeconds: Long = DEFAULT_ACCESS_TOKEN_LIFETIME_SECONDS,
    ): ButlerSession {
        val now = clock.instant()
        val durableRefreshToken = refreshToken ?: authStore?.spotifyAuthGrant(ownerSpotifyUserId)?.refreshToken
        val existingGrant = authStore?.spotifyAuthGrant(ownerSpotifyUserId)
        if (durableRefreshToken != null && authStore != null) {
            authStore.saveSpotifyAuthGrant(
                ownerSpotifyUserId,
                durableRefreshToken,
                existingGrant?.authorizedAtMillis ?: now.toEpochMilli(),
                existingGrant?.lastRefreshedAtMillis,
            )
        }
        val session = newSession(ownerSpotifyUserId, accessToken, durableRefreshToken, accessTokenLifetimeSeconds, now)
        sessions[session.id] = session
        persist(session, now)
        return session
    }

    fun find(sessionId: String?): ButlerSession? {
        if (sessionId.isNullOrBlank()) return null
        val session = sessions[sessionId] ?: return null
        if (!session.expiresAt.isAfter(clock.instant())) {
            remove(sessionId)
            return null
        }
        touch(session)
        return session
    }

    fun rehydrate(
        sessionId: String?,
        refresh: (String) -> TokenRefreshResult,
        verifySpotifyUserId: (String) -> String,
    ): ButlerSession? {
        if (sessionId.isNullOrBlank() || authStore == null) return null
        val stored = authStore.browserSession(sessionId) ?: return null
        if (stored.expiresAtMillis <= clock.instant().toEpochMilli()) {
            authStore.deleteBrowserSession(sessionId)
            return null
        }
        return withRefreshLock(stored.spotifyUserId) {
            val current = authStore.browserSession(sessionId) ?: return@withRefreshLock null
            val grant = authStore.spotifyAuthGrant(current.spotifyUserId) ?: return@withRefreshLock null
            val result = refresh(grant.refreshToken)
            require(verifySpotifyUserId(result.accessToken) == current.spotifyUserId) {
                "Spotify identity did not match the stored authentication grant"
            }
            val now = clock.instant()
            val replacementRefreshToken = result.refreshToken ?: grant.refreshToken
            authStore.updateSpotifyAuthGrantRefreshToken(
                current.spotifyUserId,
                replacementRefreshToken,
                now.toEpochMilli(),
            )
            val session =
                newSession(
                    current.spotifyUserId,
                    result.accessToken,
                    replacementRefreshToken,
                    result.expiresInSeconds,
                    now,
                ).copy(expiresAt = Instant.ofEpochMilli(current.expiresAtMillis))
            sessions.remove(sessionId)
            sessions[session.id] = session
            authStore.rotateBrowserSession(
                sessionId,
                StoredBrowserSession(
                    session.id,
                    session.ownerSpotifyUserId,
                    current.createdAtMillis,
                    now.toEpochMilli(),
                    current.expiresAtMillis,
                ),
            )
            session
        }
    }

    fun rotate(
        session: ButlerSession,
        accessToken: String,
        refreshToken: String?,
        accessTokenLifetimeSeconds: Long = DEFAULT_ACCESS_TOKEN_LIFETIME_SECONDS,
    ): ButlerSession =
        withRefreshLock(session.id) {
            val current = sessions[session.id] ?: throw IllegalStateException("The session was already invalidated")
            val durableRefreshToken = refreshToken ?: current.refreshToken
            persistGrant(current.ownerSpotifyUserId, durableRefreshToken)
            sessions.remove(current.id)
            authStore?.deleteBrowserSession(current.id)
            create(current.ownerSpotifyUserId, accessToken, durableRefreshToken, accessTokenLifetimeSeconds)
        }

    fun refresh(
        session: ButlerSession,
        exchange: (String) -> Pair<String, String?>,
    ): ButlerSession =
        refreshWithResult(session) { token ->
            exchange(token).let { (accessToken, refreshToken) ->
                TokenRefreshResult(accessToken, DEFAULT_ACCESS_TOKEN_LIFETIME_SECONDS, refreshToken)
            }
        }

    fun refreshWithResult(
        session: ButlerSession,
        exchange: (String) -> TokenRefreshResult,
    ): ButlerSession =
        withRefreshLock(session.id) {
            val current = sessions[session.id] ?: throw IllegalStateException("The session was already invalidated")
            val refreshToken = current.refreshToken ?: throw IllegalStateException("No refresh token is available")
            val result = exchange(refreshToken)
            rotate(current, result.accessToken, result.refreshToken ?: refreshToken, result.expiresInSeconds)
        }

    fun refreshIfNeeded(
        session: ButlerSession,
        threshold: Duration,
        exchange: (String) -> TokenRefreshResult,
    ): ButlerSession =
        withRefreshLock(session.id) {
            val current = sessions[session.id] ?: throw IllegalStateException("The session was already invalidated")
            if (current.accessTokenExpiresAt.isAfter(clock.instant().plus(threshold))) return@withRefreshLock current
            val refreshToken = current.refreshToken ?: return@withRefreshLock current
            val result = exchange(refreshToken)
            val now = clock.instant()
            val effectiveRefreshToken = result.refreshToken ?: refreshToken
            persistGrant(current.ownerSpotifyUserId, effectiveRefreshToken)
            val updated =
                current.copy(
                    accessToken = result.accessToken,
                    refreshToken = effectiveRefreshToken,
                    accessTokenExpiresAt = now.plusSeconds(result.expiresInSeconds),
                )
            sessions[current.id] = updated
            touch(updated)
            updated
        }

    fun remove(sessionId: String?) {
        if (sessionId.isNullOrBlank()) return
        sessions.remove(sessionId)
        authStore?.deleteBrowserSession(sessionId)
    }

    fun signOut(session: ButlerSession) {
        sessions.entries.removeIf { it.value.ownerSpotifyUserId == session.ownerSpotifyUserId }
        authStore?.deleteAuthenticationState(session.ownerSpotifyUserId)
    }

    fun invalidate(spotifyUserId: String) {
        sessions.entries.removeIf { it.value.ownerSpotifyUserId == spotifyUserId }
        authStore?.deleteAuthenticationState(spotifyUserId)
    }

    fun invalidateSession(sessionId: String?) {
        if (sessionId.isNullOrBlank()) return
        val owner = authStore?.browserSession(sessionId)?.spotifyUserId
        sessions.remove(sessionId)
        if (owner != null) invalidate(owner) else authStore?.deleteBrowserSession(sessionId)
    }

    private fun persistGrant(
        ownerSpotifyUserId: String,
        refreshToken: String?,
    ) {
        val store = authStore ?: return
        if (refreshToken == null) return
        val existing = store.spotifyAuthGrant(ownerSpotifyUserId)
        store.saveSpotifyAuthGrant(
            ownerSpotifyUserId,
            refreshToken,
            existing?.authorizedAtMillis ?: clock.instant().toEpochMilli(),
            clock.instant().toEpochMilli(),
        )
    }

    private fun persist(
        session: ButlerSession,
        now: Instant,
    ) {
        authStore?.saveBrowserSession(
            StoredBrowserSession(
                session.id,
                session.ownerSpotifyUserId,
                now.toEpochMilli(),
                now.toEpochMilli(),
                session.expiresAt.toEpochMilli(),
            ),
        )
    }

    private fun touch(session: ButlerSession) {
        authStore?.touchBrowserSession(session.id, clock.instant().toEpochMilli())
    }

    private fun newSession(
        ownerSpotifyUserId: String,
        accessToken: String,
        refreshToken: String?,
        accessTokenLifetimeSeconds: Long,
        now: Instant,
    ): ButlerSession =
        ButlerSession(
            id = opaqueToken(),
            ownerSpotifyUserId = ownerSpotifyUserId,
            accessToken = accessToken,
            refreshToken = refreshToken,
            csrfToken = opaqueToken(),
            expiresAt = now.plus(lifetime),
            accessTokenExpiresAt = now.plusSeconds(accessTokenLifetimeSeconds.coerceAtLeast(1)),
        )

    private fun opaqueToken(): String =
        ByteArray(OPAQUE_TOKEN_BYTES).also(random::nextBytes).let {
            Base64.getUrlEncoder().withoutPadding().encodeToString(it)
        }

    private fun <T> withRefreshLock(
        key: String,
        action: () -> T,
    ): T {
        val lock =
            refreshLocks.compute(key) { _, existing ->
                val value = existing ?: RefreshLock()
                value.users++
                value
            }!!
        return synchronized(lock.monitor) {
            try {
                action()
            } finally {
                refreshLocks.compute(key) { _, existing ->
                    if (existing !== lock) {
                        existing
                    } else {
                        lock.users--
                        if (lock.users == 0) null else lock
                    }
                }
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
