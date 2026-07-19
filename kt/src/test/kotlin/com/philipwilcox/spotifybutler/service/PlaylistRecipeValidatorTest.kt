package com.philipwilcox.spotifybutler.service

import kotlin.test.Test
import kotlin.test.assertFailsWith

class PlaylistRecipeValidatorTest {
    @Test
    fun `validator rejects empty unions and duplicate quota dimensions`() {
        assertFailsWith<IllegalArgumentException> {
            PlaylistRecipeCodec.canonicalize(
                validRecipe().copy(source = CandidateSource.Union(emptyList())),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            PlaylistRecipeCodec.canonicalize(
                validRecipe().copy(
                    selection =
                        validRecipe().selection.copy(
                            quotas =
                                listOf(
                                    Quota(CandidateDimension.AlbumId, 1),
                                    Quota(CandidateDimension.AlbumId, 2),
                                ),
                        ),
                ),
            )
        }
    }

    @Test
    fun `validator rejects invalid ranges and complexity`() {
        assertFailsWith<IllegalArgumentException> {
            PlaylistRecipeCodec.canonicalize(
                validRecipe().copy(predicate = TrackPredicate.DurationRange(300, 200)),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            PlaylistRecipeCodec.canonicalize(
                validRecipe().copy(
                    source =
                        CandidateSource.Union(
                            List(PlaylistRecipeValidator.MAX_SOURCE_FAN_OUT + 1) {
                                CandidateSource.SavedTracks
                            },
                        ),
                ),
            )
        }
    }

    private fun validRecipe() =
        PlaylistRecipe(
            source = CandidateSource.SavedTracks,
            selection = SelectionPolicy(rankBy = RankingStrategy.SeededRandom),
            ordering = OrderingPolicy.SeededRandom,
        )
}
