package com.philipwilcox.spotifybutler.service

import com.philipwilcox.spotifybutler.db.SpotifyStore
import com.philipwilcox.spotifybutler.spotify.PlaylistTrack
import com.philipwilcox.spotifybutler.spotify.SpotifyCacheFetcher
import com.philipwilcox.spotifybutler.spotify.SpotifyCacheSnapshot
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Clock

class SpotifyCacheService(
    private val apiClient: SpotifyCacheFetcher,
    private val store: SpotifyStore,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val logger = KotlinLogging.logger {}

    fun refreshSources(
        ownerSpotifyUserId: String,
        accessToken: String,
        sourceKeys: Set<String>? = null,
    ): CacheRefreshResult {
        val fullRefresh = sourceKeys.isNullOrEmpty()
        if (fullRefresh && !apiClient.supportsIndependentSources) {
            val snapshot = apiClient.fetchCache(accessToken)
            store.replaceCache(snapshot, clock.millis(), ownerSpotifyUserId)
            return CacheRefreshResult(
                store.sourceSnapshots(ownerSpotifyUserId),
                store.aggregateStatus(ownerSpotifyUserId),
            )
        }

        if (fullRefresh) return refreshFullLibrary(ownerSpotifyUserId, accessToken)

        val requested = sourceKeys.orEmpty()
        requested.forEach { CacheSourceKey.root(ownerSpotifyUserId, it) }
        val updated = mutableListOf<CacheSourceSnapshot>()
        requested.filterNot { it.startsWith(CacheSourceKey.PLAYLIST_ITEMS_PREFIX) }.sorted().forEach { sourceKey ->
            runCatching { refreshSource(ownerSpotifyUserId, accessToken, sourceKey) }
                .onSuccess { updated += it }
                .onFailure { updated += store.sourceSnapshot(ownerSpotifyUserId, sourceKey) }
        }
        requested.filter { it.startsWith(CacheSourceKey.PLAYLIST_ITEMS_PREFIX) }.sorted().forEach { sourceKey ->
            runCatching { refreshSource(ownerSpotifyUserId, accessToken, sourceKey) }
                .onSuccess { updated += it }
                .onFailure { updated += store.sourceSnapshot(ownerSpotifyUserId, sourceKey) }
        }
        return CacheRefreshResult(
            updated.distinctBy(CacheSourceSnapshot::sourceKey),
            store.aggregateStatus(ownerSpotifyUserId),
        )
    }

    private fun refreshFullLibrary(
        ownerSpotifyUserId: String,
        accessToken: String,
    ): CacheRefreshResult {
        val updated = mutableListOf<CacheSourceSnapshot>()
        refreshFullSource(ownerSpotifyUserId, accessToken, CacheSourceKey.PLAYLISTS, updated)
        val contentKeys =
            store
                .cachedPlaylistIds(ownerSpotifyUserId)
                .sorted()
                .map { CacheSourceKey.playlistItems(ownerSpotifyUserId, it).sourceKey }
        OperationProgress.current()?.libraryRefreshSourcesDiscovered(ROOT_SOURCE_KEYS.size + contentKeys.size)
        ROOT_SOURCE_KEYS.filterNot { it == CacheSourceKey.PLAYLISTS }.forEach { sourceKey ->
            refreshFullSource(ownerSpotifyUserId, accessToken, sourceKey, updated)
        }
        contentKeys.forEach { sourceKey -> refreshFullSource(ownerSpotifyUserId, accessToken, sourceKey, updated) }
        return CacheRefreshResult(updated, store.aggregateStatus(ownerSpotifyUserId))
    }

    private fun refreshFullSource(
        ownerSpotifyUserId: String,
        accessToken: String,
        sourceKey: String,
        updated: MutableList<CacheSourceSnapshot>,
    ) {
        runCatching { refreshSource(ownerSpotifyUserId, accessToken, sourceKey, trackLibraryProgress = true) }
            .onSuccess { updated += it }
            .onFailure { updated += store.sourceSnapshot(ownerSpotifyUserId, sourceKey) }
    }

    @Suppress("TooGenericExceptionCaught")
    fun refreshSource(
        ownerSpotifyUserId: String,
        accessToken: String,
        sourceKey: String,
        trackLibraryProgress: Boolean = false,
    ): CacheSourceSnapshot {
        CacheSourceKey.root(ownerSpotifyUserId, sourceKey)
        if (trackLibraryProgress) OperationProgress.current()?.libraryRefreshSourceStarted()
        OperationProgress.current()?.actionStarted("Refreshing library source")
        store.setSourceRefreshing(ownerSpotifyUserId, sourceKey)
        return try {
            val timestamp = clock.millis()
            val snapshot = fetchSource(ownerSpotifyUserId, accessToken, sourceKey)
            store.replaceSource(ownerSpotifyUserId, sourceKey, snapshot, timestamp)
            OperationProgress.current()?.actionStarted("Storing source in local library")
            store.sourceSnapshot(ownerSpotifyUserId, sourceKey).also { stored ->
                if (trackLibraryProgress) OperationProgress.current()?.libraryRefreshSourceFinished(succeeded = true)
                logger.info {
                    "Spotify cache source stored: owner=$ownerSpotifyUserId sourceKey=$sourceKey " +
                        "resourceKind=${stored.resourceKind} status=${stored.status} " +
                        "itemCount=${stored.itemCount ?: 0}" +
                        OperationProgress.current()?.let { " ${it.logFields()}" }.orEmpty()
                }
            }
        } catch (exception: Exception) {
            logger.warn(exception) { "Spotify source refresh failed sourceKey=$sourceKey" }
            store.setSourceFailure(ownerSpotifyUserId, sourceKey, sanitizedErrorCode(exception), clock.millis())
            if (trackLibraryProgress) OperationProgress.current()?.libraryRefreshSourceFinished(succeeded = false)
            throw exception
        }
    }

    fun readSourceSnapshot(
        ownerSpotifyUserId: String,
        sourceKey: String,
    ): CacheSourceSnapshot = store.sourceSnapshot(ownerSpotifyUserId, sourceKey)

    fun aggregateStatus(ownerSpotifyUserId: String): CacheAggregateStatus = store.aggregateStatus(ownerSpotifyUserId)

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
        val owner = ownerSpotifyUserId ?: SpotifyStore.DEFAULT_OWNER
        if (!refresh && store.hasCompletedSync(owner)) return CacheLoadResult.SkippedExistingCache
        val result = refreshSources(owner, accessToken)
        return CacheLoadResult.Loaded(
            savedTrackCount =
                result.updatedSources.firstOrNull { it.sourceKey == CacheSourceKey.SAVED_TRACKS }?.itemCount ?: 0,
            topTrackCount =
                result.updatedSources.firstOrNull { it.sourceKey == CacheSourceKey.TOP_TRACKS }?.itemCount ?: 0,
            topArtistCount =
                result.updatedSources.firstOrNull { it.sourceKey == CacheSourceKey.TOP_ARTISTS }?.itemCount ?: 0,
            playlistCount =
                result.updatedSources.firstOrNull { it.sourceKey == CacheSourceKey.PLAYLISTS }?.itemCount ?: 0,
            playlistTrackCount =
                result.updatedSources.filter { it.resourceKind == CacheResourceKind.PLAYLIST_CONTENTS }.sumOf {
                    it.itemCount
                        ?: 0
                },
        )
    }

    fun completeSync() = store.markSyncComplete(clock.millis())

    private fun fetchSource(
        owner: String,
        accessToken: String,
        sourceKey: String,
    ): SpotifyCacheSnapshot =
        when (CacheSourceKey.root(owner, sourceKey).resourceKind) {
            CacheResourceKind.TRACK_LIST ->
                if (sourceKey == CacheSourceKey.SAVED_TRACKS) {
                    SpotifyCacheSnapshot(
                        apiClient.fetchSavedTracks(accessToken),
                        emptyList(),
                        emptyList(),
                        emptyList(),
                        emptyList(),
                    )
                } else {
                    SpotifyCacheSnapshot(
                        emptyList(),
                        apiClient.fetchTopTracks(accessToken),
                        emptyList(),
                        emptyList(),
                        emptyList(),
                    )
                }
            CacheResourceKind.ARTIST_LIST ->
                SpotifyCacheSnapshot(
                    emptyList(),
                    emptyList(),
                    apiClient.fetchTopArtists(accessToken),
                    emptyList(),
                    emptyList(),
                )
            CacheResourceKind.PLAYLIST_LIST ->
                SpotifyCacheSnapshot(
                    emptyList(),
                    emptyList(),
                    emptyList(),
                    apiClient.fetchPlaylists(accessToken),
                    emptyList(),
                )
            CacheResourceKind.PLAYLIST_CONTENTS -> {
                val playlistId = sourceKey.removePrefix(CacheSourceKey.PLAYLIST_ITEMS_PREFIX)
                val items = apiClient.fetchPlaylistItems(accessToken, playlistId)
                SpotifyCacheSnapshot(
                    emptyList(),
                    emptyList(),
                    emptyList(),
                    emptyList(),
                    items.mapNotNull {
                        it.track?.let { track ->
                            PlaylistTrack(it.playlistName, it.addedAt, track, playlistId)
                        }
                    },
                    items,
                )
            }
        }

    private fun sanitizedErrorCode(exception: Exception): String =
        when (exception) {
            is IllegalArgumentException -> "invalid_source_response"
            else -> "spotify_source_failure"
        }

    private companion object {
        val ROOT_SOURCE_KEYS =
            listOf(
                CacheSourceKey.PLAYLISTS,
                CacheSourceKey.SAVED_TRACKS,
                CacheSourceKey.TOP_TRACKS,
                CacheSourceKey.TOP_ARTISTS,
            )
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
