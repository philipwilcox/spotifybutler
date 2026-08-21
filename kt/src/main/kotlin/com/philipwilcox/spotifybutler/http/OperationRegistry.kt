@file:Suppress("TooGenericExceptionCaught")

package com.philipwilcox.spotifybutler.http

import com.philipwilcox.spotifybutler.service.OperationProgress
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class OperationAlreadyRunningException : RuntimeException()

class OperationRegistry {
    private val logger = KotlinLogging.logger {}
    private val executor: ExecutorService =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "butler-operation")
        }
    private var record: OperationRecord? = null
    private var closed = false

    @Synchronized
    fun start(
        ownerSpotifyUserId: String,
        kind: OperationKind,
        requestId: String,
        totalSteps: Int?,
        task: () -> OperationResultWire,
    ): OperationAcceptedWire {
        if (closed) error("Operation registry is closed")
        if (record?.status?.value?.phase in
            setOf(OperationPhase.queued, OperationPhase.running)
        ) {
            throw OperationAlreadyRunningException()
        }
        val id = "op-${UUID.randomUUID()}"
        val state =
            MutableStateFlow(OperationStatusWire(id, kind, OperationPhase.queued, "Waiting to start", 0, totalSteps))
        val newRecord = OperationRecord(ownerSpotifyUserId, id, requestId, kind, totalSteps, state)
        record = newRecord
        executor.submit {
            update(newRecord, OperationPhase.running, "Starting operation", 0)
            try {
                val result =
                    OperationProgress.with(
                        operation = kind.name,
                        expectedExternalCalls = newRecord.totalSteps,
                        progressSink = { progress ->
                            val bulkProgress = progress.bulkRepublish as? BulkRepublishProgressWire
                            if (progress.libraryRefresh == null) {
                                newRecord.totalSteps = progress.totalExternalCalls ?: newRecord.totalSteps
                            }
                            update(
                                newRecord,
                                OperationPhase.running,
                                progress.action,
                                bulkProgress?.completedItems ?: progress.completedExternalCalls,
                                libraryRefreshProgress =
                                    progress.libraryRefresh?.let {
                                        LibraryRefreshProgressWire(
                                            it.completedSources,
                                            it.totalSources,
                                            it.activeSourceCompletedPages,
                                            it.activeSourceTotalPages,
                                        )
                                    },
                                bulkRepublishProgress = bulkProgress,
                            )
                        },
                    ) { task() }
                require(resultMatches(kind, result))
                update(
                    newRecord,
                    OperationPhase.succeeded,
                    "Completed",
                    newRecord.totalSteps ?: newRecord.status.value.completedSteps,
                    result = result,
                    libraryRefreshProgress = newRecord.status.value.libraryRefreshProgress,
                    bulkRepublishProgress = newRecord.status.value.bulkRepublishProgress,
                )
            } catch (failure: Exception) {
                logger.error(failure) { "Operation failed: operationId=$id requestId=$requestId" }
                val error =
                    if (failure is com.philipwilcox.spotifybutler.service.DestinationConflictException) {
                        OperationFailureWire("destination_conflict", failure.message ?: "Destination has changed")
                    } else {
                        OperationFailureWire("operation_failed", "The operation could not be completed. Please retry.")
                    }
                update(
                    newRecord,
                    OperationPhase.failed,
                    "Operation failed",
                    newRecord.status.value.completedSteps,
                    error = error,
                    libraryRefreshProgress = newRecord.status.value.libraryRefreshProgress,
                    bulkRepublishProgress = newRecord.status.value.bulkRepublishProgress,
                )
            }
        }
        return OperationAcceptedWire(id, kind)
    }

    @Synchronized
    fun updates(
        ownerSpotifyUserId: String,
        operationId: String,
    ): StateFlow<OperationStatusWire>? =
        record?.takeIf { it.ownerSpotifyUserId == ownerSpotifyUserId && it.operationId == operationId }?.status

    @Synchronized fun close() {
        closed = true
        executor.shutdown()
    }

    private fun update(
        record: OperationRecord,
        phase: OperationPhase,
        action: String,
        completed: Int,
        result: OperationResultWire? = null,
        error: OperationFailureWire? = null,
        libraryRefreshProgress: LibraryRefreshProgressWire? = null,
        bulkRepublishProgress: BulkRepublishProgressWire? = null,
    ) {
        record.status.value =
            OperationStatusWire(
                record.operationId,
                record.kind,
                phase,
                action,
                completed,
                record.totalSteps,
                result,
                error,
                libraryRefreshProgress,
                bulkRepublishProgress,
            )
    }

    private fun resultMatches(
        kind: OperationKind,
        result: OperationResultWire,
    ): Boolean =
        when (kind) {
            OperationKind.LIBRARY_REFRESH -> result is LibraryRefreshResultWire
            OperationKind.PUBLISH_PLAN -> result is PublishPlanResultWire
            OperationKind.PUBLISH_CREATE, OperationKind.PUBLISH_ADOPT -> result is PublishDestinationResultWire
            OperationKind.DESTINATION_SYNC -> result is DestinationSyncResultWire
            OperationKind.BULK_REPUBLISH_PLAN -> result is BulkRepublishPlanResultWire
            OperationKind.BULK_REPUBLISH -> result is BulkRepublishResultWire
        }

    private data class OperationRecord(
        val ownerSpotifyUserId: String,
        val operationId: String,
        val requestId: String,
        val kind: OperationKind,
        @Volatile var totalSteps: Int?,
        val status: MutableStateFlow<OperationStatusWire>,
    )
}
