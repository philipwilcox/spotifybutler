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

@Serializable
data class SpotifyFixtureResponse(
    val method: String,
    val path: String,
    val status: Int,
    val body: JsonElement,
)

@Serializable
data class ExpectedTables(
    @SerialName("saved_tracks") val savedTracks: List<JsonObject>,
    @SerialName("top_tracks") val topTracks: List<JsonObject>,
    @SerialName("top_artists") val topArtists: List<JsonObject>,
    val playlists: List<JsonObject>,
    @SerialName("playlist_tracks") val playlistTracks: List<JsonObject>,
    @SerialName("sync_status") val syncStatus: List<JsonObject>,
)

val spotifyFixtureJson =
    Json {
        ignoreUnknownKeys = false
        explicitNulls = true
        prettyPrint = false
    }

fun SpotifyTableSnapshot.toExpectedTables(): ExpectedTables =
    ExpectedTables(
        savedTracks = savedTracks.map(::canonicalizeRow),
        topTracks = topTracks.map(::canonicalizeRow),
        topArtists = topArtists.map(::canonicalizeRow),
        playlists = playlists.map(::canonicalizeRow),
        playlistTracks = playlistTracks.map(::canonicalizeRow),
        syncStatus = syncStatus.map(::canonicalizeRow),
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
            val objectWithId = if (nestedTrack) item.jsonObject["track"]?.jsonObject else item.jsonObject
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
                        item["track"]
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

private fun canonicalTrackJson(value: JsonElement): JsonElement {
    if (value == JsonNull) return value
    val rawJson = value.jsonPrimitive.content
    val parsed = spotifyFixtureJson.parseToJsonElement(rawJson)
    return JsonPrimitive(canonicalize(parsed).toString())
}
