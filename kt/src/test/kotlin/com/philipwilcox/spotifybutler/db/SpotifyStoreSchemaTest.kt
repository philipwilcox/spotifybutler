package com.philipwilcox.spotifybutler.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.philipwilcox.spotifybutler.spotify.SpotifyCacheSnapshot
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SpotifyStoreSchemaTest {
    @Test
    fun `fresh database uses final schema and persists cache metadata`() {
        val path = Files.createTempDirectory("spotify-schema-").resolve("cache.db")
        val revision =
            SpotifyStore.open(path).use { store ->
                assertEquals(null, store.cacheMetadata())
                store.replaceCache(emptySnapshot(), 10L, "owner-one")
                val metadata = assertNotNull(store.cacheMetadata())
                assertEquals("owner-one", metadata.ownerSpotifyUserId)
                assertEquals(10L, metadata.syncTimestampMillis)
                assertEquals("ready", metadata.completionState)
                metadata.revision
            }

        val schema = JdbcSqliteDriver("jdbc:sqlite:$path").use { it.cacheMetadataSchema() }
        assertTrue("refresh_operation_id" !in schema)
        assertTrue("user_playlist_definitions" in schema)
        assertTrue("user_playlist_definition_items" in schema)

        SpotifyStore.open(path).use { store ->
            assertEquals(revision, store.cacheMetadata()?.revision)
        }
    }

    private fun SqlDriver.cacheMetadataSchema(): String =
        executeQuery(
            null,
            "SELECT sql FROM sqlite_master WHERE type = 'table' AND name IN " +
                "('cache_metadata', 'user_playlist_definitions', 'user_playlist_definition_items') " +
                "ORDER BY name",
            { cursor ->
                QueryResult.Value(
                    buildString {
                        while (cursor.next().value) {
                            append(cursor.getString(0)).append('\n')
                        }
                    },
                )
            },
            0,
            {},
        ).value

    private fun emptySnapshot(): SpotifyCacheSnapshot =
        SpotifyCacheSnapshot(
            topArtists = emptyList(),
            topTracks = emptyList(),
            savedTracks = emptyList(),
            playlists = emptyList(),
            playlistTracks = emptyList(),
        )
}
