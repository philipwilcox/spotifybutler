package com.philipwilcox.spotifybutler.db

import com.philipwilcox.spotifybutler.support.progress
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class SpotifyTableSnapshot(
    val savedTracks: List<JsonObject>,
    val topTracks: List<JsonObject>,
    val topArtists: List<JsonObject>,
    val playlists: List<JsonObject>,
    val playlistTracks: List<JsonObject>,
    val syncStatus: List<JsonObject>,
    val cacheMetadata: List<JsonObject>,
    val playlistDetails: List<JsonObject>,
    val playlistItems: List<JsonObject>,
    val songs: List<JsonObject>,
    val songArtists: List<JsonObject>,
    val managedPlaylists: List<JsonObject>,
    val userPlaylistDefinitions: List<JsonObject>,
    val userPlaylistDefinitionItems: List<JsonObject>,
)

internal fun SpotifyDatabase.exportTables(queries: SpotifyDatabaseQueries): SpotifyTableSnapshot =
    SpotifyTableSnapshot(
        savedTracks = exportTable("saved_tracks") { queries.exportSavedTrackRows() },
        topTracks = exportTable("top_tracks") { queries.exportTopTrackRows() },
        topArtists = exportTable("top_artists") { queries.exportTopArtistRows() },
        playlists = exportTable("playlists") { queries.exportPlaylistRows() },
        playlistTracks = exportTable("playlist_tracks") { queries.exportPlaylistTrackRows() },
        syncStatus = exportTable("sync_status") { queries.exportSyncStatusRows() },
        cacheMetadata = exportTable("cache_metadata") { queries.exportCacheMetadataRows() },
        playlistDetails = exportTable("playlist_details") { queries.exportPlaylistDetailsRows() },
        playlistItems = exportTable("playlist_items") { queries.exportPlaylistItemsRows() },
        songs = exportTable("songs") { queries.exportSongRows() },
        songArtists = exportTable("song_artists") { queries.exportSongArtistRows() },
        managedPlaylists = exportTable("managed_playlists") { queries.exportManagedPlaylistRows() },
        userPlaylistDefinitions =
            exportTable(
                "user_playlist_definitions",
            ) { queries.exportUserPlaylistDefinitionRows() },
        userPlaylistDefinitionItems =
            exportTable("user_playlist_definition_items") {
                queries.exportUserPlaylistDefinitionItemRows()
            },
    )

private fun <T> exportTable(
    table: String,
    export: () -> List<T>,
): List<T> {
    progress("exporting $table")
    return export().also {
        progress("exported $table")
    }
}

private fun SpotifyDatabaseQueries.exportSavedTrackRows() =
    exportSavedTracks().executeAsList().map { row ->
        row.jsonObject(
            "name" to row.name,
            "id" to row.id,
            "primary_artist_id" to row.primary_artist_id,
            "release_date" to row.release_date,
            "release_year" to row.release_year,
            "href" to row.href,
            "uri" to row.uri,
            "added_at" to row.added_at,
            "track_json" to row.track_json,
        )
    }

private fun SpotifyDatabaseQueries.exportTopTrackRows() =
    exportTopTracks().executeAsList().map { row ->
        row.jsonObject(
            "name" to row.name,
            "id" to row.id,
            "href" to row.href,
            "uri" to row.uri,
            "track_json" to row.track_json,
        )
    }

private fun SpotifyDatabaseQueries.exportTopArtistRows() =
    exportTopArtists().executeAsList().map { row ->
        row.jsonObject(
            "name" to row.name,
            "id" to row.id,
            "href" to row.href,
            "uri" to row.uri,
        )
    }

private fun SpotifyDatabaseQueries.exportPlaylistRows() =
    exportPlaylists().executeAsList().map { row ->
        row.jsonObject(
            "name" to row.name,
            "id" to row.id,
            "href" to row.href,
            "uri" to row.uri,
            "tracks_href" to row.tracks_href,
        )
    }

private fun SpotifyDatabaseQueries.exportPlaylistTrackRows() =
    exportPlaylistTracks().executeAsList().map { row ->
        row.jsonObject(
            "playlist_name" to row.playlist_name,
            "added_at" to row.added_at,
            "release_date" to row.release_date,
            "release_year" to row.release_year,
            "name" to row.name,
            "primary_artist_id" to row.primary_artist_id,
            "id" to row.id,
            "href" to row.href,
            "uri" to row.uri,
            "track_json" to row.track_json,
        )
    }

