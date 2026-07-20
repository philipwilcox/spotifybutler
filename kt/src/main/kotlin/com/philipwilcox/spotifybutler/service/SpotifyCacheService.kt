package com.philipwilcox.spotifybutler.service

import com.philipwilcox.spotifybutler.db.SpotifyStore
import com.philipwilcox.spotifybutler.spotify.SpotifyCacheFetcher
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Clock

class SpotifyCacheService(
    private val apiClient: SpotifyCacheFetcher,
    private val store: SpotifyStore,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val logger = KotlinLogging.logger {}

    fun loadIfNeeded(
        accessToken: String,
        refresh: Boolean,
        ownerSpotifyUserId: String? = null,
    ): CacheLoadResult = prepareLoadIfNeeded(accessToken, refresh, ownerSpotifyUserId)

    fun prepareLoadIfNeeded(
        accessToken: String,
        refresh: Boolean,
        ownerSpotifyUserId: String? = null,
    ): CacheLoadResult {
        val hasCompletedSync = store.hasCompletedSync()
        if (!refresh && hasCompletedSync) {
            logger.info { "Spotify SQLite cache already has a completed sync; skipping fetch." }
            return CacheLoadResult.SkippedExistingCache
        }
        logger.info { "Loading Spotify API data into SQLite: refresh=$refresh hasCompletedSync=$hasCompletedSync" }
        val snapshot = apiClient.fetchCache(accessToken)
        store.replaceCache(snapshot, clock.millis(), ownerSpotifyUserId)
        return CacheLoadResult.Loaded(
            savedTrackCount = snapshot.savedTracks.size,
            topTrackCount = snapshot.topTracks.size,
            topArtistCount = snapshot.topArtists.size,
            playlistCount = snapshot.playlists.size,
            playlistTrackCount = snapshot.playlistTracks.size,
        )
    }

    fun completeSync() {
        store.markSyncComplete(clock.millis())
    }
}

sealed interface CacheLoadResult {
    data object SkippedExistingCache : CacheLoadResult

    data class Loaded(
        val savedTrackCount: Int,
        val topTrackCount: Int,
        val topArtistCount: Int,
        val playlistCount: Int,
        val playlistTrackCount: Int,
    ) : CacheLoadResult
}
