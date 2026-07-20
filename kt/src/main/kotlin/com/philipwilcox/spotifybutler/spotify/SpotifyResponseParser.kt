package com.philipwilcox.spotifybutler.spotify

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.net.URI

private val spotifyJson = Json { ignoreUnknownKeys = true }

internal fun parseSpotifyResponse(responseBody: String): JsonObject =
    spotifyJson.parseToJsonElement(responseBody).jsonObject

internal fun parseSpotifyCurrentUser(response: JsonObject): SpotifyCurrentUser =
    SpotifyCurrentUser(response.optionalString("display_name"), response.requiredString("id", "current user"))

internal fun parseSavedTrack(item: JsonObject): SavedTrack? {
    val track = item["track"] as? JsonObject ?: return null
    return SavedTrack(item.optionalString("added_at"), parseSpotifyTrack(track, "saved track"))
}

internal fun parseSpotifyTrack(
    item: JsonObject,
    context: String,
): SpotifyTrack {
    val album = item["album"] as? JsonObject
    val artists = (item["artists"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    val firstArtist = artists.firstOrNull()
    return SpotifyTrack(
        name = item.requiredString("name", context),
        id = item.requiredString("id", context),
        href = item.requiredString("href", context),
        uri = item.requiredString("uri", context),
        releaseDate = album?.optionalString("release_date"),
        primaryArtistId = firstArtist?.optionalString("id"),
        rawJson = item.toString(),
        albumId = album?.optionalString("id"),
        durationMs = item.optionalLong("duration_ms"),
        explicit = item.optionalBoolean("explicit"),
        artistIds = artists.mapNotNull { it.optionalString("id") },
    )
}

internal fun decodeStoredTrack(
    trackJson: String,
    context: String,
): SpotifyTrack =
    try {
        parseSpotifyTrack(spotifyJson.parseToJsonElement(trackJson).jsonObject, context)
    } catch (exception: IllegalArgumentException) {
        throw IllegalArgumentException("Could not decode stored track for $context", exception)
    }

internal fun parseSpotifyArtist(item: JsonObject): SpotifyArtist =
    SpotifyArtist(
        name = item.requiredString("name", "top artist"),
        id = item.requiredString("id", "top artist"),
        href = item.requiredString("href", "top artist"),
        uri = item.requiredString("uri", "top artist"),
    )

internal fun parseSpotifyPlaylist(item: JsonObject): SpotifyPlaylist {
    // Older sanitized captures can contain both metadata shapes while recording only the legacy page URL.
    val items =
        (item["tracks"] as? JsonObject)
            ?: (item["items"] as? JsonObject)
            ?: error("Spotify playlist response did not contain items metadata")
    return SpotifyPlaylist(
        name = item.requiredString("name", "playlist"),
        id = item.requiredString("id", "playlist"),
        href = item.requiredString("href", "playlist"),
        uri = item.requiredString("uri", "playlist"),
        tracksHref = items.requiredString("href", "playlist items metadata"),
        snapshotId = item.optionalString("snapshot_id"),
    )
}

internal fun parsePlaylistTrack(
    item: JsonObject,
    playlistName: String,
): PlaylistTrack? {
    val track = (item["item"] as? JsonObject) ?: (item["track"] as? JsonObject) ?: return null
    return PlaylistTrack(
        playlistName,
        item.optionalString("added_at"),
        parseSpotifyTrack(track, "playlist track"),
    )
}

internal fun parsePageItems(
    response: JsonObject,
    uri: URI,
): List<JsonObject> =
    (response["items"] as? JsonArray)?.map { item ->
        item as? JsonObject ?: error("Spotify page at $uri contained a non-object item")
    }
        ?: error("Spotify page at $uri did not contain an items array")

internal fun JsonObject.optionalString(key: String): String? = get(key)?.jsonPrimitive?.contentOrNull

internal fun JsonObject.optionalLong(key: String): Long? = get(key)?.jsonPrimitive?.longOrNull

internal fun JsonObject.optionalBoolean(key: String): Boolean? = get(key)?.jsonPrimitive?.booleanOrNull

private fun JsonObject.requiredString(
    key: String,
    context: String,
): String = optionalString(key) ?: throw IllegalArgumentException("Spotify $context response did not contain $key")
