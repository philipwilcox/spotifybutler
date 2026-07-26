package com.philipwilcox.spotifybutler.support

import com.philipwilcox.spotifybutler.db.SpotifyTableSnapshot
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class SpotifyFixture(
    val schemaVersion: Int,
    val name: String,
    val responses: List<SpotifyFixtureResponse>,
    val expectedTables: ExpectedTables,
)

const val SPOTIFY_FIXTURE_SCHEMA_VERSION = 2

fun SpotifyFixture.validate() {
    require(schemaVersion == SPOTIFY_FIXTURE_SCHEMA_VERSION) {
        "Unsupported Spotify fixture schema version $schemaVersion; expected $SPOTIFY_FIXTURE_SCHEMA_VERSION"
    }
}

@Serializable
data class SpotifyFixtureResponse(
    val method: String,
    val path: String,
    val status: Int,
    val body: JsonElement,
)

@Serializable
data class ExpectedTables(
    @SerialName("saved_tracks") val savedTracks: List<JsonObject> = emptyList(),
    @SerialName("top_tracks") val topTracks: List<JsonObject> = emptyList(),
    @SerialName("top_artists") val topArtists: List<JsonObject> = emptyList(),
    val playlists: List<JsonObject> = emptyList(),
    @SerialName("playlist_tracks") val playlistTracks: List<JsonObject> = emptyList(),
    @SerialName("sync_status") val syncStatus: List<JsonObject> = emptyList(),
    @SerialName("cache_metadata") val cacheMetadata: List<JsonObject> = emptyList(),
    @SerialName("playlist_details") val playlistDetails: List<JsonObject> = emptyList(),
    @SerialName("playlist_items") val playlistItems: List<JsonObject> = emptyList(),
    val songs: List<JsonObject> = emptyList(),
    @SerialName("song_artists") val songArtists: List<JsonObject> = emptyList(),
    @SerialName("managed_playlists") val managedPlaylists: List<JsonObject> = emptyList(),
    @SerialName("user_playlist_definitions") val userPlaylistDefinitions: List<JsonObject> = emptyList(),
    @SerialName("user_playlist_definition_items") val userPlaylistDefinitionItems: List<JsonObject> = emptyList(),
    @SerialName("cache_source_sync") val cacheSourceSync: List<JsonObject> = emptyList(),
)

val spotifyFixtureJson =
    Json {
        ignoreUnknownKeys = false
        explicitNulls = true
        prettyPrint = false
    }

fun SpotifyTableSnapshot.toExpectedTables(includeNormalizedProjections: Boolean = true): ExpectedTables =
    ExpectedTables(
        savedTracks = savedTracks.map(::canonicalizeRow),
        topTracks = topTracks.map(::canonicalizeRow),
        topArtists = topArtists.map(::canonicalizeRow),
        playlists = playlists.map(::canonicalizeRow),
        playlistTracks = playlistTracks.map(::canonicalizeRow),
        syncStatus = syncStatus.map(::canonicalizeRow),
        cacheMetadata.takeIf { includeNormalizedProjections }.orEmpty().map(::canonicalizeCacheMetadataRow),
        playlistDetails = playlistDetails.takeIf { includeNormalizedProjections }.orEmpty().map(::canonicalizeRow),
        playlistItems = playlistItems.takeIf { includeNormalizedProjections }.orEmpty().map(::canonicalizeRow),
        songs = songs.takeIf { includeNormalizedProjections }.orEmpty().map(::canonicalizeRow),
        songArtists = songArtists.takeIf { includeNormalizedProjections }.orEmpty().map(::canonicalizeRow),
        managedPlaylists = managedPlaylists.takeIf { includeNormalizedProjections }.orEmpty().map(::canonicalizeRow),
        userPlaylistDefinitions =
            userPlaylistDefinitions.takeIf { includeNormalizedProjections }.orEmpty().map(::canonicalizeRow),
        userPlaylistDefinitionItems =
            userPlaylistDefinitionItems.takeIf { includeNormalizedProjections }.orEmpty().map(::canonicalizeRow),
        cacheSourceSync = cacheSourceSync.takeIf { includeNormalizedProjections }.orEmpty().map(::canonicalizeRow),
    )

fun ExpectedTables.limitToCapturedItems(capture: ValidatedCapture): ExpectedTables {
    val savedTrackIds = capture.itemIdsFor("/v1/me/tracks", nestedTrack = true)
    val topTrackIds = capture.itemIdsFor("/v1/me/top/tracks")
    val topArtistIds = capture.itemIdsFor("/v1/me/top/artists")
    val playlistTrackIds = capture.playlistTrackIds()
    return copy(
        savedTracks = savedTracks.filter { it.idIsIn(savedTrackIds) },
        topTracks = topTracks.filter { it.idIsIn(topTrackIds) },
        topArtists = topArtists.filter { it.idIsIn(topArtistIds) },
        playlistTracks = playlistTracks.filter { it.belongsToCapturedPlaylistTrack(playlistTrackIds) },
        playlistItems = playlistItems.filter { it.belongsToCapturedPlaylist(playlistTrackIds.keys) },
        playlistDetails = playlistDetails.filter { it.idIsIn(playlistTrackIds.keys) },
    )
}

