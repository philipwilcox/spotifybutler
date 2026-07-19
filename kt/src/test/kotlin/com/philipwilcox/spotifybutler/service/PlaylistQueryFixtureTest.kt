package com.philipwilcox.spotifybutler.service

import com.philipwilcox.spotifybutler.db.SpotifyStore
import com.philipwilcox.spotifybutler.spotify.SpotifyTrack
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlaylistQueryFixtureTest {
    @Test
    fun `sanitized fixture validates and exercises every production query`() {
        val resource =
            requireNotNull(javaClass.classLoader.getResource("playlist-query-fixtures")) {
                "playlist query fixture directory is missing"
            }
        val fixtures = loadPlaylistQueryFixtures(Path.of(resource.toURI()))
        assertTrue(fixtures.isNotEmpty())

        fixtures.forEach { fixture ->
            fixture.validate()
            val databasePath =
                java.nio.file.Files
                    .createTempDirectory("playlist-query-fixture-")
                    .resolve("cache.db")
            SpotifyStore.open(databasePath).use { store ->
                fixture.loadInto(store)
                val definitions = PlaylistQueries.definitions(fixture.currentYear, fixture.minYearForDiscoverWeekly)
                val expectations = fixture.expectations.associateBy { it.definitionId }
                definitions.forEach { definition ->
                    val expectation = requireNotNull(expectations[definition.id.name])
                    val tracks = store.execute(definition.query)
                    assertQueryExpectation(definition.id, tracks, expectation)
                }
                assertPlanningExpectations(store, definitions, expectations)
            }
        }
    }

    private fun assertQueryExpectation(
        definitionId: PlaylistDefinitionId,
        tracks: List<SpotifyTrack>,
        expectation: QueryFixtureExpectation,
    ) {
        val actualUris = tracks.map(SpotifyTrack::uri)
        expectation.exactDesiredUris?.let { expected ->
            if (definitionId == PlaylistDefinitionId.RECENT_LIKED_100) {
                assertEquals(expected, actualUris, definitionId.name)
            } else {
                assertEquals(expected.toSet(), actualUris.toSet(), definitionId.name)
            }
        }
        expectation.selectionConstraints?.let { constraints ->
            assertEquals(constraints.expectedCount, tracks.size, definitionId.name)
            assertTrue(actualUris.all { it in constraints.eligibleUris }, definitionId.name)
            constraints.maxPerPrimaryArtist?.let { maxPerArtist ->
                assertTrue(
                    tracks
                        .groupingBy(SpotifyTrack::primaryArtistId)
                        .eachCount()
                        .values
                        .all { it <= maxPerArtist },
                )
            }
        }
    }

    private fun assertPlanningExpectations(
        store: SpotifyStore,
        definitions: List<PlaylistDefinition>,
        expectations: Map<String, QueryFixtureExpectation>,
    ) {
        val plans = PlaylistPlanningService(store).plan(definitions)
        assertEquals(definitions.map { it.id }, plans.map { it.definition.id })
        plans.forEach { plan ->
            val expectation = requireNotNull(expectations[plan.definition.id.name])
            expectation.existingPlaylist?.let { existing ->
                assertEquals(existing.id, plan.existingPlaylist?.id)
                assertEquals(existing.snapshotId, plan.existingPlaylist?.snapshotId)
            }
            expectation.exactAlreadyPresentUris?.let {
                assertEquals(
                    it.toSet(),
                    plan.alreadyPresentTracks
                        .map { t ->
                            t.uri
                        }.toSet(),
                )
            }
            expectation.exactAddedUris?.let { assertEquals(it.toSet(), plan.tracksToAdd.map { t -> t.uri }.toSet()) }
            expectation.exactRemovedUris?.let {
                assertEquals(
                    it.toSet(),
                    plan.tracksToRemove
                        .map { t ->
                            t.uri
                        }.toSet(),
                )
            }
        }
    }
}
