package com.philipwilcox.spotifybutler.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlaylistGenerationTestReporterTest {
    @Test
    fun `ordered diff reports first difference missing unexpected and moved tracks`() {
        val diff =
            orderedTrackDiff(
                expected = listOf("one", "two", "three"),
                actual = listOf("three", "two", "four"),
            )

        assertEquals(0, diff.firstDifference)
        assertEquals(listOf("one", "three"), diff.missing)
        assertEquals(listOf("three", "four"), diff.unexpected)
        assertEquals(listOf("three"), diff.moved)
    }

    @Test
    fun `ordered diff reports no difference for identical duplicate sequences`() {
        val diff =
            orderedTrackDiff(
                expected = listOf("one", "one", "two"),
                actual = listOf("one", "one", "two"),
            )

        assertNull(diff.firstDifference)
        assertEquals(emptyList(), diff.missing)
        assertEquals(emptyList(), diff.unexpected)
        assertEquals(emptyList(), diff.moved)
    }
}
