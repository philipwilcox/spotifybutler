package com.philipwilcox.spotifybutler.service

import com.philipwilcox.spotifybutler.db.SpotifyStore
import com.philipwilcox.spotifybutler.spotify.PlaylistTrack
import com.philipwilcox.spotifybutler.spotify.SavedTrack
import com.philipwilcox.spotifybutler.spotify.SpotifyCacheSnapshot
import com.philipwilcox.spotifybutler.spotify.SpotifyPlaylist
import com.philipwilcox.spotifybutler.spotify.SpotifyTrack
import org.junit.jupiter.api.Tag
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@Tag("playlist-generation-contract")
class PlaylistGenerationServiceTest {
    @Test
    fun `SQLite recipe result is stored and sent to mutation client in exact order`() {
        val path = Files.createTempDirectory("playlist-generation-e2e-").resolve("cache.db")
        val definition =
            PlaylistDefinition(
                PlaylistDefinitionId.RECENT_LIKED_100,
                "Generated Playlist",
                PlaylistQuery.RecentLiked(100),
            )
        val recipe =
            playlistRecipe {
                from(CandidateSource.SavedTracks)
                distinctBy(CandidateIdentity.SpotifyUri)
                select {
                    target(3)
                    rankedBy(RankingStrategy.AddedAtAscending)
                }
                orderBy(OrderingPolicy.AddedAtAscending)
            }
        val tracks = listOf(track("one"), track("two"), track("three"))
        val store = InMemoryGenerationStore()
        val client = RecordingGenerationMutationClient()

        SpotifyStore.open(path).use { database ->
            database.replaceCache(
                SpotifyCacheSnapshot(
                    savedTracks =
                        tracks.mapIndexed { index, track ->
                            SavedTrack("2026-01-${(index + 1).toString().padStart(2, '0')}T00:00:00Z", track)
                        },
                    topTracks = emptyList(),
                    topArtists = emptyList(),
                    playlists =
                        listOf(
                            SpotifyPlaylist(
                                name = "Generated Playlist",
                                id = "Generated Playlist-id",
                                href = "https://example.invalid/playlist",
                                uri = "spotify:playlist:generated",
                                tracksHref = "https://example.invalid/playlist/items",
                                snapshotId = "snapshot-1",
                            ),
                        ),
                    playlistTracks =
                        listOf(
                            PlaylistTrack("Generated Playlist", "2026-01-01T00:00:00Z", track("stale")),
                        ),
                ),
                syncTimestampMillis = 1L,
            )
            val record =
                PlaylistGenerationService(generationStore = store).generate(
                    id = "generation-1",
                    definition = definition,
                    recipe = recipe,
                    candidates = database.candidates(recipe.source),
                    context = database.recipeExecutionContext(),
                    seed = ByteArray(32) { it.toByte() },
                    cacheRevision = "cache-1",
                    algorithmVersion = "playlist-generation-v1",
                )
            val stored = assertNotNull(store.get("generation-1"))
            assertEquals(record.desiredTracks.map { it.uri }, stored.desiredTracks.map { it.uri })

            val plan = database.let { PlaylistPlanningService(it).planGenerated(definition, stored.desiredTracks) }
            PlaylistMutationService(client).apply(listOf(plan), dryRun = false)
        }

        assertEquals(
            listOf("replace Generated Playlist-id:one,two,three"),
            client.calls,
        )
    }

    private fun track(id: String) =
        SpotifyTrack(
            name = id,
            id = id,
            href = "https://example.invalid/$id",
            uri = "spotify:track:$id",
            releaseDate = "2026",
            primaryArtistId = "artist",
            rawJson =
                """{"name":"$id","id":"$id","href":"https://example.invalid/$id","uri":"spotify:track:$id"}""",
        )
}

private class RecordingGenerationMutationClient : PlaylistMutationClient {
    val calls = mutableListOf<String>()

    override fun createPlaylist(name: String): String = "$name-id"

    override fun addTracks(
        playlistId: String,
        tracks: List<SpotifyTrack>,
    ) {
        calls += "add $playlistId:${tracks.joinToString(",") { it.id }}"
    }

    override fun replaceTracks(
        playlistId: String,
        tracks: List<SpotifyTrack>,
    ) {
        calls += "replace $playlistId:${tracks.joinToString(",") { it.id }}"
    }
}
