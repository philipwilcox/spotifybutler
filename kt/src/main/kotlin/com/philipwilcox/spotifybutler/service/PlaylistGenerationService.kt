package com.philipwilcox.spotifybutler.service

import com.philipwilcox.spotifybutler.spotify.SpotifyTrack

data class PlaylistGenerationRecord(
    val id: String,
    val definitionId: PlaylistDefinitionId,
    val recipeRevision: String,
    val algorithmVersion: String,
    val cacheRevision: String,
    val seed: ByteArray,
    val desiredTracks: List<SpotifyTrack>,
)

interface GenerationStore {
    fun save(record: PlaylistGenerationRecord)

    fun get(id: String): PlaylistGenerationRecord?
}

class InMemoryGenerationStore : GenerationStore {
    private val records = mutableMapOf<String, PlaylistGenerationRecord>()

    override fun save(record: PlaylistGenerationRecord) {
        check(records.putIfAbsent(record.id, record) == null) {
            "Generation ${record.id} already exists"
        }
    }

    override fun get(id: String): PlaylistGenerationRecord? = records[id]
}

class PlaylistGenerationService(
    private val engine: PlaylistRecipeEngine = PlaylistRecipeEngine(),
    private val generationStore: GenerationStore,
) {
    fun generate(
        id: String,
        definition: PlaylistDefinition,
        recipe: PlaylistRecipe,
        candidates: List<CandidateTrack>,
        context: RecipeExecutionContext,
        seed: ByteArray,
        cacheRevision: String,
        algorithmVersion: String,
    ): PlaylistGenerationRecord {
        val canonicalRecipe = PlaylistRecipeCodec.canonicalize(recipe)
        val result = engine.generate(canonicalRecipe, candidates, seed, context)
        return PlaylistGenerationRecord(
            id = id,
            definitionId = definition.id,
            recipeRevision = PlaylistRecipeCodec.revision(canonicalRecipe),
            algorithmVersion = algorithmVersion,
            cacheRevision = cacheRevision,
            seed = seed.copyOf(),
            desiredTracks = result.selected.map(CandidateTrack::track),
        ).also(generationStore::save)
    }
}
