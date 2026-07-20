package com.philipwilcox.spotifybutler.service

import com.philipwilcox.spotifybutler.db.SpotifyStore
import com.philipwilcox.spotifybutler.spotify.PlaylistTrack
import com.philipwilcox.spotifybutler.spotify.SavedTrack
import com.philipwilcox.spotifybutler.spotify.SpotifyCacheSnapshot
import com.philipwilcox.spotifybutler.spotify.SpotifyPlaylist
import com.philipwilcox.spotifybutler.spotify.SpotifyTrack
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PlaylistPlanningServiceTest {
    @Test
    fun `planning compares URI membership while preserving duplicate rows`() {
        withStore { store ->
            val desired = listOf(track("desired", "spotify:track:shared"), track("duplicate", "spotify:track:shared"))
            val existing = listOf(track("existing", "spotify:track:shared"), track("stale", "spotify:track:stale"))
            store.replaceCache(snapshot(desired, existing), 1L)

            val plan = PlaylistPlanningService(store).plan(listOf(definition("playlist")), OWNER_ONE).single()

            assertEquals(2, plan.desiredTracks.size)
            assertEquals(
                listOf("spotify:track:shared", "spotify:track:shared"),
                plan.alreadyPresentTracks.map { it.uri },
            )
            assertEquals(emptyList(), plan.tracksToAdd)
            assertEquals(listOf("spotify:track:stale"), plan.tracksToRemove.map { it.uri })
        }
    }

    @Test
    fun `malformed cached track JSON fails before a plan is returned`() {
        withStore { store ->
            val malformed = track("bad", "spotify:track:bad", rawJson = "not-json")
            store.replaceCache(snapshot(listOf(track("desired", "spotify:track:desired")), listOf(malformed)), 1L)

            val failure =
                assertFailsWith<IllegalArgumentException> {
                    PlaylistPlanningService(store).plan(listOf(definition("playlist")), OWNER_ONE)
                }

            assertEquals(true, failure.message?.contains("playlist playlist row 0"))
        }
    }

    @Test
    fun `duplicate playlist names fail instead of choosing a cache row`() {
        withStore { store ->
            val playlist =
                SpotifyPlaylist(
                    "playlist",
                    "one",
                    "href-one",
                    "uri-one",
                    "tracks-one",
                    "snapshot-one",
                    ownerId = OWNER_ONE,
                )
            val duplicate = playlist.copy(id = "two", href = "href-two", uri = "uri-two")
            store.replaceCache(
                SpotifyCacheSnapshot(emptyList(), emptyList(), emptyList(), listOf(playlist, duplicate), emptyList()),
                1L,
            )

            assertFailsWith<IllegalArgumentException> {
                PlaylistPlanningService(
                    store,
                ).plan(listOf(definition("playlist")), OWNER_ONE)
            }
        }
    }

    @Test
    fun `planning does not adopt a same-named playlist owned by another user`() {
        withStore { store ->
            val foreignPlaylist =
                SpotifyPlaylist(
                    name = "playlist",
                    id = "foreign-playlist",
                    href = "href-foreign",
                    uri = "uri-foreign",
                    tracksHref = "tracks-foreign",
                    snapshotId = "snapshot-foreign",
                    ownerId = OWNER_TWO,
                )
            store.replaceCache(
                SpotifyCacheSnapshot(
                    savedTracks = listOf(SavedTrack("2026-01-01T00:00:00Z", track("desired"))),
                    topTracks = emptyList(),
                    topArtists = emptyList(),
                    playlists = listOf(foreignPlaylist),
                    playlistTracks = listOf(PlaylistTrack("playlist", "2026-01-01T00:00:00Z", track("stale"))),
                ),
                1L,
                OWNER_ONE,
            )

            val plan = PlaylistPlanningService(store).plan(listOf(definition("playlist")), OWNER_ONE).single()

            assertEquals(null, plan.existingPlaylist)
            assertEquals(listOf("spotify:track:desired"), plan.tracksToAdd.map { it.uri })
            assertEquals(emptyList(), plan.tracksToRemove)
        }
    }

    private fun withStore(block: (SpotifyStore) -> Unit) {
        val path = Files.createTempDirectory("playlist-planning-").resolve("cache.db")
        SpotifyStore.open(path).use(block)
    }

    private fun snapshot(
        desired: List<SpotifyTrack>,
        existing: List<SpotifyTrack>,
    ) = SpotifyCacheSnapshot(
        savedTracks = desired.map { SavedTrack("2026-01-01T00:00:00Z", it) },
        topTracks = emptyList(),
        topArtists = emptyList(),
        playlists =
            listOf(
                SpotifyPlaylist(
                    "playlist",
                    "playlist-id",
                    "href",
                    "uri",
                    "tracks",
                    "snapshot",
                    ownerId = OWNER_ONE,
                ),
            ),
        playlistTracks = existing.map { PlaylistTrack("playlist", "2026-01-02T00:00:00Z", it) },
    )

    private companion object {
        const val OWNER_ONE = "owner-one"
        const val OWNER_TWO = "owner-two"
    }

    private fun definition(name: String) =
        PlaylistDefinition(PlaylistDefinitionId.RECENT_LIKED_100, name, PlaylistQuery.RecentLiked(100))

    private fun track(
        id: String,
        uri: String = "spotify:track:$id",
        rawJson: String = "{\"href\":\"href-$id\",\"id\":\"$id\",\"name\":\"$id\",\"uri\":\"$uri\"}",
    ) = SpotifyTrack(id, id, "href-$id", uri, "2026", "artist", rawJson)
}
