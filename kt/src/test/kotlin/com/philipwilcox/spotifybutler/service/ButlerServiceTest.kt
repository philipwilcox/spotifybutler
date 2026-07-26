package com.philipwilcox.spotifybutler.service

import com.philipwilcox.spotifybutler.db.SpotifyStore
import com.philipwilcox.spotifybutler.spotify.SpotifyCacheFetcher
import com.philipwilcox.spotifybutler.spotify.SpotifyCacheSnapshot
import com.philipwilcox.spotifybutler.spotify.SpotifyTrack
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ButlerServiceTest {
    @Test
    fun `completed cache is reused and all playlist plans are produced before mutation`() {
        val calls = mutableListOf<String>()
        withStore { store ->
            val cache = SpotifyCacheService(StaticFetcher(emptySnapshot()), store, fixedClock())
            store.replaceCache(emptySnapshot(), 1L)
            val result =
                ButlerService(
                    cacheService = cache,
                    planningService = PlaylistPlanningService(store),
                    mutationService = PlaylistMutationService(RecordingButlerMutationClient(calls)),
                    clock = fixedClock(),
                ).run("token", refresh = false)

            assertEquals(CacheLoadResult.SkippedExistingCache, result.sync)
            assertEquals(15, result.playlistPlans.size)
            assertEquals(15, result.playlistOutcomes.size)
            assertEquals((1..15).map { "create:$it" }, calls)
        }
    }

    @Test
    fun `dry run retains outcomes without playlist mutation calls`() {
        val calls = mutableListOf<String>()
        withStore { store ->
            store.replaceCache(emptySnapshot(), 1L)
            val result =
                ButlerService(
                    cacheService = SpotifyCacheService(StaticFetcher(emptySnapshot()), store, fixedClock()),
                    planningService = PlaylistPlanningService(store),
                    mutationService = PlaylistMutationService(RecordingButlerMutationClient(calls)),
                    clock = fixedClock(),
                    dryRun = true,
                ).run("token", refresh = false)

            assertEquals(15, result.playlistOutcomes.size)
            assertEquals(emptyList(), calls)
            assertEquals(true, result.playlistOutcomes.all { it.dryRun })
        }
    }

    @Test
    fun `refresh records the authenticated owner on the replacement cache`() {
        withStore { store ->
            ButlerService(
                cacheService = SpotifyCacheService(StaticFetcher(emptySnapshot()), store, fixedClock()),
                planningService = PlaylistPlanningService(store),
                mutationService = PlaylistMutationService(RecordingButlerMutationClient(mutableListOf())),
                clock = fixedClock(),
            ).run("token", refresh = true, ownerSpotifyUserId = "owner-1")

            assertEquals(
                "owner-1",
                store.sourceSnapshot("owner-1", CacheSourceKey.SAVED_TRACKS).ownerSpotifyUserId,
            )
        }
    }

    @Test
    fun `sync failure prevents planning and mutation`() {
        val calls = mutableListOf<String>()
        withStore { store ->
            val failingFetcher = FailingFetcher()
            val service =
                ButlerService(
                    cacheService = SpotifyCacheService(failingFetcher, store, fixedClock()),
                    planningService = PlaylistPlanningService(store),
                    mutationService = PlaylistMutationService(RecordingButlerMutationClient(calls)),
                    clock = fixedClock(),
                )

            assertFailsWith<IllegalStateException> { service.run("token", refresh = true) }
            assertEquals(emptyList(), calls)
        }
    }

    private fun withStore(block: (SpotifyStore) -> Unit) {
        val path = Files.createTempDirectory("butler-service-").resolve("cache.db")
        SpotifyStore.open(path).use(block)
    }

    private fun fixedClock() = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)

    private fun emptySnapshot() = SpotifyCacheSnapshot(emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
}

private class StaticFetcher(
    private val snapshot: SpotifyCacheSnapshot,
) : SpotifyCacheFetcher {
    override fun fetchCache(accessToken: String): SpotifyCacheSnapshot = snapshot
}

private class FailingFetcher : SpotifyCacheFetcher {
    override fun fetchCache(accessToken: String): SpotifyCacheSnapshot = error("sync failed")
}

private class RecordingButlerMutationClient(
    private val calls: MutableList<String>,
) : PlaylistMutationClient {
    private var nextId = 0

    override fun createPlaylist(name: String): String {
        nextId++
        calls += "create:$nextId"
        return "playlist-$nextId"
    }

    override fun addTracks(
        playlistId: String,
        tracks: List<SpotifyTrack>,
    ) {
        calls += "add:$playlistId"
    }

    override fun replaceTracks(
        playlistId: String,
        tracks: List<SpotifyTrack>,
    ) {
        calls += "replace:$playlistId"
    }
}
