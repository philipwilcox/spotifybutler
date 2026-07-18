package com.philipwilcox.spotifybutler.service

import kotlin.test.Ignore
import kotlin.test.Test

class SpotifyCacheLoadContractTest {
    @Test
    @Ignore("Add sanitized, paginated Spotify response fixtures captured from the INFO scrape logs.")
    fun `loads every Spotify collection into its corresponding SQLite table`() {
        TODO(
            "Use fake saved-track, top-track, top-artist, playlist, and playlist-track pages; assert table row " +
                "counts and stored IDs, metadata, and release years.",
        )
    }

    @Test
    @Ignore("Add sanitized track fixtures captured from the INFO scrape logs.")
    fun `preserves each source track object as SQLite track JSON`() {
        TODO(
            "Assert saved_tracks, top_tracks, and playlist_tracks retain the exact fake track JSON used as input.",
        )
    }

    @Test
    @Ignore("Add a fake Spotify cache fetcher and a temporary SQLDelight SQLite database fixture.")
    fun `fetches only an empty cache unless refresh is requested`() {
        TODO(
            "Assert the first load writes sync_status, a second non-refresh load does not call Spotify, and refresh " +
                "replaces all cached rows.",
        )
    }
}
