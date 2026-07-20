package com.philipwilcox.spotifybutler.db

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SpotifyStoreUserPlaylistDefinitionTest {
    @Test
    fun `saving a definition replaces its ordered items and preserves owner isolation`() {
        val path = Files.createTempDirectory("spotify-user-definition-").resolve("cache.db")

        SpotifyStore.open(path).use { store ->
            store.saveUserPlaylistDefinition(
                StoredUserPlaylistDefinition(
                    "definition-1",
                    "owner-one",
                    "First",
                    "revision-one",
                    listOf("track-1", "track-2"),
                ),
            )
            store.saveUserPlaylistDefinition(
                StoredUserPlaylistDefinition("definition-2", "owner-two", "Second", "revision-two", listOf("track-3")),
            )
            store.saveUserPlaylistDefinition(
                StoredUserPlaylistDefinition(
                    "definition-1",
                    "owner-one",
                    "Renamed",
                    "revision-three",
                    listOf("track-2", "track-1", "track-2"),
                ),
            )

            assertEquals(
                StoredUserPlaylistDefinition(
                    "definition-1",
                    "owner-one",
                    "Renamed",
                    "revision-three",
                    listOf("track-2", "track-1", "track-2"),
                ),
                store.userPlaylistDefinition("definition-1", "owner-one"),
            )
            assertNull(store.userPlaylistDefinition("definition-1", "owner-two"))
            assertEquals(listOf("definition-2"), store.userPlaylistDefinitions("owner-two").map { it.id })

            val snapshot = store.exportTables()
            assertEquals(2, snapshot.userPlaylistDefinitions.size)
            assertEquals(4, snapshot.userPlaylistDefinitionItems.size)
            assertEquals(
                listOf("track-2", "track-1", "track-2"),
                snapshot.userPlaylistDefinitionItems
                    .filter { it["definition_id"]?.toString() == "\"definition-1\"" }
                    .map { it["track_id"]?.toString()?.trim('"') },
            )
        }
    }
}