private fun SpotifyDatabaseQueries.exportSyncStatusRows() =
    exportSyncStatus().executeAsList().map { row ->
        row.jsonObject("sync_timestamp_millis" to row.sync_timestamp_millis)
    }

private fun SpotifyDatabaseQueries.exportCacheMetadataRows() =
    exportCacheMetadata().executeAsList().map { row ->
        row.jsonObject(
            "singleton_id" to row.singleton_id,
            "cache_revision" to row.cache_revision,
            "sync_timestamp_millis" to row.sync_timestamp_millis,
            "owner_spotify_user_id" to row.owner_spotify_user_id,
            "completion_state" to row.completion_state,
        )
    }

private fun SpotifyDatabaseQueries.exportPlaylistDetailsRows() =
    exportPlaylistDetails().executeAsList().map { row ->
        row.jsonObject(
            "playlist_id" to row.playlist_id,
            "description" to row.description,
            "is_public" to row.is_public,
            "collaborative" to row.collaborative,
            "owner_id" to row.owner_id,
            "item_count" to row.item_count,
            "display_url" to row.display_url,
        )
    }

private fun SpotifyDatabaseQueries.exportPlaylistItemsRows() =
    exportPlaylistItems().executeAsList().map { row ->
        row.jsonObject(
            "playlist_id" to row.playlist_id,
            "position" to row.position,
            "added_at" to row.added_at,
            "added_by_id" to row.added_by_id,
            "is_local" to row.is_local,
            "item_type" to row.item_type,
            "is_playable" to row.is_playable,
            "item_id" to row.item_id,
            "item_uri" to row.item_uri,
            "status" to row.status,
            "complete_item_json" to row.complete_item_json,
        )
    }

private fun SpotifyDatabaseQueries.exportSongRows() =
    exportSongs().executeAsList().map { row ->
        row.jsonObject(
            "id" to row.id,
            "name" to row.name,
            "href" to row.href,
            "uri" to row.uri,
            "album_id" to row.album_id,
            "album_name" to row.album_name,
            "album_href" to row.album_href,
            "album_uri" to row.album_uri,
            "release_date" to row.release_date,
            "duration_ms" to row.duration_ms,
            "explicit" to row.explicit,
            "available" to row.available,
            "track_json" to row.track_json,
        )
    }

private fun SpotifyDatabaseQueries.exportSongArtistRows() =
    exportSongArtists().executeAsList().map { row ->
        row.jsonObject(
            "track_id" to row.track_id,
            "position" to row.position,
            "artist_id" to row.artist_id,
            "name" to row.name,
            "href" to row.href,
            "uri" to row.uri,
        )
    }

private fun SpotifyDatabaseQueries.exportManagedPlaylistRows() =
    exportManagedPlaylists().executeAsList().map { row ->
        row.jsonObject(
            "definition_id" to row.definition_id,
            "spotify_playlist_id" to row.spotify_playlist_id,
            "owner_spotify_user_id" to row.owner_spotify_user_id,
        )
    }

private fun SpotifyDatabaseQueries.exportUserPlaylistDefinitionRows() =
    exportUserPlaylistDefinitions().executeAsList().map { row ->
        row.jsonObject(
            "id" to row.id,
            "owner_spotify_user_id" to row.owner_spotify_user_id,
            "name" to row.name,
        )
    }

private fun SpotifyDatabaseQueries.exportUserPlaylistDefinitionItemRows() =
    exportUserPlaylistDefinitionItems().executeAsList().map { row ->
        row.jsonObject(
            "definition_id" to row.definition_id,
            "position" to row.position,
            "track_id" to row.track_id,
        )
    }

private fun Any.jsonObject(vararg values: Pair<String, Any?>): JsonObject =
    buildJsonObject {
        values.forEach { (key, value) -> put(key, value.toJsonElement()) }
    }

private fun Any?.toJsonElement(): JsonElement =
    when (this) {
        null -> JsonNull
        is Boolean -> JsonPrimitive(this)
        is Number -> JsonPrimitive(this)
        else -> JsonPrimitive(toString())
    }
