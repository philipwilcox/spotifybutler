package com.philipwilcox.spotifybutler.service

import com.philipwilcox.spotifybutler.db.SpotifyStore
import com.philipwilcox.spotifybutler.spotify.SavedTrack
import com.philipwilcox.spotifybutler.spotify.SpotifyCacheSnapshot
import com.philipwilcox.spotifybutler.spotify.SpotifyTrack
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PlaylistDestinationServiceContractTest {
    @Test
    fun creationPersistsOnlyAfterGatewayReturnsAndSyncUpdatesManagedState() {
        val path = Files.createTempDirectory("destination-contract-").resolve("cache.db")
        val gateway = RecordingDestinationGateway()
        SpotifyStore.open(path).use { store ->
            store.replaceCache(
                SpotifyCacheSnapshot(
                    listOf(SavedTrack("2026-01-01T00:00:00Z", track("one"))),
                    emptyList(),
                    emptyList(),
                    emptyList(),
                    emptyList(),
                ),
                1L,
                "owner",
            )
            val service = PlaylistDestinationService(store, gateway)
            assertEquals(null, service.current("RECENT_LIKED_100", "owner"))
            val created = service.create("RECENT_LIKED_100", "owner", "token", DestinationCreateRequest("Generated"))
            assertEquals("created-playlist", created.spotifyPlaylistId)
            val synced = service.sync("RECENT_LIKED_100", "owner", "token", listOf("one"))
            assertEquals(listOf("one"), synced.trackIds)
            assertEquals("snapshot-2", service.current("RECENT_LIKED_100", "owner")?.lastSeenSnapshotId)
        }
    }

    @Test
    fun missingSyncIsRejectedAndOneTimeUpdateDoesNotCreateOrChangeMapping() {
        val path = Files.createTempDirectory("one-time-contract-").resolve("cache.db")
        val gateway = RecordingDestinationGateway()
        SpotifyStore.open(path).use { store ->
            store.replaceCache(
                SpotifyCacheSnapshot(emptyList(), emptyList(), emptyList(), emptyList(), emptyList()),
                1L,
                "owner",
            )
            val service = PlaylistDestinationService(store, gateway)
            assertFailsWith<MissingDestinationException> {
                service.sync("RECENT_LIKED_100", "owner", "token", emptyList())
            }
            service.create("RECENT_LIKED_100", "owner", "token", DestinationCreateRequest("Generated"))
            val before = service.current("RECENT_LIKED_100", "owner")
            val result = service.oneTimeUpdate("RECENT_LIKED_100", "owner", "token", "existing-playlist", emptyList())
            assertEquals(false, result.tracked)
            assertEquals(before, service.current("RECENT_LIKED_100", "owner"))
        }
    }

    private class RecordingDestinationGateway : PlaylistDestinationGateway {
        var calls = 0

        override fun create(
            accessToken: String,
            request: DestinationCreateRequest,
        ) = "created-playlist"

        override fun owns(
            accessToken: String,
            playlistId: String,
            ownerSpotifyUserId: String,
        ) = true

        override fun replace(
            accessToken: String,
            playlistId: String,
            trackIds: List<String>,
        ): AuthoritativePlaylistState {
            calls += 1
            return AuthoritativePlaylistState(playlistId, trackIds, "snapshot-" + (calls + 1))
        }

        override fun current(
            accessToken: String,
            playlistId: String,
        ) = AuthoritativePlaylistState(playlistId, emptyList(), "snapshot-current")
    }

    private companion object {
        fun track(id: String) =
            SpotifyTrack(
                id,
                id,
                "href:" + id,
                "spotify:track:" + id,
                "2026",
                "artist",
                "{\"name\":\"" + id + "\",\"id\":\"" + id + "\",\"href\":\"href:" + id + "\",\"uri\":\"spotify:track:" +
                    id +
                    "\"}",
            )
    }
}
