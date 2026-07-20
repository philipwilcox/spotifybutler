package com.philipwilcox.spotifybutler.spotify

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
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
        albumName = album?.optionalString("name"),
        albumHref = album?.optionalString("href"),
        albumUri = album?.optionalString("uri"),
        available = item.explicitPlayable() ?: item.optionalString("id") != null,
        artists =
            artists
                .map { artist ->
                    SpotifyArtistReference(
                        id = artist.optionalString("id"),
                        name = artist.optionalString("name"),
                        href = artist.optionalString("href"),
                        uri = artist.optionalString("uri"),
                    )
                }.filter { artist -> artist.name != null || artist.href != null || artist.uri != null },
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
        description = item.optionalString("description"),
        public = item.optionalBoolean("public"),
        collaborative = item.optionalBoolean("collaborative"),
        ownerId = (item["owner"] as? JsonObject)?.optionalString("id"),
        itemCount = items["total"]?.jsonPrimitive?.intOrNull,
        displayUrl = (item["external_urls"] as? JsonObject)?.optionalString("spotify"),
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

@Suppress("CyclomaticComplexMethod")
internal fun parsePlaylistItem(
    item: JsonObject,
    playlist: SpotifyPlaylist,
    position: Int,
): SpotifyPlaylistItem {
    val nested = (item["item"] as? JsonObject) ?: (item["track"] as? JsonObject)
    val itemType = nested?.optionalString("type") ?: item.optionalString("type")
    val isLocal = item.optionalBoolean("is_local") ?: nested?.optionalBoolean("is_local") ?: false
    val track =
        nested?.takeIf { itemType == null || itemType == "track" }?.let {
            runCatching { parseSpotifyTrack(it, "playlist item") }.getOrNull()
        }
    val itemId = nested?.optionalString("id")
    val itemUri = nested?.optionalString("uri")
    val unavailable = nested?.explicitPlayable() == false
    val isPlayable = track != null && !unavailable && !isLocal && itemType != "episode"
    val status =
        when {
            nested == null -> "inaccessible"
            itemType != null && itemType != "track" -> "unsupported_type"
            isLocal -> "local"
            track == null || unavailable -> "unavailable"
            else -> "playable"
        }
    return SpotifyPlaylistItem(
        playlistId = playlist.id,
        playlistName = playlist.name,
        position = position,
        addedAt = item.optionalString("added_at"),
        addedById = (item["added_by"] as? JsonObject)?.optionalString("id"),
        isLocal = isLocal,
        itemType = itemType,
        isPlayable = isPlayable,
        itemId = itemId,
        itemUri = itemUri,
        status = status,
        rawJson = item.toString(),
        track = track?.takeIf { isPlayable },
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

private fun JsonObject.explicitPlayable(): Boolean? =
    get("is_playable")?.toString()?.trim('"')?.lowercase()?.let { value ->
        when (value) {
            "true" -> true
            "false" -> false
            else -> null
        }
    }

private fun JsonObject.requiredString(
    key: String,
    context: String,
): String = optionalString(key) ?: throw IllegalArgumentException("Spotify $context response did not contain $key")
