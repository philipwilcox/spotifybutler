package com.philipwilcox.spotifybutler.service

enum class PlaylistDefinitionId {
    RECENT_LIKED_100,
    RANDOM_LIKED_100,
    COLLECTED_DISCOVER_WEEKLY,
    LIKED_PER_ARTIST,
    THREE_SAVED_SONGS_PER_ARTIST,
    ROLLING_RECENT_20,
    ROLLING_PRIOR_20,
    ROLLING_PRE_40,
    NOT_TOP_ARTISTS,
    NOT_TOP_TRACKS,
    DECADE_1980,
    DECADE_1990,
    DECADE_2000,
    DECADE_2010,
    DECADE_2020,
    RECENT_5_PER_ARTIST,
}

sealed interface PlaylistQuery {
    data class RecentLiked(
        val limit: Long,
    ) : PlaylistQuery

    data class RandomLiked(
        val limit: Long,
    ) : PlaylistQuery

    data class CollectedDiscoverWeekly(
        val collectedName: String,
        val sourceName: String,
        val minReleaseYear: Long,
    ) : PlaylistQuery

    data class SavedPerArtist(
        val limit: Long,
    ) : PlaylistQuery

    data class SavedInYearRangePerArtist(
        val minYear: Long,
        val maxYear: Long,
        val limit: Long,
    ) : PlaylistQuery

    data class SavedThroughYearPerArtist(
        val maxYear: Long,
        val limit: Long,
    ) : PlaylistQuery

    data class SavedSinceYearPerArtist(
        val minYear: Long,
        val limit: Long,
    ) : PlaylistQuery

    data object SavedNotByTopArtists : PlaylistQuery

    data object SavedNotInTopTracks : PlaylistQuery

    data class SavedInYearRange(
        val minYearInclusive: Long,
        val maxYearExclusive: Long,
    ) : PlaylistQuery
}

data class PlaylistDefinition(
    val id: PlaylistDefinitionId,
    val name: String,
    val query: PlaylistQuery,
)

object PlaylistQueries {
    private const val MIN_SUPPORTED_YEAR = 1900
    private const val MAX_SUPPORTED_YEAR = 3000
    private const val RECENT_YEAR_COUNT = 19
    private const val PRIOR_YEAR_COUNT = 39
    private const val PRIOR_YEAR_OFFSET = 20
    private const val PRE_YEAR_OFFSET = 40
    private const val RECENT_FIVE_YEAR_OFFSET = 4
    private const val DECADE_1980_START = 1980L
    private const val DECADE_1990_START = 1990L
    private const val DECADE_2000_START = 2000L
    private const val DECADE_2010_START = 2010L
    private const val DECADE_2020_START = 2020L
    private const val DECADE_LENGTH = 10L
    const val ARTIST_LIMIT = 12L
    const val THREE_SAVED_SONGS_ARTIST_LIMIT = 3L
    const val RECENT_LIKED_LIMIT = 100L
    const val RANDOM_LIKED_LIMIT = 100L
    const val RECENT_ARTIST_LIMIT = 8L
    const val COLLECTED_DISCOVER_WEEKLY_NAME = "Collected Discover Weekly 2016 And On - Butler"
    const val DISCOVER_WEEKLY_NAME = "Discover Weekly"

