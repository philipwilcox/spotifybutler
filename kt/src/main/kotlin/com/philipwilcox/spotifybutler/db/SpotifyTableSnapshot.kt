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
)

internal fun SpotifyDatabase.exportTables(queries: SpotifyDatabaseQueries): SpotifyTableSnapshot =
    SpotifyTableSnapshot(
        savedTracks = exportTable("saved_tracks") { queries.exportSavedTrackRows() },
        topTracks = exportTable("top_tracks") { queries.exportTopTrackRows() },
        topArtists = exportTable("top_artists") { queries.exportTopArtistRows() },
        playlists = exportTable("playlists") { queries.exportPlaylistRows() },
        playlistTracks = exportTable("playlist_tracks") { queries.exportPlaylistTrackRows() },
        syncStatus = exportTable("sync_status") { queries.exportSyncStatusRows() },
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
            "snapshot_id" to row.snapshot_id,
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
