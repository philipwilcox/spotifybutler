package com.philipwilcox.spotifybutler.service

private const val UNSIGNED_BYTE_MASK = 0xff

class PlaylistRecipeEngine {
    fun generate(
        recipe: PlaylistRecipe,
        candidates: List<CandidateTrack>,
        seed: ByteArray,
        context: RecipeExecutionContext = RecipeExecutionContext(),
    ): PlaylistGenerationResult {
        val canonicalRecipe = PlaylistRecipeCodec.canonicalize(recipe)
        val recipeRevision = PlaylistRecipeCodec.revision(canonicalRecipe)
        val selectionRevision =
            PlaylistRecipeCodec.revision(canonicalRecipe.copy(shuffleAfterGeneration = false))
        val eligible = candidates.filter { canonicalRecipe.predicate.matches(it, context) }
        val distinct = applyDistinctness(canonicalRecipe.distinctness, eligible)
        val ranked = distinct.sortedWith(rankComparator(canonicalRecipe.selection.rankBy, seed, selectionRevision))
        val selectedAndRejected = admit(ranked, canonicalRecipe.selection)
        val ordered =
            selectedAndRejected.selected.sortedWith(
                orderComparator(canonicalRecipe.ordering, seed, selectionRevision),
            )
        val generated =
            if (canonicalRecipe.shuffleAfterGeneration) {
                ordered.sortedWith(shuffleComparator(seed, recipeRevision))
            } else {
                ordered
            }
        return PlaylistGenerationResult(
            candidates = eligible,
            distinctCandidates = distinct,
            selected = generated,
            rejectedByQuota = selectedAndRejected.rejected,
        )
    }

    companion object {
        internal fun matches(
            predicate: TrackPredicate,
            candidate: CandidateTrack,
            context: RecipeExecutionContext = RecipeExecutionContext(),
        ): Boolean = predicate.matches(candidate, context)
    }

    private fun applyDistinctness(
        policy: DistinctnessPolicy,
        candidates: List<CandidateTrack>,
    ): List<CandidateTrack> =
        when (policy) {
            is DistinctnessPolicy.By -> candidates.distinctBy { identity(it, policy.identity) }
            DistinctnessPolicy.KeepAll -> candidates
        }

    private fun identity(
        candidate: CandidateTrack,
        identity: CandidateIdentity,
    ): String =
        when (identity) {
            CandidateIdentity.SpotifyUri -> candidate.track.uri
        }

    private fun admit(
        ranked: List<CandidateTrack>,
        policy: SelectionPolicy,
    ): AdmissionResult {
        val counts = mutableMapOf<Pair<CandidateDimension, String>, Int>()
        val selected = mutableListOf<CandidateTrack>()
        val rejected = mutableListOf<CandidateTrack>()
        ranked.forEach { candidate ->
            if (policy.target != null && selected.size >= policy.target) return@forEach
            val allowed =
                policy.quotas.all { quota ->
                    candidate.dimensionValues(quota.dimension).all { value ->
                        counts[quota.dimension to value].orZero() < quota.maximum
                    }
                }
            if (!allowed) {
                rejected += candidate
            } else {
                selected += candidate
                policy.quotas.forEach { quota ->
                    candidate.dimensionValues(quota.dimension).forEach { value ->
                        val key = quota.dimension to value
                        counts[key] = counts[key].orZero() + 1
                    }
                }
            }
        }
        return AdmissionResult(selected, rejected)
    }

    private fun rankComparator(
        strategy: RankingStrategy,
        seed: ByteArray,
        recipeRevision: String,
    ): Comparator<CandidateTrack> =
        Comparator { left, right ->
            compareRankValues(
                strategy,
                left,
                right,
                seed,
                recipeRevision,
            ).takeUnless { it == 0 } ?: left.identity.compareTo(right.identity)
        }

    private fun orderComparator(
        policy: OrderingPolicy,
        seed: ByteArray,
        recipeRevision: String,
    ): Comparator<CandidateTrack> =
        Comparator { left, right ->
            val comparison =
                when (policy) {
                    OrderingPolicy.SeededRandom ->
                        compareBytes(
                            orderingRank(seed, recipeRevision, left),
                            orderingRank(seed, recipeRevision, right),
                        )
                    OrderingPolicy.AddedAtAscending ->
                        compareNullable(
                            left.addedAt,
                            right.addedAt,
                        )
                    OrderingPolicy.AddedAtDescending ->
                        compareNullable(
                            right.addedAt,
                            left.addedAt,
                        )
                    OrderingPolicy.ReleaseDateAscending ->
                        compareNullable(
                            left.track.releaseDate,
                            right.track.releaseDate,
                        )
                    OrderingPolicy.ReleaseDateDescending ->
                        compareNullable(
                            right.track.releaseDate,
                            left.track.releaseDate,
                        )
                }
            comparison.takeUnless { it == 0 } ?: left.identity.compareTo(right.identity)
        }

