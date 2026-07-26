package com.philipwilcox.spotifybutler.service

import com.philipwilcox.spotifybutler.db.SpotifyStore
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.Base64

@Suppress("TooManyFunctions")
class PlaylistPreviewService(
    private val store: SpotifyStore,
    private val clock: Clock = Clock.systemUTC(),
    private val algorithmVersion: String = "recipe-engine-v1",
) {
    fun preview(
        definitionId: String,
        ownerSpotifyUserId: String,
        seed: String? = null,
    ): PlaylistPreview {
        val definition = resolve(definitionId, ownerSpotifyUserId)
        val recipeRevision = PlaylistRecipeCodec.revision(definition.recipe)
        val dependencies = dependencies(definition.recipe, ownerSpotifyUserId)
        val effectiveSeed = seed ?: defaultSeed(definitionId, recipeRevision, dependencies)
        val unavailable = dependencies.filterNot(SourceDependency::usable)
        if (unavailable.isNotEmpty()) {
            return PlaylistPreview(
                definitionId,
                PreviewStatus.UNAVAILABLE,
                emptyList(),
                effectiveSeed,
                recipeRevision,
                algorithmVersion,
                dependencies,
                Instant.now(clock),
                "One or more recipe sources are not ready",
            )
        }
        val candidates = store.candidates(ownerSpotifyUserId, definition.recipe.source)
        val result =
            PlaylistRecipeEngine().generate(
                definition.recipe,
                candidates,
                seedBytes(effectiveSeed),
                store.recipeExecutionContext(ownerSpotifyUserId),
            )
        val ids = result.selected.map { it.track.id }
        val status =
            when {
                ids.isEmpty() -> PreviewStatus.EMPTY
                dependencies.any { it.itemCount == 0 } -> PreviewStatus.PARTIAL
                else -> PreviewStatus.READY
            }
        return PlaylistPreview(
            definitionId,
            status,
            ids,
            effectiveSeed,
            recipeRevision,
            algorithmVersion,
            dependencies,
            Instant.now(clock),
        )
    }

    fun previewSummary(
        definitionId: String,
        ownerSpotifyUserId: String,
    ): PlaylistPreview = preview(definitionId, ownerSpotifyUserId)

    fun resolveBuiltIn(
        definition: PlaylistDefinition,
        ownerSpotifyUserId: String,
        seed: String? = null,
    ): PlaylistPreview = preview(definition.id.name, ownerSpotifyUserId, seed)

    fun resolveOwnerDefinition(
        definition: com.philipwilcox.spotifybutler.db.StoredUserPlaylistDefinition,
        ownerSpotifyUserId: String,
    ): PlaylistDefinitionView {
        require(
            definition.ownerSpotifyUserId == ownerSpotifyUserId,
        ) { "Definition does not belong to the authenticated owner" }
        return definition.toView()
    }

    fun definitions(ownerSpotifyUserId: String): List<PlaylistDefinitionView> =
        builtIns(ownerSpotifyUserId) + store.userPlaylistDefinitions(ownerSpotifyUserId).map { it.toView() }

    fun updateShuffleAfterGeneration(
        definitionId: String,
        ownerSpotifyUserId: String,
        enabled: Boolean,
    ): PlaylistDefinitionView {
        val ownerDefinition = store.userPlaylistDefinition(definitionId, ownerSpotifyUserId)
        if (ownerDefinition != null) {
            store.saveUserPlaylistDefinition(
                ownerDefinition.copy(
                    recipe = requireNotNull(ownerDefinition.recipe).copy(shuffleAfterGeneration = enabled),
                ),
            )
            return requireNotNull(store.userPlaylistDefinition(definitionId, ownerSpotifyUserId)) {
                "Playlist definition was not saved"
            }.toView()
        }
        require(builtIns(ownerSpotifyUserId).any { it.definitionId == definitionId }) {
            "Playlist definition not found"
        }
        store.savePlaylistRecipePreference(definitionId, ownerSpotifyUserId, enabled)
        return builtIns(ownerSpotifyUserId).first { it.definitionId == definitionId }
    }

    fun resolve(
        definitionId: String,
        ownerSpotifyUserId: String,
    ): PlaylistDefinitionView =
        store.userPlaylistDefinition(definitionId, ownerSpotifyUserId)?.toView()
            ?: builtIns(ownerSpotifyUserId).firstOrNull { it.definitionId == definitionId }
            ?: error("Playlist definition not found")

    fun sourceDependencies(
        definition: PlaylistDefinitionView,
        ownerSpotifyUserId: String,
    ): List<SourceDependency> = dependencies(definition.recipe, ownerSpotifyUserId)

    private fun builtIns(ownerSpotifyUserId: String): List<PlaylistDefinitionView> =
        PlaylistQueries
            .definitions(
                clock.instant().atZone(java.time.ZoneOffset.UTC).year,
                BUILT_IN_MIN_YEAR,
            ).map { definition ->
                val recipe = definition.toPlaylistRecipe()
                val shuffleAfterGeneration =
                    store.playlistRecipePreference(definition.id.name, ownerSpotifyUserId)
                        ?: recipe.shuffleAfterGeneration
                PlaylistDefinitionView(
                    definition.id.name,
                    null,
                    definition.name,
                    "Built-in Butler recipe",
                    DefinitionKind.BUILT_IN,
                    false,
                    true,
                    recipe.copy(shuffleAfterGeneration = shuffleAfterGeneration),
                )
            }

    private fun dependencies(
        recipe: PlaylistRecipe,
        owner: String,
    ): List<SourceDependency> =
        (sourceKeys(recipe.source, owner) + predicateKeys(recipe.predicate)).distinct().map { key ->
            val snapshot = store.sourceSnapshot(owner, key)
            SourceDependency(
                key,
                snapshot.resourceKind,
                snapshot.sourceRevision,
                snapshot.lastSyncedAt,
                snapshot.itemCount,
                snapshot.status == CacheSourceStatus.READY,
            )
        }

    private fun sourceKeys(
        source: CandidateSource,
        owner: String,
    ): List<String> =
        when (source) {
            CandidateSource.SavedTracks -> listOf(CacheSourceKey.SAVED_TRACKS)
            CandidateSource.TopTracks -> listOf(CacheSourceKey.TOP_TRACKS)
            is CandidateSource.PlaylistItems ->
                listOf(
                    if (source.playlistName.startsWith(CacheSourceKey.PLAYLIST_ITEMS_PREFIX)) {
                        source.playlistName
                    } else {
                        store
                            .findPlaylistByName(
                                source.playlistName,
                                owner,
                            )?.id
                            ?.let { "${CacheSourceKey.PLAYLIST_ITEMS_PREFIX}$it" }
                            ?: "${CacheSourceKey.PLAYLIST_ITEMS_PREFIX}${source.playlistName}"
                    },
                )
            is CandidateSource.Union -> source.sources.flatMap { sourceKeys(it, owner) }
            is CandidateSource.Difference -> sourceKeys(source.left, owner) + sourceKeys(source.right, owner)
            is CandidateSource.Filtered -> sourceKeys(source.source, owner)
        }

    private fun predicateKeys(predicate: TrackPredicate): List<String> =
        when (predicate) {
            TrackPredicate.NotInTopArtists -> listOf(CacheSourceKey.TOP_ARTISTS)
            TrackPredicate.NotInTopTracks -> listOf(CacheSourceKey.TOP_TRACKS)
            is TrackPredicate.And -> predicate.predicates.flatMap(::predicateKeys)
            is TrackPredicate.Or -> predicate.predicates.flatMap(::predicateKeys)
            is TrackPredicate.Not -> predicateKeys(predicate.predicate)
            else -> emptyList()
        }

    private fun defaultSeed(
        definitionId: String,
        recipeRevision: String,
        dependencies: List<SourceDependency>,
    ): String {
        val canonical =
            buildString {
                append(definitionId).append('|').append(recipeRevision)
                dependencies.forEach {
                    append(
                        '|',
                    ).append(it.sourceKey).append('=').append(it.sourceRevision ?: "empty")
                }
            }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray()),
        )
    }

    private fun seedBytes(seed: String): ByteArray =
        runCatching { Base64.getUrlDecoder().decode(seed) }.getOrElse { seed.toByteArray() }.let { bytes ->
            if (bytes.isNotEmpty()) bytes else ByteBuffer.allocate(EMPTY_SEED_BYTES).putLong(0L).array()
        }

    private fun com.philipwilcox.spotifybutler.db.StoredUserPlaylistDefinition.toView(): PlaylistDefinitionView =
        PlaylistDefinitionView(
            id,
            ownerSpotifyUserId,
            name,
            description,
            DefinitionKind.OWNER,
            true,
            enabled,
            recipe ?: error("Stored definition has no typed recipe"),
        )

    private companion object {
        const val BUILT_IN_MIN_YEAR = 2018
        const val EMPTY_SEED_BYTES = 8
    }
}
