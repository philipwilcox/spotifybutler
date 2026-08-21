package com.philipwilcox.spotifybutler.service

import java.util.UUID

data class OperationStepTiming(
    val step: Int,
    val totalSteps: Int?,
    val stepDurationMs: Long,
    val elapsedMs: Long,
)

data class LibraryRefreshProgress(
    val completedSources: Int,
    val totalSources: Int,
    val activeSourceCompletedPages: Int? = null,
    val activeSourceTotalPages: Int? = null,
)

data class OperationProgressUpdate(
    val action: String,
    val completedExternalCalls: Int,
    val totalExternalCalls: Int?,
    val libraryRefresh: LibraryRefreshProgress? = null,
    val bulkRepublish: Any? = null,
)

class OperationProgress private constructor(
    val operation: String,
    val flowId: String,
    private val startedAtNanos: Long,
    expectedExternalCalls: Int?,
    private val progressSink: ((OperationProgressUpdate) -> Unit)?,
) {
    private var completedExternalCalls = 0
    private var expectedCalls: Int? = expectedExternalCalls
    private var lastTiming: OperationStepTiming? = null
    private var libraryRefreshTotalSources: Int? = null
    private var libraryRefreshCompletedSources = 0
    private var libraryRefreshActiveSource = false
    private var activeSourceCompletedPages: Int? = null
    private var activeSourceTotalPages: Int? = null

    fun actionStarted(
        action: String,
        bulkRepublish: Any? = null,
    ) {
        report(action, bulkRepublish)
    }

    fun setExpectedExternalCalls(total: Int) {
        if (total > 0) expectedCalls = total
        report("Spotify page estimate available")
    }

    fun libraryRefreshSourcesDiscovered(totalSources: Int) {
        require(
            totalSources >= libraryRefreshCompletedSources,
        ) { "Library source total cannot be below completed sources" }
        libraryRefreshTotalSources = totalSources
        report("Library source count available")
    }

    fun libraryRefreshSourceStarted() {
        libraryRefreshActiveSource = true
        activeSourceCompletedPages = null
        activeSourceTotalPages = null
        report("Refreshing library source")
    }

    fun libraryRefreshSourceFinished(succeeded: Boolean) {
        libraryRefreshCompletedSources++
        libraryRefreshActiveSource = false
        activeSourceCompletedPages = null
        activeSourceTotalPages = null
        report(if (succeeded) "Library source refreshed" else "Library source failed")
    }

    fun libraryRefreshPageTotalDiscovered(totalPages: Int) {
        if (totalPages > 0 && activeSourceTotalPages == null) {
            activeSourceCompletedPages = 0
            activeSourceTotalPages = totalPages
            report("Spotify page estimate available")
        }
    }

    val isLibraryRefreshSourceActive: Boolean get() = libraryRefreshActiveSource

    fun beginExternalCall(action: String = "Calling Spotify"): OperationExternalCall {
        actionStarted(action)
        return OperationExternalCall(completedExternalCalls + 1, System.nanoTime())
    }

    fun finishExternalCall(call: OperationExternalCall): OperationStepTiming {
        completedExternalCalls = call.step
        val now = System.nanoTime()
        return OperationStepTiming(
            step = call.step,
            totalSteps = expectedCalls,
            stepDurationMs = (now - call.startedAtNanos) / NANOS_PER_MILLISECOND,
            elapsedMs = (now - startedAtNanos) / NANOS_PER_MILLISECOND,
        ).also {
            lastTiming = it
            if (activeSourceCompletedPages != null) activeSourceCompletedPages = activeSourceCompletedPages!! + 1
            report("Spotify action completed")
        }
    }

    fun retrying(action: String = "Rate limited; retrying current Spotify action") = actionStarted(action)

    @Synchronized
    private fun report(
        action: String,
        bulkRepublish: Any? = null,
    ) {
        progressSink?.invoke(
            OperationProgressUpdate(
                action,
                completedExternalCalls,
                expectedCalls,
                libraryRefreshTotalSources?.let {
                    LibraryRefreshProgress(
                        libraryRefreshCompletedSources,
                        it,
                        activeSourceCompletedPages,
                        activeSourceTotalPages,
                    )
                },
                bulkRepublish,
            ),
        )
    }

    fun logFields(timing: OperationStepTiming? = lastTiming): String {
        val stepText = timing?.let { "step=${it.step}${it.totalSteps?.let { total -> " of $total" }.orEmpty()}" }
        val durationText =
            timing?.let { " stepDurationMs=${it.stepDurationMs} elapsedMs=${it.elapsedMs}" }.orEmpty()
        val progressText = stepText ?: "stepsCompleted=$completedExternalCalls"
        val libraryProgressText =
            libraryRefreshTotalSources
                ?.let { totalSources ->
                    " sourcesCompleted=$libraryRefreshCompletedSources sourcesTotal=$totalSources" +
                        if (activeSourceCompletedPages != null && activeSourceTotalPages != null) {
                            " activePages=$activeSourceCompletedPages activePagesTotal=$activeSourceTotalPages"
                        } else {
                            ""
                        }
                }.orEmpty()
        return "flowId=$flowId operation=$operation $progressText$durationText$libraryProgressText"
    }

    companion object {
        private const val NANOS_PER_MILLISECOND = 1_000_000L
        private val current = ThreadLocal<OperationProgress?>()

        fun current(): OperationProgress? = current.get()

        fun <T> with(
            operation: String,
            flowId: String = UUID.randomUUID().toString(),
            expectedExternalCalls: Int? = null,
            progressSink: ((OperationProgressUpdate) -> Unit)? = null,
            block: (OperationProgress) -> T,
        ): T {
            val previous = current.get()
            val context = OperationProgress(operation, flowId, System.nanoTime(), expectedExternalCalls, progressSink)
            current.set(context)
            return try {
                block(context)
            } finally {
                current.set(previous)
            }
        }
    }
}

data class OperationExternalCall(
    val step: Int,
    val startedAtNanos: Long,
)
