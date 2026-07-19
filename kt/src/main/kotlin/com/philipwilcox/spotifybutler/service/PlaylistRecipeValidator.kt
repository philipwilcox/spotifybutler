package com.philipwilcox.spotifybutler.service

object PlaylistRecipeValidator {
    private const val MIN_SUPPORTED_YEAR = 1900
    private const val MAX_SUPPORTED_YEAR = 3000
    private const val MAX_EXCLUSIVE_SUPPORTED_YEAR = MAX_SUPPORTED_YEAR + 1
    const val MAX_AST_DEPTH = 16
    const val MAX_PREDICATE_COUNT = 32
    const val MAX_SOURCE_FAN_OUT = 16
    const val MAX_TARGET = 10000
    const val MAX_QUOTA = 10000

    fun validate(recipe: PlaylistRecipe) {
        require(recipe.schemaVersion == 1) { "recipe.schemaVersion: unsupported value ${recipe.schemaVersion}" }
        require(recipe.selection.target == null || recipe.selection.target in 0..MAX_TARGET) {
            "recipe.selection.target: must be between 0 and $MAX_TARGET"
        }
        require(recipe.selection.quotas.all { it.maximum in 1..MAX_QUOTA }) {
            "recipe.selection.quotas: maximum must be between 1 and $MAX_QUOTA"
        }
        require(
            recipe.selection.quotas
                .map(Quota::dimension)
                .distinct()
                .size == recipe.selection.quotas.size,
        ) {
            "recipe.selection.quotas: dimensions must be unique"
        }
        validateSource(recipe.source, 1)
        validatePredicate(recipe.predicate, 1)
    }

    private fun validateSource(
        source: CandidateSource,
        depth: Int,
    ) {
        require(depth <= MAX_AST_DEPTH) { "recipe.source: maximum AST depth is $MAX_AST_DEPTH" }
        when (source) {
            CandidateSource.SavedTracks,
            CandidateSource.TopTracks,
            -> Unit
            is CandidateSource.PlaylistItems ->
                require(source.playlistName.isNotBlank()) { "recipe.source.playlistName: must not be blank" }
            is CandidateSource.Union -> {
                require(source.sources.isNotEmpty()) { "recipe.source.sources: must not be empty" }
                require(source.sources.size <= MAX_SOURCE_FAN_OUT) {
                    "recipe.source.sources: maximum fan-out is $MAX_SOURCE_FAN_OUT"
                }
                source.sources.forEach { validateSource(it, depth + 1) }
            }
            is CandidateSource.Difference -> {
                validateSource(source.left, depth + 1)
                validateSource(source.right, depth + 1)
            }
            is CandidateSource.Filtered -> {
                validateSource(source.source, depth + 1)
                validatePredicate(source.predicate, depth + 1)
            }
        }
    }

    private fun validatePredicate(
        predicate: TrackPredicate,
        depth: Int,
    ) {
        require(depth <= MAX_AST_DEPTH) { "recipe.predicate: maximum AST depth is $MAX_AST_DEPTH" }
        when (predicate) {
            TrackPredicate.All,
            TrackPredicate.NotInTopArtists,
            TrackPredicate.NotInTopTracks,
            -> Unit
            is TrackPredicate.And -> validatePredicateList(predicate.predicates, depth)
            is TrackPredicate.Or -> validatePredicateList(predicate.predicates, depth)
            is TrackPredicate.Not -> validatePredicate(predicate.predicate, depth + 1)
            is TrackPredicate.ReleaseYearRange -> validateYearRange(predicate)
            is TrackPredicate.DurationRange -> validateDurationRange(predicate)
            is TrackPredicate.AlbumIdIn -> requireNonEmpty(predicate.albumIds, "albumIds")
            is TrackPredicate.ArtistIdIn -> requireNonEmpty(predicate.artistIds, "artistIds")
            is TrackPredicate.AddedAtRange -> validateAddedAtRange(predicate)
            is TrackPredicate.Explicitness -> Unit
        }
    }

    private fun validatePredicateList(
        predicates: List<TrackPredicate>,
        depth: Int,
    ) {
        require(predicates.isNotEmpty()) { "recipe.predicate.predicates: must not be empty" }
        require(predicates.size <= MAX_PREDICATE_COUNT) {
            "recipe.predicate.predicates: maximum count is $MAX_PREDICATE_COUNT"
        }
        predicates.forEach { validatePredicate(it, depth + 1) }
    }

    private fun validateYearRange(predicate: TrackPredicate.ReleaseYearRange) {
        require(predicate.minInclusive == null || predicate.minInclusive in MIN_SUPPORTED_YEAR..MAX_SUPPORTED_YEAR) {
            "recipe.predicate.minInclusive: unsupported year"
        }
        require(
            predicate.maxExclusive == null ||
                predicate.maxExclusive in MIN_SUPPORTED_YEAR..MAX_EXCLUSIVE_SUPPORTED_YEAR,
        ) { "recipe.predicate.maxExclusive: unsupported year" }
        require(
            predicate.minInclusive == null ||
                predicate.maxExclusive == null ||
                predicate.minInclusive < predicate.maxExclusive,
        ) { "recipe.predicate: year range must be increasing" }
    }

    private fun validateDurationRange(predicate: TrackPredicate.DurationRange) {
        require(predicate.minMsInclusive == null || predicate.minMsInclusive >= 0) {
            "recipe.predicate.minMsInclusive: must not be negative"
        }
        require(predicate.maxMsExclusive == null || predicate.maxMsExclusive >= 0) {
            "recipe.predicate.maxMsExclusive: must not be negative"
        }
        require(
            predicate.minMsInclusive == null ||
                predicate.maxMsExclusive == null ||
                predicate.minMsInclusive < predicate.maxMsExclusive,
        ) { "recipe.predicate: duration range must be increasing" }
    }

    private fun validateAddedAtRange(predicate: TrackPredicate.AddedAtRange) {
        require(predicate.minInclusive == null || predicate.minInclusive.isNotBlank()) {
            "recipe.predicate.minInclusive: must not be blank"
        }
        require(predicate.maxExclusive == null || predicate.maxExclusive.isNotBlank()) {
            "recipe.predicate.maxExclusive: must not be blank"
        }
        require(
            predicate.minInclusive == null ||
                predicate.maxExclusive == null ||
                predicate.minInclusive < predicate.maxExclusive,
        ) { "recipe.predicate: added-at range must be increasing" }
    }

    private fun requireNonEmpty(
        values: List<String>,
        name: String,
    ) {
        require(values.isNotEmpty() && values.all(String::isNotBlank)) {
            "recipe.predicate.$name: must contain non-blank values"
        }
    }
}
