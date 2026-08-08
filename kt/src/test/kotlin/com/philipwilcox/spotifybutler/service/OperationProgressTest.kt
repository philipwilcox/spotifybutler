package com.philipwilcox.spotifybutler.service

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OperationProgressTest {
    @Test
    fun libraryRefreshReportsSourceAndPageProgressSeparatelyFromExternalCalls() {
        val updates = mutableListOf<OperationProgressUpdate>()
        val pageLogFields = mutableListOf<String>()

        OperationProgress.with("library_refresh", "flow-library", progressSink = updates::add) { progress ->
            progress.libraryRefreshSourceStarted()
            val catalogCall = progress.beginExternalCall()
            progress.finishExternalCall(catalogCall)
            progress.libraryRefreshSourceFinished(succeeded = true)
            progress.libraryRefreshSourcesDiscovered(5)
            progress.libraryRefreshSourceStarted()
            repeat(2) {
                val page = progress.beginExternalCall()
                progress.libraryRefreshPageTotalDiscovered(2)
                progress.finishExternalCall(page)
                pageLogFields += progress.logFields()
            }
            progress.libraryRefreshSourceFinished(succeeded = true)
        }

        val pageUpdates = updates.filter { it.action == "Spotify action completed" }.mapNotNull { it.libraryRefresh }
        assertEquals(listOf(1, 2), pageUpdates.map { it.activeSourceCompletedPages })
        assertEquals(listOf(2, 2), pageUpdates.map { it.activeSourceTotalPages })
        assertContains(pageLogFields[0], "sourcesCompleted=1 sourcesTotal=5")
        assertContains(pageLogFields[0], "activePages=1 activePagesTotal=2")
        assertContains(pageLogFields[1], "activePages=2 activePagesTotal=2")
        assertTrue(
            updates.all { update ->
                update.totalExternalCalls == null || update.completedExternalCalls <= update.totalExternalCalls
            },
        )
        assertEquals(LibraryRefreshProgress(2, 5), updates.last().libraryRefresh)
    }

    @Test
    fun reportsStepTimingAndKnownTotals() {
        OperationProgress.with("publish-adopt", "flow-1", expectedExternalCalls = 2) { log ->
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
    fun preservesUnknownTotalsForIndeterminateOperations() {
        OperationProgress.with("publish-plan", "flow-2") { log ->
            val first = log.beginExternalCall()
            val timing = log.finishExternalCall(first)

            assertEquals(null, timing.totalSteps)
            assertContains(log.logFields(timing), "step=1 ")
            assertContains(log.logFields(), "step=1 ")
        }
    }
}
