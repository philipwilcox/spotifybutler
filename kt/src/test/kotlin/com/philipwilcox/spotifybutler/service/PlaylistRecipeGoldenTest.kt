package com.philipwilcox.spotifybutler.service

import com.philipwilcox.spotifybutler.db.SpotifyStore
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Tag
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

@Serializable
data class PlaylistRecipeGoldenFile(
    val schemaVersion: Int,
    val fixtureName: String,
    val seedHex: String,
    val algorithmVersion: String,
    val goldens: List<PlaylistRecipeGolden>,
)

@Serializable
data class PlaylistRecipeGolden(
    val definitionId: String,
    val recipeRevision: String,
    val orderedUris: List<String>,
)

@Tag("playlist-generation-contract")
class PlaylistRecipeGoldenTest {
    @Test
    fun `all built-in recipes match reviewed exact ordered goldens`() {
        val goldenResource =
            requireNotNull(
                javaClass.classLoader.getResource("playlist-generation-fixtures/built-ins-recipe-goldens.json"),
            )
        val golden =
            PlaylistRecipeCodec.json.decodeFromString<PlaylistRecipeGoldenFile>(
                Files.readString(Path.of(goldenResource.toURI())),
            )
        val fixtureResource =
            requireNotNull(javaClass.classLoader.getResource("playlist-query-fixtures"))
        val fixture =
            loadPlaylistQueryFixtures(Path.of(fixtureResource.toURI())).single { it.name == golden.fixtureName }
        val expected = golden.goldens.associateBy { it.definitionId }

        fixture.validate()
        val path = Files.createTempDirectory("playlist-recipe-golden-").resolve("cache.db")
        SpotifyStore.open(path).use { store ->
            fixture.loadInto(store)
            PlaylistQueries.definitions(fixture.currentYear).forEach { definition ->
                val recipe = definition.toPlaylistRecipe()
                val result =
                    PlaylistRecipeEngine().generate(
                        recipe,
                        store.candidates(recipe.source),
                        golden.seedHex.toSeedBytes(),
                        store.recipeExecutionContext(),
                    )
                logPlaylistSelectionResult("golden-${definition.id.name}", result)
                val goldenResult = requireNotNull(expected[definition.id.name])
                assertEquals(goldenResult.recipeRevision, PlaylistRecipeCodec.revision(recipe), definition.id.name)
                assertEquals(goldenResult.orderedUris, result.selected.map { it.track.uri }, definition.id.name)
            }
        }
        assertEquals(15, expected.size)
    }
}

private fun String.toSeedBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
