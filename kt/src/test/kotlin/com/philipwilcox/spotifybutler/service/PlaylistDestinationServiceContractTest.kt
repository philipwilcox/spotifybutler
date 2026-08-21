package com.philipwilcox.spotifybutler.service

import com.philipwilcox.spotifybutler.db.SpotifyStore
import com.philipwilcox.spotifybutler.spotify.SavedTrack
import com.philipwilcox.spotifybutler.spotify.SpotifyCacheSnapshot
import com.philipwilcox.spotifybutler.spotify.SpotifyPlaylist
import com.philipwilcox.spotifybutler.spotify.SpotifyTrack
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PlaylistDestinationServiceContractTest {
    @Test
    fun libraryPlaylistPublishPreservesOrderAndCreatesNoManagedMapping() {
        val path = Files.createTempDirectory("library-publish-contract-").resolve("cache.db")
        val gateway = RecordingDestinationGateway()
        SpotifyStore.open(path).use { store ->
            store.replaceCache(
                SpotifyCacheSnapshot(
                    listOf(
                        SavedTrack(null, track("one")),
                        SavedTrack(null, track("two")),
                    ),
                    emptyList(),
                    emptyList(),
                    listOf(SpotifyPlaylist("Library", "library", "href", "uri", "tracks", ownerId = "owner")),
                    emptyList(),
                ),
                1L,
                "owner",
            )
            val service = PlaylistDestinationService(store, gateway)

            val result = service.publishLibraryPlaylist("library", "owner", "token", listOf("two", "one", "two"))

            assertEquals(listOf("two", "one", "two"), result.trackIds)
            assertEquals(listOf("two", "one", "two"), store.libraryPlaylistTrackIds("owner", "library"))
            assertEquals(emptyList(), store.managedPlaylists("owner"))
        }
    }

    @Test
    fun libraryPlaylistPublishRejectsMissingCachedAndNonOwnedPlaylists() {
        val path = Files.createTempDirectory("library-publish-owner-contract-").resolve("cache.db")
        val gateway = RecordingDestinationGateway()
        SpotifyStore.open(path).use { store ->
            store.replaceCache(
                SpotifyCacheSnapshot(
                    emptyList(),
                    emptyList(),
                    emptyList(),
                    listOf(SpotifyPlaylist("Shared", "shared", "href", "uri", "tracks", ownerId = "other")),
                    emptyList(),
                ),
                1L,
                "owner",
            )
            val service = PlaylistDestinationService(store, gateway)

            assertFailsWith<LibraryPlaylistNotFoundException> {
                service.publishLibraryPlaylist("missing", "owner", "token", emptyList())
            }
            assertFailsWith<OwnerMismatchException> {
                service.publishLibraryPlaylist("shared", "owner", "token", emptyList())
            }
            assertEquals(0, gateway.calls)
        }
    }

    @Test
    fun libraryPlaylistPublishRechecksRemoteOwnershipBeforeReplacing() {
        val path = Files.createTempDirectory("library-publish-remote-owner-").resolve("cache.db")
        val gateway = RecordingDestinationGateway().apply { ownsPlaylist = false }
        SpotifyStore.open(path).use { store ->
            store.replaceCache(
                SpotifyCacheSnapshot(
                    emptyList(),
                    emptyList(),
                    emptyList(),
                    listOf(SpotifyPlaylist("Owned", "owned", "href", "uri", "tracks", ownerId = "owner")),
                    emptyList(),
                ),
                1L,
                "owner",
            )
            val service = PlaylistDestinationService(store, gateway)

            assertFailsWith<OwnerMismatchException> {
                service.publishLibraryPlaylist("owned", "owner", "token", emptyList())
            }
            assertEquals(0, gateway.calls)
        }
    }

    @Test
    fun publishCreationPersistsDestinationAndSyncUpdatesManagedState() {
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
            val created =
                service.publish(
                    "RECENT_LIKED_100",
                    "owner",
                    "token",
                    "Generated",
                    PublishAction.CREATE,
                    null,
                    listOf("one"),
                )
            assertEquals("created-playlist", created.spotifyPlaylistId)
            assertEquals("Generated", gateway.createdName)
            val synced = service.sync("RECENT_LIKED_100", "owner", "token", listOf("one"))
            assertEquals(listOf("one"), synced.trackIds)
            assertEquals("snapshot-3", service.current("RECENT_LIKED_100", "owner")?.lastSeenSnapshotId)
        }
    }

    @Test
    fun publishAdoptionPersistsMappingAndReplacesTracks() {
        val path = Files.createTempDirectory("publish-adoption-contract-").resolve("cache.db")
        val gateway = RecordingDestinationGateway()
        SpotifyStore.open(path).use { store ->
            store.replaceCache(
                SpotifyCacheSnapshot(
                    emptyList(),
                    emptyList(),
                    emptyList(),
                    listOf(
                        SpotifyPlaylist("Generated", "existing-playlist", "href", "uri", "tracks", ownerId = "owner"),
                    ),
                    emptyList(),
                ),
                1L,
                "owner",
            )
            val service = PlaylistDestinationService(store, gateway)
            val plan = service.planPublish("RECENT_LIKED_100", "owner", "Generated")
            assertEquals(PublishPlanAction.ADOPT, plan.action)
            val adopted =
                service.publish(
                    "RECENT_LIKED_100",
                    "owner",
                    "token",
                    "Generated",
                    PublishAction.ADOPT,
                    "existing-playlist",
                    emptyList(),
                )
            assertEquals("existing-playlist", adopted.spotifyPlaylistId)
            assertEquals(adopted, service.current("RECENT_LIKED_100", "owner"))
            assertEquals(0, gateway.currentCalls)
        }
    }

    private class RecordingDestinationGateway : PlaylistDestinationGateway {
        var calls = 0
        var currentCalls = 0
        var createdName: String? = null
        var ownsPlaylist = true

        override fun create(
            accessToken: String,
            name: String,
        ): String {
            createdName = name
            return "created-playlist"
        }

        override fun owns(
            accessToken: String,
            playlistId: String,
            ownerSpotifyUserId: String,
        ) = ownsPlaylist

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
        ): AuthoritativePlaylistState {
            currentCalls += 1
            return AuthoritativePlaylistState(playlistId, emptyList(), "snapshot-current")
        }
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
