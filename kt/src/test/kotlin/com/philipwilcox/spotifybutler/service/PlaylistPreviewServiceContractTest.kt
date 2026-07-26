package com.philipwilcox.spotifybutler.service

import com.philipwilcox.spotifybutler.db.SpotifyStore
import com.philipwilcox.spotifybutler.spotify.SavedTrack
import com.philipwilcox.spotifybutler.spotify.SpotifyCacheSnapshot
import com.philipwilcox.spotifybutler.spotify.SpotifyTrack
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class PlaylistPreviewServiceContractTest {
    @Test
    fun repeatedPreviewAndDatabaseReopenKeepExactOrder() {
        val path = Files.createTempDirectory("preview-contract-").resolve("cache.db")
        val snapshot =
            SpotifyCacheSnapshot(
                savedTracks =
                    listOf("one", "two", "three", "four").mapIndexed { index, id ->
                        SavedTrack(
                            "2026-01-0" + (index + 1) + "T00:00:00Z",
                            track(id),
                        )
                    },
                topTracks = emptyList(),
                topArtists = emptyList(),
                playlists = emptyList(),
                playlistTracks = emptyList(),
            )
        val first =
            SpotifyStore.open(path).use { store ->
                store.replaceCache(snapshot, 10L, "owner")
                PlaylistPreviewService(store).preview("RECENT_LIKED_100", "owner", "fixed-seed")
            }
        val second =
            SpotifyStore.openReadOnly(path).use { store ->
                PlaylistPreviewService(store).preview("RECENT_LIKED_100", "owner", "fixed-seed")
            }
        assertEquals(first.generatedTrackIds, second.generatedTrackIds)
        assertEquals(first.recipeRevision, second.recipeRevision)
        assertEquals(first.sourceDependencies, second.sourceDependencies)
        assertNotEquals(PreviewStatus.UNAVAILABLE, first.status)
    }

    @Test
    fun differentSeedsAreServerEvaluatedWithoutChangingSourceRows() {
        val path = Files.createTempDirectory("preview-seed-").resolve("cache.db")
        SpotifyStore.open(path).use { store ->
            store.replaceCache(
                SpotifyCacheSnapshot(
                    listOf(SavedTrack("2026-01-01T00:00:00Z", track("one"))),
                    emptyList(),
                    emptyList(),
                    emptyList(),
                    emptyList(),
                ),
                10L,
                "owner",
            )
            val service = PlaylistPreviewService(store)
            val first = service.preview("RECENT_LIKED_100", "owner", "seed-a")
            val revision = store.sourceSnapshot("owner", CacheSourceKey.SAVED_TRACKS).sourceRevision
            val second = service.preview("RECENT_LIKED_100", "owner", "seed-b")
            assertEquals(revision, store.sourceSnapshot("owner", CacheSourceKey.SAVED_TRACKS).sourceRevision)
            assertEquals(1, first.generatedTrackIds.size)
            assertEquals(1, second.generatedTrackIds.size)
        }
    }

    private companion object {
        fun track(id: String) =
            SpotifyTrack(
                id,
                id,
                "href:" + id,
                "spotify:track:" + id,
                "2026",
                "artist",
                "{\"name\":\"" + id + "\",\"id\":\"" + id + "\",\"href\":\"href:" + id + "\",\"uri\":\"spotify:track:" +
                    id +
                    "\"}",
            )
    }
}
