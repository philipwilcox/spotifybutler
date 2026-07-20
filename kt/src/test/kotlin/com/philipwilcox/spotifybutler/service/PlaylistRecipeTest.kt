package com.philipwilcox.spotifybutler.service

import com.philipwilcox.spotifybutler.spotify.SpotifyTrack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PlaylistRecipeTest {
    private val seed = ByteArray(32) { it.toByte() }

    @Test
    fun `recipe round trip preserves canonical revision`() {
        val recipe = quotaRecipe()
        val encoded = PlaylistRecipeCodec.encode(recipe)
        val decoded = PlaylistRecipeCodec.decode(encoded)

        assertEquals(recipe, decoded)
        assertEquals(PlaylistRecipeCodec.revision(recipe), PlaylistRecipeCodec.revision(decoded))
        assertTrue(encoded.contains("\"type\":\"saved_tracks\""))
    }

    @Test
    fun `recipe engine applies predicate target and simultaneous quotas in rank order`() {
        val candidates =
            listOf(
                candidate("first", "2024-01-01T00:00:00Z", "artist-a", "album-1"),
                candidate("second", "2024-01-02T00:00:00Z", "artist-a", "album-2"),
                candidate("third", "2024-01-03T00:00:00Z", "artist-b", "album-1"),
                candidate("fourth", "2024-01-04T00:00:00Z", "artist-b", "album-3"),
            )
        val recipe = quotaRecipe()

        val result = PlaylistRecipeEngine().generate(recipe, candidates, seed)

        assertEquals(listOf("first", "fourth"), result.selected.map { it.track.id })
        assertEquals(listOf("second", "third"), result.rejectedByQuota.map { it.track.id })
        assertEquals(4, result.candidates.size)
        assertEquals(4, result.distinctCandidates.size)
    }

    @Test
    fun `recipe revision changes when semantics change`() {
        val original = quotaRecipe()
        val changed = original.copy(predicate = TrackPredicate.Explicitness(true))

        assertNotEquals(PlaylistRecipeCodec.revision(original), PlaylistRecipeCodec.revision(changed))
    }

    @Test
    fun `seed changes random selection while retaining target and eligibility`() {
        val candidates =
            listOf(
                candidate("first", "2024-01-01T00:00:00Z", "artist-a", "album-1"),
                candidate("second", "2024-01-02T00:00:00Z", "artist-a", "album-2"),
                candidate("third", "2024-01-03T00:00:00Z", "artist-b", "album-2"),
                candidate("fourth", "2024-01-04T00:00:00Z", "artist-b", "album-3"),
            )
        val recipe =
            quotaRecipe().copy(
                predicate = TrackPredicate.All,
                selection = SelectionPolicy(target = 2, rankBy = RankingStrategy.SeededRandom),
                ordering = OrderingPolicy.SeededRandom,
            )
        val first = PlaylistRecipeEngine().generate(recipe, candidates, seed)
        val second = PlaylistRecipeEngine().generate(recipe, candidates, ByteArray(32) { (it + 1).toByte() })

        assertEquals(2, first.selected.size)
        assertEquals(2, second.selected.size)
        assertNotEquals(first.selected.map { it.identity }, second.selected.map { it.identity })
    }

    @Test
    fun `selection rank uses stable known-answer bytes`() {
        val recipeRevision = PlaylistRecipeCodec.revision(quotaRecipe())
        val candidate = candidate("first", "2024-01-01T00:00:00Z", "artist-a", "album-1")

        assertEquals(
            "c316aba1f1e43d63de303abbc56b7827c6474d5953e1752878f9f6b290d56f97",
            selectionRank(seed, recipeRevision, candidate).toHex(),
        )
    }

    @Test
    fun `dsl and direct construction produce the same canonical recipe`() {
        val direct = quotaRecipe()
        val dsl =
            playlistRecipe {
                from(CandidateSource.SavedTracks)
                where { releaseYear(minInclusive = 2020) }
                distinctBy(CandidateIdentity.SpotifyUri)
                select {
                    target(2)
                    maximum(1, CandidateDimension.PrimaryArtistId)
                    maximum(1, CandidateDimension.AlbumId)
                    rankedBy(RankingStrategy.AddedAtAscending)
                }
                orderBy(OrderingPolicy.AddedAtAscending)
            }

        assertEquals(PlaylistRecipeCodec.encode(direct), PlaylistRecipeCodec.encode(dsl))
        assertEquals(PlaylistRecipeCodec.revision(direct), PlaylistRecipeCodec.revision(dsl))
    }

    @Test
    fun `all legacy definitions have a canonical recipe`() {
        val definitions = PlaylistQueries.definitions(2026, 2016)

        definitions.forEach { definition ->
            val recipe = definition.toPlaylistRecipe()
            assertEquals(1, recipe.schemaVersion, definition.id.name)
            assertTrue(PlaylistRecipeCodec.revision(recipe).isNotEmpty(), definition.id.name)
            assertEquals(recipe, PlaylistRecipeCodec.decode(PlaylistRecipeCodec.encode(recipe)), definition.id.name)
        }
    }

    private fun quotaRecipe() =
        PlaylistRecipe(
            source = CandidateSource.SavedTracks,
            predicate = TrackPredicate.ReleaseYearRange(minInclusive = 2020),
            selection =
                SelectionPolicy(
                    target = 2,
                    quotas =
                        listOf(
                            Quota(CandidateDimension.PrimaryArtistId, 1),
                            Quota(CandidateDimension.AlbumId, 1),
                        ),
                    rankBy = RankingStrategy.AddedAtAscending,
                ),
            ordering = OrderingPolicy.AddedAtAscending,
        )

    private fun candidate(
        id: String,
        addedAt: String,
        artistId: String,
        albumId: String,
    ) = CandidateTrack(
        track =
            SpotifyTrack(
                name = id,
                id = id,
                href = "https://example.invalid/$id",
                uri = "spotify:track:$id",
                releaseDate = "2024-01-01",
                primaryArtistId = artistId,
                rawJson = "{}",
                albumId = albumId,
                durationMs = 180000,
                explicit = false,
                artistIds = listOf(artistId),
            ),
        addedAt = addedAt,
        sourceOrdinal = 0,
    )

    private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }
}
