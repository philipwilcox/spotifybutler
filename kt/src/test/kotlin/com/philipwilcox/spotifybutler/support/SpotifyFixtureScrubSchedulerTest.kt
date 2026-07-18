package com.philipwilcox.spotifybutler.support

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpotifyFixtureScrubSchedulerTest {
    @Test
    fun `one endpoint keeps all scrub workers busy and preserves page order`() {
        assertPageScheduling((0 until 30).map { index -> "/v1/me/top/tracks?offset=$index" })
    }

    @Test
    fun `many endpoints keep all scrub workers busy and preserve page order`() {
        assertPageScheduling(
            (0 until 15).flatMap { endpoint ->
                (0 until 2).map { page -> "/v1/endpoint-$endpoint?offset=$page" }
            },
        )
    }

    private fun assertPageScheduling(paths: List<String>) {
        val workerCount = 6
        val fixture =
            SpotifyFixture(
                schemaVersion = 1,
                name = "scheduler-test",
                responses = paths.mapIndexed(::fixtureResponse),
                expectedTables = emptyExpectedTables(),
            )
        val prepared = prepareFixture("run-scheduler-test", fixture)
        val started = CountDownLatch(workerCount)
        val release = CountDownLatch(1)
        val active = AtomicInteger(0)
        val maximumActive = AtomicInteger(0)
        val executor = Executors.newFixedThreadPool(workerCount)
        var scrubbed: List<SpotifyFixture>? = null
        var failure: Throwable? = null
        val coordinator =
            thread(start = true) {
                try {
                    scrubbed =
                        scrubPreparedFixtures(listOf(prepared), workerCount, executor) { element, _ ->
                            val current = active.incrementAndGet()
                            maximumActive.updateAndGet { previous -> maxOf(previous, current) }
                            started.countDown()
                            try {
                                release.await(5, TimeUnit.SECONDS)
                                element
                            } finally {
                                active.decrementAndGet()
                            }
                        }
                } catch (throwable: Throwable) {
                    failure = throwable
                }
            }

        try {
            assertTrue(started.await(5, TimeUnit.SECONDS), "worker pool did not fill before release")
            assertEquals(workerCount, maximumActive.get())
        } finally {
            release.countDown()
            coordinator.join(5_000)
            executor.shutdownNow()
        }

        failure?.let { throw it }
        assertEquals(
            paths,
            requireNotNull(scrubbed).single().responses.map { response -> response.path },
        )
    }

    private fun fixtureResponse(
        index: Int,
        path: String,
    ): SpotifyFixtureResponse =
        SpotifyFixtureResponse(
            method = "GET",
            path = path,
            status = 200,
            body =
                buildJsonObject {
                    put("items", buildJsonArray { })
                    put("page_id", "page-$index")
                },
        )

    private fun emptyExpectedTables(): ExpectedTables =
        ExpectedTables(
            savedTracks = emptyList(),
            topTracks = emptyList(),
            topArtists = emptyList(),
            playlists = emptyList(),
            playlistTracks = emptyList(),
            syncStatus = emptyList(),
        )
}
