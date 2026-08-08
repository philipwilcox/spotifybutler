package com.philipwilcox.spotifybutler.service

import com.philipwilcox.spotifybutler.db.SpotifyStore
import com.philipwilcox.spotifybutler.spotify.SavedTrack
import com.philipwilcox.spotifybutler.spotify.SpotifyArtist
import com.philipwilcox.spotifybutler.spotify.SpotifyCacheFetcher
import com.philipwilcox.spotifybutler.spotify.SpotifyCacheSnapshot
import com.philipwilcox.spotifybutler.spotify.SpotifyPlaylist
import com.philipwilcox.spotifybutler.spotify.SpotifyPlaylistItem
import com.philipwilcox.spotifybutler.spotify.SpotifyTrack
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpotifyCacheSourceContractTest {
    @Test
    fun selectiveRefreshChangesOnlyRequestedSource() {
        val path = Files.createTempDirectory("source-refresh-").resolve("cache.db")
        val fetcher = RecordingSourceFetcher()
        SpotifyStore.open(path).use { store ->
            val service = SpotifyCacheService(fetcher, store)
            service.refreshSources("owner", "token")
            val savedBefore = service.readSourceSnapshot("owner", CacheSourceKey.SAVED_TRACKS)
            val topBefore = service.readSourceSnapshot("owner", CacheSourceKey.TOP_TRACKS)
            fetcher.topTracks = listOf(track("top-two"))
            service.refreshSources("owner", "token", setOf(CacheSourceKey.TOP_TRACKS))
            val savedAfter = service.readSourceSnapshot("owner", CacheSourceKey.SAVED_TRACKS)
            val topAfter = service.readSourceSnapshot("owner", CacheSourceKey.TOP_TRACKS)
            assertEquals(savedBefore.sourceRevision, savedAfter.sourceRevision)
            assertEquals(savedBefore.lastSyncedAt, savedAfter.lastSyncedAt)
            assertEquals(false, topBefore.sourceRevision == topAfter.sourceRevision)
            assertEquals(
                listOf("saved-one"),
                store.songs("owner").filter { it.id.startsWith("saved") }.map(SpotifyTrack::id),
            )
            assertEquals(listOf("top-two"), store.candidates("owner", CandidateSource.TopTracks).map { it.track.id })
        }
    }

    @Test
    fun failedSourceKeepsRowsAndMarksOnlyThatSource() {
        val path = Files.createTempDirectory("source-failure-").resolve("cache.db")
        val fetcher = RecordingSourceFetcher()
        SpotifyStore.open(path).use { store ->
            val service = SpotifyCacheService(fetcher, store)
            service.refreshSources("owner", "token", setOf(CacheSourceKey.SAVED_TRACKS))
            val prior = store.songs("owner").map(SpotifyTrack::id)
            fetcher.failSaved = true
            service.refreshSources("owner", "token", setOf(CacheSourceKey.SAVED_TRACKS, CacheSourceKey.TOP_TRACKS))
            val failed = service.readSourceSnapshot("owner", CacheSourceKey.SAVED_TRACKS)
            val unrelated = service.readSourceSnapshot("owner", CacheSourceKey.TOP_TRACKS)
            assertEquals(CacheSourceStatus.ERROR, failed.status)
            assertEquals(prior, store.songs("owner").map(SpotifyTrack::id).filter { it == "saved-one" })
            assertEquals(CacheSourceStatus.READY, unrelated.status)
        }
    }

    @Test
    fun fullRefreshDiscoversSourcesAfterCatalogAndContinuesAfterSourceFailure() {
        val path = Files.createTempDirectory("source-progress-").resolve("cache.db")
        val fetcher =
            RecordingSourceFetcher().apply {
                paginatedPlaylistItems = true
                failSaved = true
            }
        val updates = mutableListOf<OperationProgressUpdate>()
        SpotifyStore.open(path).use { store ->
            val service = SpotifyCacheService(fetcher, store)
            OperationProgress.with("library_refresh", progressSink = updates::add) {
                service.refreshSources("owner", "token")
            }

            assertEquals(
                CacheSourceStatus.ERROR,
                service.readSourceSnapshot("owner", CacheSourceKey.SAVED_TRACKS).status,
            )
            assertEquals(CacheSourceStatus.READY, service.readSourceSnapshot("owner", CacheSourceKey.TOP_TRACKS).status)
        }

        val sourceProgress = updates.mapNotNull(OperationProgressUpdate::libraryRefresh)
        assertEquals(5, sourceProgress.first().totalSources)
        assertEquals(1, sourceProgress.first().completedSources)
        assertTrue(
            sourceProgress.zipWithNext().all { (before, after) ->
                before.completedSources <=
                    after.completedSources
            },
        )
        assertTrue(sourceProgress.all { it.completedSources <= it.totalSources })
        assertEquals(
            listOf(1, 2),
            updates
                .filter { it.action == "Spotify action completed" }
                .mapNotNull { it.libraryRefresh?.activeSourceCompletedPages },
        )
        assertEquals(LibraryRefreshProgress(5, 5), sourceProgress.last())
    }

    private class RecordingSourceFetcher : SpotifyCacheFetcher {
        override val supportsIndependentSources = true
        var topTracks = listOf(track("top-one"))
        var failSaved = false
        var paginatedPlaylistItems = false

        override fun fetchCache(accessToken: String) =
            SpotifyCacheSnapshot(emptyList(), emptyList(), emptyList(), emptyList(), emptyList())

        override fun fetchSavedTracks(accessToken: String): List<SavedTrack> {
            check(!failSaved) { "sanitized upstream failure" }
            return listOf(SavedTrack("2026-01-01T00:00:00Z", track("saved-one")))
        }

        override fun fetchTopTracks(accessToken: String) = topTracks

        override fun fetchTopArtists(accessToken: String) = listOf(SpotifyArtist("Artist", "artist", "href", "uri"))

        override fun fetchPlaylists(accessToken: String) =
            listOf(SpotifyPlaylist("Playlist", "playlist", "href", "uri", "tracks"))

        override fun fetchPlaylistItems(
            accessToken: String,
            playlistId: String,
        ): List<SpotifyPlaylistItem> {
            if (paginatedPlaylistItems) {
                repeat(2) {
                    val call = OperationProgress.current()?.beginExternalCall()
                    OperationProgress.current()?.libraryRefreshPageTotalDiscovered(2)
                    if (call != null) OperationProgress.current()?.finishExternalCall(call)
                }
            }
            return emptyList()
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
