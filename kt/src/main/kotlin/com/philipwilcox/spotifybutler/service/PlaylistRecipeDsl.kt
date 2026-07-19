package com.philipwilcox.spotifybutler.service

@DslMarker
annotation class PlaylistRecipeDsl

fun playlistRecipe(block: PlaylistRecipeBuilder.() -> Unit): PlaylistRecipe =
    PlaylistRecipeBuilder().apply(block).build()

@PlaylistRecipeDsl
class PlaylistRecipeBuilder {
    private var source: CandidateSource? = null
    private var predicate: TrackPredicate = TrackPredicate.All
    private var distinctness: DistinctnessPolicy = DistinctnessPolicy.By(CandidateIdentity.SpotifyUri)
    private var selection: SelectionPolicy? = null
    private var ordering: OrderingPolicy? = null

    fun from(candidateSource: CandidateSource) {
        source = candidateSource
    }

    fun where(block: PlaylistPredicateBuilder.() -> Unit) {
        predicate = PlaylistPredicateBuilder().apply(block).build()
    }

    fun distinctBy(identity: CandidateIdentity) {
        distinctness = DistinctnessPolicy.By(identity)
    }

    fun keepAll() {
        distinctness = DistinctnessPolicy.KeepAll
    }

    fun select(block: PlaylistSelectionBuilder.() -> Unit) {
        selection = PlaylistSelectionBuilder().apply(block).build()
    }

    fun orderBy(orderingPolicy: OrderingPolicy) {
        ordering = orderingPolicy
    }

    fun build(): PlaylistRecipe =
        PlaylistRecipe(
            source = requireNotNull(source) { "A playlist recipe must define a source" },
            predicate = predicate,
            distinctness = distinctness,
            selection = requireNotNull(selection) { "A playlist recipe must define selection" },
            ordering = requireNotNull(ordering) { "A playlist recipe must define ordering" },
        )
}

@PlaylistRecipeDsl
class PlaylistPredicateBuilder {
    private var predicate: TrackPredicate = TrackPredicate.All

    fun releaseYear(
        minInclusive: Int? = null,
        maxExclusive: Int? = null,
    ) {
        predicate = TrackPredicate.ReleaseYearRange(minInclusive, maxExclusive)
    }

    fun duration(
        minMsInclusive: Long? = null,
        maxMsExclusive: Long? = null,
    ) {
        predicate = TrackPredicate.DurationRange(minMsInclusive, maxMsExclusive)
    }

    fun albumIds(ids: List<String>) {
        predicate = TrackPredicate.AlbumIdIn(ids)
    }

    fun artistIds(ids: List<String>) {
        predicate = TrackPredicate.ArtistIdIn(ids)
    }

    fun explicit(value: Boolean) {
        predicate = TrackPredicate.Explicitness(value)
    }

    internal fun build(): TrackPredicate = predicate
}

@PlaylistRecipeDsl
class PlaylistSelectionBuilder {
    private var target: Int? = null
    private val quotas = mutableListOf<Quota>()
    private var rankBy: RankingStrategy? = null

    fun target(value: Int?) {
        target = value
    }

    fun maximum(
        maximum: Int,
        per: CandidateDimension,
    ) {
        quotas += Quota(per, maximum)
    }

    fun rankedBy(strategy: RankingStrategy) {
        rankBy = strategy
    }

    internal fun build(): SelectionPolicy =
        SelectionPolicy(
            target = target,
            quotas = quotas.toList(),
            rankBy = requireNotNull(rankBy) { "A playlist recipe must define a ranking strategy" },
        )
}
