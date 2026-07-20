package com.philipwilcox.spotifybutler.service

import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Clock
import java.time.Year

interface DuplicateCleanupService {
    fun clean(accessToken: String): DuplicateCleanupResult
}

class SpotifyDuplicateCleanupService(
    private val store: com.philipwilcox.spotifybutler.db.SpotifyStore,
    private val apiClient: com.philipwilcox.spotifybutler.spotify.SpotifyApiClient,
) : DuplicateCleanupService {
    override fun clean(accessToken: String): DuplicateCleanupResult {
        val duplicateIds = store.duplicateSavedTrackIds()
        if (duplicateIds.isEmpty()) return DuplicateCleanupResult()
        apiClient.removeSavedTracks(accessToken, duplicateIds)
        store.deleteSavedTracks(duplicateIds)
        return DuplicateCleanupResult(duplicateIds.size)
    }
}

data class DuplicateCleanupResult(
    val removedTrackCount: Int = 0,
)

class NoOpDuplicateCleanupService : DuplicateCleanupService {
    override fun clean(accessToken: String): DuplicateCleanupResult = DuplicateCleanupResult()
}

data class ButlerRunResult(
    val sync: CacheLoadResult,
    val duplicateCleanup: DuplicateCleanupResult,
    val playlistPlans: List<PlaylistPlan>,
    val playlistOutcomes: List<PlaylistMutationOutcome>,
)

class ButlerService(
    private val cacheService: SpotifyCacheService,
    private val planningService: PlaylistPlanningService,
    private val mutationService: PlaylistMutationService,
    private val duplicateCleanupService: DuplicateCleanupService = NoOpDuplicateCleanupService(),
    private val clock: Clock = Clock.systemUTC(),
    private val minYearForDiscoverWeekly: Int = 2018,
    private val dryRun: Boolean = false,
) {
    private val logger = KotlinLogging.logger {}

    fun run(
        accessToken: String,
        refresh: Boolean,
        ownerSpotifyUserId: String? = null,
    ): ButlerRunResult {
        val sync = cacheService.prepareLoadIfNeeded(accessToken, refresh, ownerSpotifyUserId)
        val duplicateCleanup =
            if (sync is CacheLoadResult.Loaded) duplicateCleanupService.clean(accessToken) else DuplicateCleanupResult()
        val definitions = PlaylistQueries.definitions(Year.now(clock).value, minYearForDiscoverWeekly)
        val plans = planningService.plan(definitions)
        val outcomes = mutationService.apply(plans, dryRun)
        val result = ButlerRunResult(sync, duplicateCleanup, plans, outcomes)
        logger.info {
            "Butler run completed: plans=${plans.size} outcomes=${outcomes.size} " +
                "created=${outcomes.count { it is PlaylistMutationOutcome.Created }} dryRun=$dryRun"
        }
        return result
    }
}
