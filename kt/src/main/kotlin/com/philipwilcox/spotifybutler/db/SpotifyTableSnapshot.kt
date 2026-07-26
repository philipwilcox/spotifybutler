package com.philipwilcox.spotifybutler.db

import kotlinx.serialization.json.JsonObject

/** Sanitized, owner-scoped table rows used by fixture tooling and diagnostics. */
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
    val cacheSourceSync: List<JsonObject> = emptyList(),
    val playlistRecipePreferences: List<JsonObject> = emptyList(),
)