fun SpotifyFixture.limitAvailableMarkets(maxItems: Int): SpotifyFixture {
    require(maxItems >= 0) { "Maximum available markets must not be negative" }
    return copy(
        responses = responses.map { response -> response.copy(body = response.body.limitAvailableMarkets(maxItems)) },
        expectedTables =
            expectedTables.copy(
                savedTracks = expectedTables.savedTracks.map { it.limitAvailableMarkets(maxItems) },
                topTracks = expectedTables.topTracks.map { it.limitAvailableMarkets(maxItems) },
                playlistTracks = expectedTables.playlistTracks.map { it.limitAvailableMarkets(maxItems) },
            ),
    )
}

private fun JsonObject.limitAvailableMarkets(maxItems: Int): JsonObject =
    JsonObject(
        mapValues { (key, value) ->
            if (key == "track_json" && value is JsonPrimitive && value.isString) {
                JsonPrimitive(
                    spotifyFixtureJson.parseToJsonElement(value.content).limitAvailableMarkets(maxItems).toString(),
                )
            } else {
                value.limitAvailableMarkets(maxItems)
            }
        },
    )

private fun JsonElement.limitAvailableMarkets(maxItems: Int): JsonElement =
    when (this) {
        is JsonArray -> JsonArray(map { it.limitAvailableMarkets(maxItems) })
        is JsonObject ->
            JsonObject(
                mapValues { (key, value) ->
                    if (key == "available_markets" && value is JsonArray) {
                        JsonArray(value.take(maxItems))
                    } else {
                        value.limitAvailableMarkets(maxItems)
                    }
                },
            )

        else -> this
    }

private fun ValidatedCapture.itemIdsFor(
    endpoint: String,
    nestedTrack: Boolean = false,
): Set<String> =
    pageEvents
        .asSequence()
        .filter { it.path.substringBefore('?') == endpoint }
        .flatMap { event ->
            spotifyFixtureJson
                .parseToJsonElement(event.body)
                .jsonObject
                .getValue("items")
                .jsonArray
                .asSequence()
        }.mapNotNull { item ->
            val objectWithId =
                if (nestedTrack) {
                    (item.jsonObject["item"] ?: item.jsonObject["track"])?.jsonObject
                } else {
                    item.jsonObject
                }
            objectWithId?.get("id")?.jsonPrimitive?.content
        }.toSet()

private fun JsonObject.idIsIn(ids: Set<String>): Boolean = get("id")?.jsonPrimitive?.content in ids

private fun ValidatedCapture.playlistTrackIds(): Map<String, Set<String>> {
    val playlistNames =
        pageEvents
            .filter { it.path.substringBefore('?') == "/v1/me/playlists" }
            .flatMap { it.pageItems() }
            .associate { item ->
                item.getValue("id").jsonPrimitive.content to
                    item.getValue("name").jsonPrimitive.content
            }
    return pageEvents
        .filter { it.path.substringBefore('?').isPlaylistTracksEndpoint() }
        .mapNotNull { event -> playlistNames[event.path.playlistId()]?.let { it to event } }
        .groupBy({ (playlistName, _) -> playlistName }, { (_, event) -> event })
        .mapValues { (_, events) ->
            events
                .flatMap { event ->
                    event.pageItems().mapNotNull { item ->
                        (item["item"] ?: item["track"])
                            ?.jsonObject
                            ?.get("id")
                            ?.jsonPrimitive
                            ?.content
                    }
                }.toSet()
        }
}

private fun JsonObject.belongsToCapturedPlaylistTrack(playlistTrackIds: Map<String, Set<String>>): Boolean {
    val playlistName = get("playlist_name")?.jsonPrimitive?.content ?: return false
    val trackId = get("id")?.jsonPrimitive?.content ?: return false
    return trackId in playlistTrackIds[playlistName].orEmpty()
}

private fun JsonObject.belongsToCapturedPlaylist(playlistIds: Set<String>): Boolean =
    get("playlist_id")?.jsonPrimitive?.content in playlistIds

private fun String.playlistId(): String = substringBefore('?').substringAfter("/v1/playlists/").substringBefore('/')

fun canonicalFixtureLine(fixture: SpotifyFixture): String =
    spotifyFixtureJson.encodeToString(canonicalize(fixtureElement(fixture)))

fun canonicalize(element: JsonElement): JsonElement =
    when (element) {
        is JsonArray -> JsonArray(element.map(::canonicalize))
        is JsonObject ->
            JsonObject(
                element.entries.sortedBy { it.key }.associate { (key, value) ->
                    key to
                        canonicalize(value)
                },
            )
        JsonNull -> JsonNull
        is JsonPrimitive -> element
    }

internal fun fixtureElement(fixture: SpotifyFixture): JsonElement =
    spotifyFixtureJson.encodeToJsonElement(SpotifyFixture.serializer(), fixture)

private fun canonicalizeRow(row: JsonObject): JsonObject =
    canonicalize(
        buildJsonObject {
            row.forEach { (key, value) ->
                put(key, if (key == "track_json") canonicalTrackJson(value) else value)
            }
        },
    ).jsonObject

private fun canonicalizeCacheMetadataRow(row: JsonObject): JsonObject =
    canonicalize(
        buildJsonObject {
            row.forEach { (key, value) ->
                put(key, if (key == "cache_revision") JsonPrimitive("cache-revision") else value)
            }
        },
    ).jsonObject

private fun canonicalTrackJson(value: JsonElement): JsonElement {
    if (value == JsonNull) return value
    val rawJson = value.jsonPrimitive.content
    val parsed = spotifyFixtureJson.parseToJsonElement(rawJson)
    return JsonPrimitive(canonicalize(parsed).toString())
}
