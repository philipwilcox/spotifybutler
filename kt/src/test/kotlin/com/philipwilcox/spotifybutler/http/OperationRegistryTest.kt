package com.philipwilcox.spotifybutler.http

import com.philipwilcox.spotifybutler.service.OperationProgress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OperationRegistryTest {
    @Test
    fun publishPlanReportsItsDiscoveredTotal() {
        val registry = OperationRegistry()
        val estimateReported = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val accepted =
            registry.start("owner", OperationKind.PUBLISH_PLAN, "request", null) {
                val progress = requireNotNull(OperationProgress.current())
                progress.setExpectedExternalCalls(1)
                val call = progress.beginExternalCall()
                progress.finishExternalCall(call)
                estimateReported.countDown()
                assertTrue(finish.await(1, TimeUnit.SECONDS))
                PublishPlanResultWire(
                    PublishPlanWire("definition", "Playlist", "create", emptyList(), null, "flow-1"),
                )
            }

        assertTrue(estimateReported.await(1, TimeUnit.SECONDS))
        val updates = requireNotNull(registry.updates("owner", accepted.operationId))
        assertEquals(OperationPhase.running, updates.value.phase)
        assertEquals(1, updates.value.completedSteps)
        assertEquals(1, updates.value.totalSteps)

        finish.countDown()
        assertTrue(waitFor { updates.value.phase == OperationPhase.succeeded })
        assertEquals(1, updates.value.completedSteps)
        assertEquals(1, updates.value.totalSteps)
        registry.close()
    }

    private fun waitFor(condition: () -> Boolean): Boolean {
        repeat(100) {
            if (condition()) return true
            Thread.sleep(10)
        }
        return condition()
    }
}
