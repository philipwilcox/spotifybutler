package com.philipwilcox.spotifybutler.service

import java.time.Instant

enum class CacheResourceKind {
    TRACK_LIST,
    ARTIST_LIST,
    PLAYLIST_LIST,
    PLAYLIST_CONTENTS,
}

data class CacheSourceKey(
    val ownerSpotifyUserId: String,
    val sourceKey: String,
    val resourceKind: CacheResourceKind,
) {
    init {
        require(ownerSpotifyUserId.isNotBlank()) { "ownerSpotifyUserId must not be blank" }
        require(sourceKey.isNotBlank()) { "sourceKey must not be blank" }
    }

    companion object {
        const val SAVED_TRACKS = "saved_tracks"
        const val TOP_TRACKS = "top_tracks"
        const val TOP_ARTISTS = "top_artists"
        const val PLAYLISTS = "playlists"
        const val PLAYLIST_ITEMS_PREFIX = "playlist_items:"

        fun playlistItems(
            ownerSpotifyUserId: String,
            playlistId: String,
        ): CacheSourceKey =
            CacheSourceKey(
                ownerSpotifyUserId,
                "$PLAYLIST_ITEMS_PREFIX$playlistId",
                CacheResourceKind.PLAYLIST_CONTENTS,
            )

        fun root(
            ownerSpotifyUserId: String,
            sourceKey: String,
        ): CacheSourceKey =
            when (sourceKey) {
                SAVED_TRACKS -> CacheSourceKey(ownerSpotifyUserId, sourceKey, CacheResourceKind.TRACK_LIST)
                TOP_TRACKS -> CacheSourceKey(ownerSpotifyUserId, sourceKey, CacheResourceKind.TRACK_LIST)
                TOP_ARTISTS -> CacheSourceKey(ownerSpotifyUserId, sourceKey, CacheResourceKind.ARTIST_LIST)
                PLAYLISTS -> CacheSourceKey(ownerSpotifyUserId, sourceKey, CacheResourceKind.PLAYLIST_LIST)
                else ->
                    if (sourceKey.startsWith(PLAYLIST_ITEMS_PREFIX)) {
                        CacheSourceKey(ownerSpotifyUserId, sourceKey, CacheResourceKind.PLAYLIST_CONTENTS)
                    } else {
                        error("Unsupported cache source key: $sourceKey")
                    }
            }

        fun resourceKindValue(kind: CacheResourceKind): String =
            when (kind) {
                CacheResourceKind.TRACK_LIST -> "track_list"
                CacheResourceKind.ARTIST_LIST -> "artist_list"
                CacheResourceKind.PLAYLIST_LIST -> "playlist_list"
                CacheResourceKind.PLAYLIST_CONTENTS -> "playlist_contents"
            }
    }
}

enum class CacheSourceStatus {
    EMPTY,
    READY,
    REFRESHING,
    STALE,
    ERROR,
}

data class CacheSourceSnapshot(
    val ownerSpotifyUserId: String,
    val sourceKey: String,
    val resourceKind: CacheResourceKind,
    val status: CacheSourceStatus,
    val sourceRevision: String?,
    val lastSyncedAt: Instant?,
    val itemCount: Int?,
    val canRefresh: Boolean = true,
    val lastErrorCode: String? = null,
    val lastErrorAt: Instant? = null,
)

data class CacheRefreshRequest(
    val ownerSpotifyUserId: String,
    val sourceKeys: Set<String>? = null,
)

data class CacheRefreshResult(
    val updatedSources: List<CacheSourceSnapshot>,
    val aggregateStatus: CacheAggregateStatus,
)

enum class CacheAggregateStatus {
    EMPTY,
    READY,
    PARTIAL,
    REFRESHING,
    STALE,
}

data class SourceDependency(
    val sourceKey: String,
    val resourceKind: CacheResourceKind,
    val sourceRevision: String?,
    val lastSyncedAt: Instant?,
    val itemCount: Int?,
    val usable: Boolean,
)