    private fun shuffleComparator(
        seed: ByteArray,
        recipeRevision: String,
    ): Comparator<CandidateTrack> =
        Comparator { left, right ->
            compareBytes(
                shuffleRank(seed, recipeRevision, left),
                shuffleRank(seed, recipeRevision, right),
            ).takeUnless { it == 0 } ?: left.identity.compareTo(right.identity)
        }

    private fun compareRankValues(
        strategy: RankingStrategy,
        left: CandidateTrack,
        right: CandidateTrack,
        seed: ByteArray,
        recipeRevision: String,
    ): Int =
        when (strategy) {
            RankingStrategy.SeededRandom ->
                compareBytes(selectionRank(seed, recipeRevision, left), selectionRank(seed, recipeRevision, right))
            RankingStrategy.AddedAtAscending -> compareNullable(left.addedAt, right.addedAt)
            RankingStrategy.AddedAtDescending -> compareNullable(right.addedAt, left.addedAt)
            RankingStrategy.ReleaseDateAscending -> compareNullable(left.track.releaseDate, right.track.releaseDate)
            RankingStrategy.ReleaseDateDescending -> compareNullable(right.track.releaseDate, left.track.releaseDate)
        }

    private data class AdmissionResult(
        val selected: List<CandidateTrack>,
        val rejected: List<CandidateTrack>,
    )
}

private fun TrackPredicate.matches(
    candidate: CandidateTrack,
    context: RecipeExecutionContext = RecipeExecutionContext(),
): Boolean =
    when (this) {
        TrackPredicate.All -> true
        is TrackPredicate.And -> predicates.all { it.matches(candidate, context) }
        is TrackPredicate.Or -> predicates.any { it.matches(candidate, context) }
        is TrackPredicate.Not -> !predicate.matches(candidate, context)
        else -> matchesLeaf(candidate, context)
    }

private fun TrackPredicate.matchesLeaf(
    candidate: CandidateTrack,
    context: RecipeExecutionContext,
): Boolean =
    when (this) {
        is TrackPredicate.ReleaseYearRange -> matchesYearRange(candidate)
        is TrackPredicate.DurationRange -> matchesDurationRange(candidate)
        is TrackPredicate.AlbumIdIn -> candidate.track.albumId in albumIds
        is TrackPredicate.ArtistIdIn -> candidate.track.artistIds.any(artistIds::contains)
        is TrackPredicate.AddedAtRange -> matchesAddedAtRange(candidate)
        is TrackPredicate.Explicitness -> candidate.track.explicit == value
        TrackPredicate.NotInTopArtists ->
            candidate.track.primaryArtistId != null && candidate.track.primaryArtistId !in context.topArtistIds
        TrackPredicate.NotInTopTracks -> candidate.track.id !in context.topTrackIds
        TrackPredicate.All,
        is TrackPredicate.And,
        is TrackPredicate.Or,
        is TrackPredicate.Not,
        -> error("Composite predicate was passed to matchesLeaf")
    }

private fun TrackPredicate.ReleaseYearRange.matchesYearRange(candidate: CandidateTrack): Boolean {
    val year =
        candidate.track.releaseDate
            ?.substringBefore('-')
            ?.toIntOrNull()
    return year != null &&
        (minInclusive == null || year >= minInclusive) &&
        (maxExclusive == null || year < maxExclusive)
}

private fun TrackPredicate.DurationRange.matchesDurationRange(candidate: CandidateTrack): Boolean {
    val duration = candidate.track.durationMs
    return duration != null &&
        (minMsInclusive == null || duration >= minMsInclusive) &&
        (maxMsExclusive == null || duration < maxMsExclusive)
}

private fun TrackPredicate.AddedAtRange.matchesAddedAtRange(candidate: CandidateTrack): Boolean {
    val addedAt = candidate.addedAt
    return addedAt != null &&
        (minInclusive == null || addedAt >= minInclusive) &&
        (maxExclusive == null || addedAt < maxExclusive)
}

private fun Int?.orZero(): Int = this ?: 0

private fun compareBytes(
    left: ByteArray,
    right: ByteArray,
): Int {
    left.indices.firstOrNull { index -> left[index] != right[index] }?.let { index ->
        return unsigned(left[index]).compareTo(unsigned(right[index]))
    }
    return left.size.compareTo(right.size)
}

private fun unsigned(value: Byte): Int = value.toInt() and UNSIGNED_BYTE_MASK

private fun compareNullable(
    left: String?,
    right: String?,
): Int =
    when {
        left == null && right == null -> 0
        left == null -> 1
        right == null -> -1
        else -> left.compareTo(right)
    }
