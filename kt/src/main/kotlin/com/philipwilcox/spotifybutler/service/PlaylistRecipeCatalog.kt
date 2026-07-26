package com.philipwilcox.spotifybutler.service

fun PlaylistDefinition.toPlaylistRecipe(): PlaylistRecipe =
    PlaylistRecipe(
        shuffleAfterGeneration = query !is PlaylistQuery.RecentLiked,
        source = recipeSource(query),
        predicate = recipePredicate(query),
        selection = recipeSelection(query),
        ordering = recipeOrdering(query),
    )

private fun recipeSource(query: PlaylistQuery): CandidateSource =
    when (query) {
        is PlaylistQuery.CollectedDiscoverWeekly ->
            CandidateSource.Union(
                listOf(
                    CandidateSource.PlaylistItems(query.collectedName),
                    CandidateSource.Difference(
                        CandidateSource.Filtered(
                            CandidateSource.PlaylistItems(query.sourceName),
                            TrackPredicate.ReleaseYearRange(minInclusive = query.minReleaseYear.toInt()),
                        ),
                        CandidateSource.PlaylistItems(query.collectedName),
                    ),
                ),
            )
        PlaylistQuery.SavedNotByTopArtists,
        PlaylistQuery.SavedNotInTopTracks,
        -> CandidateSource.SavedTracks
        else -> CandidateSource.SavedTracks
    }

private fun recipePredicate(query: PlaylistQuery): TrackPredicate =
    when (query) {
        PlaylistQuery.SavedNotByTopArtists -> TrackPredicate.NotInTopArtists
        PlaylistQuery.SavedNotInTopTracks -> TrackPredicate.NotInTopTracks
        is PlaylistQuery.SavedInYearRange ->
            TrackPredicate.ReleaseYearRange(query.minYearInclusive.toInt(), query.maxYearExclusive.toInt())
        is PlaylistQuery.SavedInYearRangePerArtist ->
            TrackPredicate.ReleaseYearRange(query.minYear.toInt(), (query.maxYear + 1).toInt())
        is PlaylistQuery.SavedThroughYearPerArtist ->
            TrackPredicate.ReleaseYearRange(maxExclusive = (query.maxYear + 1).toInt())
        is PlaylistQuery.SavedSinceYearPerArtist ->
            TrackPredicate.ReleaseYearRange(minInclusive = query.minYear.toInt())
        else -> TrackPredicate.All
    }

private fun recipeSelection(query: PlaylistQuery): SelectionPolicy =
    SelectionPolicy(
        target =
            when (query) {
                is PlaylistQuery.RecentLiked -> query.limit.toInt()
                is PlaylistQuery.RandomLiked -> query.limit.toInt()
                else -> null
            },
        quotas =
            when (query) {
                is PlaylistQuery.SavedPerArtist ->
                    listOf(Quota(CandidateDimension.PrimaryArtistId, query.limit.toInt()))
                is PlaylistQuery.SavedInYearRangePerArtist ->
                    listOf(Quota(CandidateDimension.PrimaryArtistId, query.limit.toInt()))
                is PlaylistQuery.SavedThroughYearPerArtist ->
                    listOf(Quota(CandidateDimension.PrimaryArtistId, query.limit.toInt()))
                is PlaylistQuery.SavedSinceYearPerArtist ->
                    listOf(Quota(CandidateDimension.PrimaryArtistId, query.limit.toInt()))
                else -> emptyList()
            },
        rankBy =
            when (query) {
                is PlaylistQuery.RecentLiked -> RankingStrategy.AddedAtDescending
                is PlaylistQuery.RandomLiked,
                is PlaylistQuery.SavedPerArtist,
                is PlaylistQuery.SavedInYearRangePerArtist,
                is PlaylistQuery.SavedThroughYearPerArtist,
                is PlaylistQuery.SavedSinceYearPerArtist,
                -> RankingStrategy.SeededRandom
                else -> RankingStrategy.ReleaseDateAscending
            },
    )

private fun recipeOrdering(query: PlaylistQuery): OrderingPolicy =
    when (query) {
        is PlaylistQuery.CollectedDiscoverWeekly -> OrderingPolicy.AddedAtAscending
        is PlaylistQuery.RandomLiked,
        is PlaylistQuery.SavedPerArtist,
        is PlaylistQuery.SavedInYearRangePerArtist,
        is PlaylistQuery.SavedThroughYearPerArtist,
        is PlaylistQuery.SavedSinceYearPerArtist,
        -> OrderingPolicy.SeededRandom
        is PlaylistQuery.RecentLiked -> OrderingPolicy.AddedAtDescending
        else -> OrderingPolicy.AddedAtDescending
    }
