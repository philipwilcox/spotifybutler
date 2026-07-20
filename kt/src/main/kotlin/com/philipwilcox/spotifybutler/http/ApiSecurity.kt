package com.philipwilcox.spotifybutler.http

import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private const val OPAQUE_TOKEN_BYTES = 32
private const val OPERATION_ID_RANDOM_BOUND = 1000
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
}

enum class OperationStatus {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
}

data class OperationRecord(
    val id: String,
    val ownerSpotifyUserId: String,
    val type: String,
    val idempotencyKey: String,
    val requestFingerprint: String,
    val status: OperationStatus,
    val createdAt: Instant,
    val finishedAt: Instant?,
    val result: String?,
    val errorCode: String?,
    val errorMessage: String?,
)

sealed interface IdempotencyLookup {
    data object New : IdempotencyLookup

    data class Existing(
        val operation: OperationRecord,
    ) : IdempotencyLookup

    data object Conflict : IdempotencyLookup
}

class OperationStore(
    private val clock: Clock = Clock.systemUTC(),
) {
    private val operations = ConcurrentHashMap<String, OperationRecord>()
    private val random = SecureRandom()

    fun findById(id: String): OperationRecord? = operations[id]

    fun lookup(
        ownerSpotifyUserId: String,
        type: String,
        idempotencyKey: String,
        requestFingerprint: String,
    ): IdempotencyLookup {
        val existing =
            operations.values.firstOrNull {
                it.ownerSpotifyUserId == ownerSpotifyUserId &&
                    it.type == type &&
                    it.idempotencyKey == idempotencyKey
            } ?: return IdempotencyLookup.New
        return if (existing.requestFingerprint == requestFingerprint) {
            IdempotencyLookup.Existing(existing)
        } else {
            IdempotencyLookup.Conflict
        }
    }

    fun create(
        ownerSpotifyUserId: String,
        type: String,
        idempotencyKey: String,
        requestFingerprint: String,
    ): OperationRecord {
        val operation =
            OperationRecord(
                id = "op-${UUID.randomUUID()}-${random.nextInt(OPERATION_ID_RANDOM_BOUND)}",
                ownerSpotifyUserId = ownerSpotifyUserId,
                type = type,
                idempotencyKey = idempotencyKey,
                requestFingerprint = requestFingerprint,
                status = OperationStatus.QUEUED,
                createdAt = clock.instant(),
                finishedAt = null,
                result = null,
                errorCode = null,
                errorMessage = null,
            )
        operations[operation.id] = operation
        return operation
    }

    fun running(operation: OperationRecord): OperationRecord = update(operation.copy(status = OperationStatus.RUNNING))

    fun succeeded(
        operation: OperationRecord,
        result: String,
    ): OperationRecord =
        update(
            operation.copy(
                status = OperationStatus.SUCCEEDED,
                finishedAt = clock.instant(),
                result = result,
            ),
        )

    fun failed(
        operation: OperationRecord,
        code: String,
        message: String,
    ): OperationRecord =
        update(
            operation.copy(
                status = OperationStatus.FAILED,
                finishedAt = clock.instant(),
                errorCode = code,
                errorMessage = message,
            ),
        )

    fun list(
        ownerSpotifyUserId: String,
        status: OperationStatus?,
        type: String?,
        limit: Int,
    ): List<OperationRecord> =
        operations.values
            .asSequence()
            .filter { it.ownerSpotifyUserId == ownerSpotifyUserId }
            .filter { status == null || it.status == status }
            .filter { type == null || it.type == type }
            .sortedByDescending(OperationRecord::createdAt)
            .take(limit)
            .toList()

    private fun update(operation: OperationRecord): OperationRecord {
        operations[operation.id] = operation
        return operation
    }
}