    fun definitions(
        currentYear: Int,
        minYearForDiscoverWeekly: Int,
    ): List<PlaylistDefinition> {
        require(
            currentYear in MIN_SUPPORTED_YEAR..MAX_SUPPORTED_YEAR,
        ) { "currentYear must be a plausible calendar year" }
        require(minYearForDiscoverWeekly in MIN_SUPPORTED_YEAR..currentYear) {
            "minYearForDiscoverWeekly must be between 1900 and currentYear"
        }
        val recentStart = currentYear - RECENT_YEAR_COUNT
        val priorStart = currentYear - PRIOR_YEAR_COUNT
        val priorEnd = currentYear - PRIOR_YEAR_OFFSET
        val preMax = currentYear - PRE_YEAR_OFFSET
        return listOf(
            PlaylistDefinition(
                PlaylistDefinitionId.RECENT_LIKED_100,
                "100 Most Recent Liked Songs",
                PlaylistQuery.RecentLiked(RECENT_LIKED_LIMIT),
            ),
            PlaylistDefinition(
                PlaylistDefinitionId.RANDOM_LIKED_100,
                "100 Random Liked Songs",
                PlaylistQuery.RandomLiked(RANDOM_LIKED_LIMIT),
            ),
            PlaylistDefinition(
                PlaylistDefinitionId.COLLECTED_DISCOVER_WEEKLY,
                COLLECTED_DISCOVER_WEEKLY_NAME,
                PlaylistQuery.CollectedDiscoverWeekly(
                    COLLECTED_DISCOVER_WEEKLY_NAME,
                    DISCOVER_WEEKLY_NAME,
                    minYearForDiscoverWeekly.toLong(),
                ),
            ),
            PlaylistDefinition(
                PlaylistDefinitionId.LIKED_PER_ARTIST,
                "Liked Tracks, Twelve Per Artist",
                PlaylistQuery.SavedPerArtist(ARTIST_LIMIT),
            ),
            PlaylistDefinition(
                PlaylistDefinitionId.THREE_SAVED_SONGS_PER_ARTIST,
                "3 Saved Songs Per Artist",
                PlaylistQuery.SavedPerArtist(THREE_SAVED_SONGS_ARTIST_LIMIT),
            ),
            PlaylistDefinition(
                PlaylistDefinitionId.ROLLING_RECENT_20,
                "$recentStart-$currentYear, 12 Per Artist",
                PlaylistQuery.SavedInYearRangePerArtist(recentStart.toLong(), currentYear.toLong(), ARTIST_LIMIT),
            ),
            PlaylistDefinition(
                PlaylistDefinitionId.ROLLING_PRIOR_20,
                "$priorStart-$priorEnd, 12 Per Artist",
                PlaylistQuery.SavedInYearRangePerArtist(priorStart.toLong(), priorEnd.toLong(), ARTIST_LIMIT),
            ),
            PlaylistDefinition(
                PlaylistDefinitionId.ROLLING_PRE_40,
                "Pre-$priorStart, 12 Per Artist",
                PlaylistQuery.SavedThroughYearPerArtist(preMax.toLong(), ARTIST_LIMIT),
            ),
            PlaylistDefinition(
                PlaylistDefinitionId.NOT_TOP_ARTISTS,
                "Saved Tracks Not By My Top 50 Artists - Butler",
                PlaylistQuery.SavedNotByTopArtists,
            ),
            PlaylistDefinition(
                PlaylistDefinitionId.NOT_TOP_TRACKS,
                "Saved Tracks Not In My Top 50 Tracks - Butler",
                PlaylistQuery.SavedNotInTopTracks,
            ),
            PlaylistDefinition(
                PlaylistDefinitionId.DECADE_1980,
                "1980 - Butler Created",
                PlaylistQuery.SavedInYearRange(DECADE_1980_START, DECADE_1980_START + DECADE_LENGTH),
            ),
            PlaylistDefinition(
                PlaylistDefinitionId.DECADE_1990,
                "1990 - Butler Created",
                PlaylistQuery.SavedInYearRange(DECADE_1990_START, DECADE_1990_START + DECADE_LENGTH),
            ),
            PlaylistDefinition(
                PlaylistDefinitionId.DECADE_2000,
                "2000 - Butler Created",
                PlaylistQuery.SavedInYearRange(DECADE_2000_START, DECADE_2000_START + DECADE_LENGTH),
            ),
            PlaylistDefinition(
                PlaylistDefinitionId.DECADE_2010,
                "2010 - Butler Created",
                PlaylistQuery.SavedInYearRange(DECADE_2010_START, DECADE_2010_START + DECADE_LENGTH),
            ),
            PlaylistDefinition(
                PlaylistDefinitionId.DECADE_2020,
                "2020 - Butler Created",
                PlaylistQuery.SavedInYearRange(DECADE_2020_START, DECADE_2020_START + DECADE_LENGTH),
            ),
            PlaylistDefinition(
                PlaylistDefinitionId.RECENT_5_PER_ARTIST,
                "Last 5 Years, Eight Per Artist",
                PlaylistQuery.SavedSinceYearPerArtist(
                    (currentYear - RECENT_FIVE_YEAR_OFFSET).toLong(),
                    RECENT_ARTIST_LIMIT,
                ),
            ),
        )
    }
}
