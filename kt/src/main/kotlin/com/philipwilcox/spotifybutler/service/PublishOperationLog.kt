package com.philipwilcox.spotifybutler.service

import java.util.UUID

data class PublishStepTiming(
    val step: Int,
    val totalSteps: Int?,
    val stepDurationMs: Long,
    val elapsedMs: Long,
)

class PublishOperationLog private constructor(
    val operation: String,
    val flowId: String,
    private val startedAtNanos: Long,
    expectedExternalCalls: Int?,
) {
    private var completedExternalCalls = 0
    private var expectedCalls: Int? = expectedExternalCalls
    private var lastTiming: PublishStepTiming? = null

    fun beginExternalCall(): PublishExternalCall = PublishExternalCall(completedExternalCalls + 1, System.nanoTime())

    fun finishExternalCall(call: PublishExternalCall): PublishStepTiming {
        completedExternalCalls = call.step
        val now = System.nanoTime()
        return PublishStepTiming(
            step = call.step,
            totalSteps = expectedCalls,
            stepDurationMs = (now - call.startedAtNanos) / NANOS_PER_MILLISECOND,
            elapsedMs = (now - startedAtNanos) / NANOS_PER_MILLISECOND,
        ).also { lastTiming = it }
    }

    fun setExpectedExternalCalls(total: Int) {
        require(total >= completedExternalCalls) { "Expected external calls cannot precede completed calls" }
        expectedCalls = total
        lastTiming = lastTiming?.copy(totalSteps = total)
    }

    fun setExpectedExternalCallsIfUnknown(total: Int) {
        if (expectedCalls == null) setExpectedExternalCalls(total)
    }

    fun logFields(timing: PublishStepTiming? = lastTiming): String {
        val stepText = timing?.let { "step=${it.step}${it.totalSteps?.let { total -> " of $total" }.orEmpty()}" }
        val durationText =
            timing?.let { " stepDurationMs=${it.stepDurationMs} elapsedMs=${it.elapsedMs}" }.orEmpty()
        val progressText = stepText ?: "stepsCompleted=$completedExternalCalls"
        return "flowId=$flowId operation=$operation $progressText$durationText"
    }

    companion object {
        private const val NANOS_PER_MILLISECOND = 1_000_000L
        private val current = ThreadLocal<PublishOperationLog?>()

        fun current(): PublishOperationLog? = current.get()

        fun <T> with(
            operation: String,
            flowId: String = UUID.randomUUID().toString(),
            expectedExternalCalls: Int? = null,
            block: (PublishOperationLog) -> T,
        ): T {
            val previous = current.get()
            val context = PublishOperationLog(operation, flowId, System.nanoTime(), expectedExternalCalls)
            current.set(context)
            return try {
                block(context)
            } finally {
                current.set(previous)
            }
        }
    }
}

data class PublishExternalCall(
    val step: Int,
    val startedAtNanos: Long,
)
