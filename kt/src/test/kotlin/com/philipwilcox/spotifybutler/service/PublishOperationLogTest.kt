package com.philipwilcox.spotifybutler.service

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PublishOperationLogTest {
    @Test
    fun reportsStepTimingAndKnownTotals() {
        PublishOperationLog.with("publish-adopt", "flow-1", expectedExternalCalls = 2) { log ->
            val first = log.beginExternalCall()
            val timing = log.finishExternalCall(first)

            assertEquals(1, timing.step)
            assertEquals(2, timing.totalSteps)
            assertTrue(timing.stepDurationMs >= 0)
            assertTrue(timing.elapsedMs >= timing.stepDurationMs)
            assertContains(log.logFields(timing), "flowId=flow-1")
            assertContains(log.logFields(timing), "step=1 of 2")
        }
    }

    @Test
    fun reportsUnknownTotalsUntilPaginationDeterminesTheCount() {
        PublishOperationLog.with("publish-plan", "flow-2") { log ->
            val first = log.beginExternalCall()
            val timing = log.finishExternalCall(first)

            assertEquals(null, timing.totalSteps)
            assertContains(log.logFields(timing), "step=1 ")
            log.setExpectedExternalCallsIfUnknown(3)
            assertContains(log.logFields(), "step=1 of 3")
        }
    }
}
