package com.philipwilcox.spotifybutler.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PlaylistQueriesTest {
    @Test
    fun `catalog preserves the TypeScript order and names`() {
        val definitions = PlaylistQueries.definitions(2026, 2016)

        assertEquals(16, definitions.size)
        assertEquals(
            listOf(
                "100 Most Recent Liked Songs",
                "100 Random Liked Songs",
                "Collected Discover Weekly 2016 And On - Butler",
                "Liked Tracks, Twelve Per Artist",
                "3 Saved Songs Per Artist",
                "2007-2026, 12 Per Artist",
                "1987-2006, 12 Per Artist",
                "Pre-1987, 12 Per Artist",
                "Saved Tracks Not By My Top 50 Artists - Butler",
                "Saved Tracks Not In My Top 50 Tracks - Butler",
                "1980 - Butler Created",
                "1990 - Butler Created",
                "2000 - Butler Created",
                "2010 - Butler Created",
                "2020 - Butler Created",
                "Last 5 Years, Eight Per Artist",
            ),
            definitions.map(PlaylistDefinition::name),
        )
        assertEquals(
            PlaylistQuery.SavedInYearRangePerArtist(2007, 2026, 12),
            definitions[5].query,
        )
        assertEquals(PlaylistQuery.SavedSinceYearPerArtist(2022, 8), definitions.last().query)
        assertEquals(16, definitions.map(PlaylistDefinition::id).toSet().size)
        assertTrue(definitions.map(PlaylistDefinition::name).toSet().size == definitions.size)
    }

    @Test
    fun `catalog moves rolling names and bounds together`() {
        val definitions = PlaylistQueries.definitions(2031, 2018)

        assertEquals("2012-2031, 12 Per Artist", definitions[5].name)
        assertEquals(PlaylistQuery.SavedInYearRangePerArtist(2012, 2031, 12), definitions[5].query)
        assertEquals("1992-2011, 12 Per Artist", definitions[6].name)
        assertEquals(PlaylistQuery.SavedThroughYearPerArtist(1991, 12), definitions[7].query)
        assertEquals(PlaylistQuery.SavedSinceYearPerArtist(2027, 8), definitions.last().query)
        assertEquals(
            PlaylistQuery.CollectedDiscoverWeekly(
                PlaylistQueries.COLLECTED_DISCOVER_WEEKLY_NAME,
                PlaylistQueries.DISCOVER_WEEKLY_NAME,
                2018,
            ),
            definitions[2].query,
        )
        assertIs<PlaylistQuery.SavedInYearRange>(definitions[10].query)
    }
}
