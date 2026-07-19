package com.philipwilcox.spotifybutler.service

import org.junit.jupiter.api.Tag
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

@Tag("playlist-generation-contract")
class PlaylistGenerationFixtureTest {
    @Test
    fun `readable composability fixtures produce exact ordered results`() {
        val resource =
            requireNotNull(javaClass.classLoader.getResource("playlist-generation-fixtures")) {
                "playlist generation fixture directory is missing"
            }
        loadPlaylistGenerationFixtures(Path.of(resource.toURI())).forEach { fixture ->
            fixture.validate()
            fixture.cases.forEach { testCase ->
                val definition =
                    PlaylistDefinition(
                        id = PlaylistDefinitionId.RECENT_LIKED_100,
                        name = testCase.name,
                        query = PlaylistQuery.RecentLiked(100),
                    )
                val result =
                    PlaylistRecipeEngine().generate(
                        recipe = testCase.recipe,
                        candidates = fixture.candidates(),
                        seed = testCase.seedBytes(),
                    )
                val actualIds = result.selected.map { it.track.id }
                logPlaylistGenerationReport(
                    PlaylistGenerationTestReport(
                        fixtureName = fixture.name,
                        definition = definition,
                        executionPath = "recipe-fixture:${testCase.name}",
                        actualTracks = result.selected.map(CandidateTrack::track),
                        expectedUris = testCase.expectedIds.map { fixture.trackById(it).uri },
                        recipeRevision = PlaylistRecipeCodec.revision(testCase.recipe),
                        recipeJson = PlaylistRecipeCodec.encode(testCase.recipe),
                        algorithmVersion = "playlist-generation-v1",
                        seedHex = testCase.seedHex,
                        notes =
                            listOf(
                                "eligible=${result.candidates.size}",
                                "distinct=${result.distinctCandidates.size}",
                                "rejectedByQuota=${result.rejectedByQuota.size}",
                            ),
                    ),
                )
                assertEquals(testCase.expectedIds, actualIds, "${fixture.name}/${testCase.name}")
            }
        }
    }
}
