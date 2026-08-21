package com.philipwilcox.spotifybutler.service

import com.philipwilcox.spotifybutler.db.SpotifyStore
import org.junit.jupiter.api.Tag
import java.nio.file.Files
import java.nio.file.Path
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Tag("playlist-generation-contract")
class PlaylistRecipeFixtureTest {
    private val seed = ByteArray(32) { index -> (index + 1).toByte() }

    @Test
    fun `recipe execution preserves legacy eligibility and establishes ordered results`() {
        val resource =
            requireNotNull(javaClass.classLoader.getResource("playlist-query-fixtures")) {
                "playlist query fixture directory is missing"
            }
        loadPlaylistQueryFixtures(Path.of(resource.toURI())).forEach(::assertRecipeFixture)
    }

    private fun assertRecipeFixture(fixture: PlaylistQueryFixture) {
        fixture.validate()
        val databasePath = Files.createTempDirectory("playlist-recipe-fixture-").resolve("cache.db")
        SpotifyStore.open(databasePath).use { store ->
            fixture.loadInto(store)
            val definitions = PlaylistQueries.definitions(fixture.currentYear)
            val expectations = fixture.expectations.associateBy { it.definitionId }
            definitions.forEach { definition ->
                assertRecipeDefinition(fixture, store, definition, requireNotNull(expectations[definition.id.name]))
            }
        }
    }

    private fun assertRecipeDefinition(
        fixture: PlaylistQueryFixture,
        store: SpotifyStore,
        definition: PlaylistDefinition,
        expectation: QueryFixtureExpectation,
    ) {
        val recipe = definition.toPlaylistRecipe()
        val result =
            PlaylistRecipeEngine().generate(
                recipe = recipe,
                candidates = store.candidates(recipe.source),
                seed = seed,
                context = store.recipeExecutionContext(),
            )
        val actualTracks = result.selected.map(CandidateTrack::track)
        val expectedUris = expectation.exactDesiredUris
        logPlaylistGenerationReport(
            PlaylistGenerationTestReport(
                fixtureName = fixture.name,
                definition = definition,
                executionPath = "recipe",
                actualTracks = actualTracks,
                expectedUris = expectedUris,
                recipeRevision = PlaylistRecipeCodec.revision(recipe),
                recipeJson = PlaylistRecipeCodec.encode(recipe),
                algorithmVersion = "playlist-generation-v1",
                seedHex = seed.toHex(),
                notes =
                    listOf(
                        "eligible=${result.candidates.size}",
                        "distinct=${result.distinctCandidates.size}",
                        "rejectedByQuota=${result.rejectedByQuota.size}",
                    ),
            ),
        )
        expectedUris?.let { expected ->
            if (definition.id !in RANDOM_OR_QUOTA_DEFINITIONS && !recipe.shuffleAfterGeneration) {
                assertEquals(expected, actualTracks.map { it.uri }, definition.id.name)
            }
        }
        expectation.selectionConstraints?.let { constraints ->
            assertEquals(constraints.expectedCount, actualTracks.size, definition.id.name)
            assertTrue(actualTracks.all { it.uri in constraints.eligibleUris }, definition.id.name)
            constraints.maxPerPrimaryArtist?.let { maxPerArtist ->
                val counts = actualTracks.groupingBy { it.primaryArtistId }.eachCount().values
                assertTrue(counts.all { it <= maxPerArtist }, definition.id.name)
            }
        }
    }

    @Test
    fun `recipe results survive SQLite insertion order and reopen variants`() {
        val resource =
            requireNotNull(javaClass.classLoader.getResource("playlist-query-fixtures")) {
                "playlist query fixture directory is missing"
            }
        loadPlaylistQueryFixtures(Path.of(resource.toURI())).forEach { fixture ->
            val definitions = PlaylistQueries.definitions(fixture.currentYear)
            val golden = loadBuiltInGoldens()
            val variants =
                listOf(
                    "canonical" to fixture.seedTables,
                    "reverse" to fixture.seedTables.reordered(reverse = true),
                    "shuffled" to fixture.seedTables.reordered(reverse = false),
                )
            val expectedByDefinition = golden.goldens.associate { it.definitionId to it.orderedUris }
            variants.forEach { (variantName, seedTables) ->
                val databasePath = Files.createTempDirectory("playlist-recipe-stability-").resolve("cache.db")
                SpotifyStore.open(databasePath).use { store ->
                    store.replaceCache(seedTables.toSnapshot(), syncTimestampMillis = 1L)
                    assertEquals(
                        expectedByDefinition,
                        seedTables.recipeResults(definitions),
                        "$variantName in-memory result",
                    )
                }
                SpotifyStore.openReadOnly(databasePath).use { store ->
                    val actual =
                        definitions.associate { definition ->
                            val recipe = definition.toPlaylistRecipe()
                            definition.id.name to
                                PlaylistRecipeEngine()
                                    .generate(
                                        recipe,
                                        store.candidates(recipe.source),
                                        seed,
                                        store.recipeExecutionContext(),
                                    ).selected
                                    .map { it.track.uri }
                        }
                    assertEquals(expectedByDefinition, actual, "$variantName after reopen")
                }
            }
        }
    }

    private fun loadBuiltInGoldens(): PlaylistRecipeGoldenFile {
        val resource =
            requireNotNull(
                javaClass.classLoader.getResource("playlist-generation-fixtures/built-ins-recipe-goldens.json"),
            )
        return PlaylistRecipeCodec.json.decodeFromString<PlaylistRecipeGoldenFile>(
            Files.readString(Path.of(resource.toURI())),
        )
    }

    private fun QueryFixtureSeedTables.recipeResults(
        definitions: List<PlaylistDefinition>,
    ): Map<String, List<String>> {
        val path = Files.createTempDirectory("playlist-recipe-stability-result-").resolve("cache.db")
        return SpotifyStore.open(path).use { store ->
            replaceCacheInto(store)
            definitions.associate { definition ->
                val recipe = definition.toPlaylistRecipe()
                definition.id.name to
                    PlaylistRecipeEngine()
                        .generate(recipe, store.candidates(recipe.source), seed, store.recipeExecutionContext())
                        .selected
                        .map { it.track.uri }
            }
        }
    }

    private fun QueryFixtureSeedTables.replaceCacheInto(store: SpotifyStore) {
        store.replaceCache(toSnapshot(), syncTimestampMillis = 1L)
    }

    private fun QueryFixtureSeedTables.reordered(reverse: Boolean): QueryFixtureSeedTables {
        fun <T> List<T>.reorder(): List<T> = if (reverse) asReversed() else shuffled(Random(47))
        return copy(
            savedTracks = savedTracks.reorder(),
            topTracks = topTracks.reorder(),
            topArtists = topArtists.reorder(),
            playlists = playlists.reorder(),
            playlistTracks = playlistTracks.reorder(),
        )
    }

    private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }

    companion object {
        private val RANDOM_OR_QUOTA_DEFINITIONS =
            setOf(
                PlaylistDefinitionId.RANDOM_LIKED_100,
                PlaylistDefinitionId.LIKED_PER_ARTIST,
                PlaylistDefinitionId.THREE_SAVED_SONGS_PER_ARTIST,
                PlaylistDefinitionId.ROLLING_RECENT_20,
                PlaylistDefinitionId.ROLLING_PRIOR_20,
                PlaylistDefinitionId.ROLLING_PRE_40,
                PlaylistDefinitionId.RECENT_5_PER_ARTIST,
            )
    }
}
