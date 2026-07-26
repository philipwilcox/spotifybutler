package com.philipwilcox.spotifybutler.db

import com.philipwilcox.spotifybutler.service.CandidateSource
import com.philipwilcox.spotifybutler.service.OrderingPolicy
import com.philipwilcox.spotifybutler.service.PlaylistRecipe
import com.philipwilcox.spotifybutler.service.RankingStrategy
import com.philipwilcox.spotifybutler.service.SelectionPolicy
import com.philipwilcox.spotifybutler.spotify.SavedTrack
import com.philipwilcox.spotifybutler.spotify.SpotifyCacheSnapshot
import com.philipwilcox.spotifybutler.spotify.SpotifyPlaylist
import com.philipwilcox.spotifybutler.spotify.SpotifyPlaylistItem
import com.philipwilcox.spotifybutler.spotify.SpotifyTrack
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class SpotifyStoreTargetContractTest {
    @Test
    fun schemaVersionAndLegacyTables() {
        val path = Files.createTempDirectory("target-schema-").resolve("cache.db")
        SpotifyStore.open(path).use { store -> assertEquals(1, store.schemaVersion()) }
        DriverManager.getConnection("jdbc:sqlite:" + path).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table'").use { result ->
                    val tables = buildList { while (result.next()) add(result.getString(1)) }
                    assertEquals(false, "cache_metadata" in tables)
                    assertEquals(false, "sync_status" in tables)
                    assertEquals(true, "cache_source_sync" in tables)
                }
            }
        }
    }

    @Test
    fun incompatibleExistingSchemaIsRejected() {
        val path = Files.createTempDirectory("incompatible-schema-").resolve("cache.db")
        DriverManager.getConnection("jdbc:sqlite:" + path).use { connection ->
            connection.createStatement().use {
                it.execute("CREATE TABLE schema_version (singleton_id INTEGER PRIMARY KEY, version INTEGER NOT NULL)")
            }
            connection.prepareStatement("INSERT INTO schema_version VALUES (1, 999)").use { it.executeUpdate() }
        }
        val failure = assertFailsWith<IllegalArgumentException> { SpotifyStore.open(path) }
        assertEquals(true, failure.message.orEmpty().contains("Recreate"))
    }

    @Test
    fun identicalIdentifiersRemainIsolatedByOwner() {
        val path = Files.createTempDirectory("owner-isolation-").resolve("cache.db")
        SpotifyStore.open(path).use { store ->
            store.replaceCache(snapshot("track-a", "playlist-shared"), 10L, "owner-a")
            store.replaceCache(snapshot("track-b", "playlist-shared"), 20L, "owner-b")
            assertEquals(listOf("track-a"), store.songs("owner-a").map(SpotifyTrack::id))
            assertEquals(listOf("track-b"), store.songs("owner-b").map(SpotifyTrack::id))
            assertEquals(listOf("track-a"), store.playlistItems("playlist-shared", "owner-a").mapNotNull { it.itemId })
            assertEquals(listOf("track-b"), store.playlistItems("playlist-shared", "owner-b").mapNotNull { it.itemId })
            store.saveManagedPlaylist("definition-shared", "playlist-shared", "owner-a", 30L)
            store.saveManagedPlaylist("definition-shared", "playlist-shared", "owner-b", 40L)
            assertEquals("owner-a", store.managedPlaylist("definition-shared", "owner-a")?.ownerSpotifyUserId)
            assertEquals("owner-b", store.managedPlaylist("definition-shared", "owner-b")?.ownerSpotifyUserId)
        }
    }

    @Test
    fun typedOwnerRecipePersistsWithOrderedItems() {
        val path = Files.createTempDirectory("owner-recipes-").resolve("cache.db")
        val recipe =
            PlaylistRecipe(
                source = CandidateSource.SavedTracks,
                selection = SelectionPolicy(2, rankBy = RankingStrategy.SeededRandom),
                ordering = OrderingPolicy.SeededRandom,
            )
        SpotifyStore.open(path).use { store ->
            store.saveUserPlaylistDefinition(
                StoredUserPlaylistDefinition("same", "owner-a", "A", listOf("item-a"), "desc-a", true, recipe),
            )
            store.saveUserPlaylistDefinition(
                StoredUserPlaylistDefinition("same-b", "owner-b", "B", listOf("item-b"), "desc-b", true, recipe),
            )
            val saved = assertNotNull(store.userPlaylistDefinition("same", "owner-a"))
            assertEquals("desc-a", saved.description)
            assertEquals(recipe, saved.recipe)
            assertEquals(listOf("item-a"), saved.trackIds)
            assertEquals(null, store.userPlaylistDefinition("same", "owner-b"))
        }
    }

    private fun snapshot(
        trackId: String,
        playlistId: String,
    ) = SpotifyCacheSnapshot(
        savedTracks = listOf(SavedTrack("2026-01-01T00:00:00Z", track(trackId))),
        topTracks = emptyList(),
        topArtists = emptyList(),
        playlists =
            listOf(
                SpotifyPlaylist(
                    "Shared",
                    playlistId,
                    "href",
                    "spotify:playlist:" + playlistId,
                    "tracks",
                    ownerId = "owner",
                ),
            ),
        playlistTracks = emptyList(),
        playlistItems =
            listOf(
                SpotifyPlaylistItem(
                    playlistId,
                    "Shared",
                    0,
                    null,
                    null,
                    false,
                    "track",
                    true,
                    trackId,
                    "spotify:track:" + trackId,
                    "playable",
                    "{\"item\":" + track(trackId).rawJson + "}",
                    track(trackId),
                ),
            ),
    )

    private fun track(id: String) =
        SpotifyTrack(
            id,
            id,
            "href:" + id,
            "spotify:track:" + id,
            "2026",
            "artist",
            "{\"name\":\"" + id + "\",\"id\":\"" + id + "\",\"href\":\"href:" + id +
                "\",\"uri\":\"spotify:track:" + id +
                "\"}",
        )
}
