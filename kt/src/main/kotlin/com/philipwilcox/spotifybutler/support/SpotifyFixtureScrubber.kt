package com.philipwilcox.spotifybutler.support

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.util.UUID

internal class ReplacementPlan private constructor(
    val ids: Map<String, String>,
    val names: Map<String, String>,
    private val sensitive: Map<SensitiveField, String>,
    private val all: Map<String, String>,
    private val embeddedPattern: Regex?,
) {
    fun directReplacement(
        key: String?,
        parentKey: String?,
        original: String,
    ): String? =
        when {
            key?.isIdField() == true -> ids[original]
            key?.isNameField() == true -> names[original]
            else -> sensitive[SensitiveField(key.sensitiveFieldName(parentKey), original)]
        }

    fun replaceEmbeddedValues(value: String): String =
        embeddedPattern?.replace(value) { match -> all[match.value] ?: match.value } ?: value

    companion object {
        fun from(maps: ReplacementMaps): ReplacementPlan {
            val frozenIds = maps.ids.toMap()
            val frozenNames = maps.names.toMap()
            val frozenSensitive = maps.sensitive.toMap()
            val frozenAll = frozenIds + frozenNames
            val pattern =
                frozenAll.keys
                    .sortedByDescending(String::length)
                    .joinToString("|") { Regex.escape(it) }
                    .takeIf(String::isNotEmpty)
                    ?.let(::Regex)
            return ReplacementPlan(frozenIds, frozenNames, frozenSensitive, frozenAll, pattern)
        }
    }
}

internal data class SensitiveField(
    val key: String?,
    val original: String,
)

internal class ReplacementMaps {
    val ids = linkedMapOf<String, String>()
    val names = linkedMapOf<String, String>()
    val sensitive = linkedMapOf<SensitiveField, String>()
}

internal class PreparedFixture(
    val runId: String,
    private val root: JsonObject,
    val replacementPlan: ReplacementPlan,
    val responses: List<JsonElement>,
    val expectedTables: Map<String, List<JsonObject>>,
) {
    private val scrubbedResponses = arrayOfNulls<JsonElement>(responses.size)
    private val scrubbedTables = expectedTables.mapValues { (_, rows) -> arrayOfNulls<JsonElement>(rows.size) }

    fun placeResponse(
        index: Int,
        response: JsonElement,
    ) {
        scrubbedResponses[index] = response
    }

    fun placeTableRows(
        table: String,
        startIndex: Int,
        rows: List<JsonElement>,
    ) {
        val slots = requireNotNull(scrubbedTables[table]) { "Unknown expected table $table" }
        rows.forEachIndexed { offset, row -> slots[startIndex + offset] = row }
    }

    fun assemble(): SpotifyFixture {
        val responseArray = JsonArray(scrubbedResponses.map { requireNotNull(it) })
        val tableObject =
            JsonObject(
                expectedTables.keys.associateWith { table ->
                    JsonArray(
                        requireNotNull(scrubbedTables[table])
                            .map { requireNotNull(it) as JsonObject }
                            .sortedWith(compareBy { row -> stableRowSortKey(table, row) }),
                    )
                },
            )
        val scrubbedRoot =
            JsonObject(
                root.mapValues { (key, value) ->
                    when (key) {
                        "responses" -> responseArray
                        "expectedTables" -> tableObject
                        "name" -> value
                        else -> scrubElement(value, replacementPlan, key)
                    }
                },
            )
        return spotifyFixtureJson.decodeFromJsonElement(SpotifyFixture.serializer(), scrubbedRoot)
    }
}

private fun stableRowSortKey(
    table: String,
    row: JsonObject,
): String {
    val columns =
        when (table) {
            "saved_tracks" ->
                listOf(
                    "name",
                    "id",
                    "primary_artist_id",
                    "release_date",
                    "release_year",
                    "href",
                    "uri",
                    "added_at",
                    "track_json",
                )
            "top_tracks" -> listOf("name", "id", "href", "uri", "track_json")
            "top_artists" -> listOf("name", "id", "href", "uri")
            "playlists" -> listOf("name", "id", "href", "uri", "tracks_href", "snapshot_id")
            "playlist_tracks" ->
                listOf(
                    "playlist_name",
                    "name",
                    "id",
                    "primary_artist_id",
                    "release_date",
                    "release_year",
                    "href",
                    "uri",
                    "added_at",
                    "track_json",
                )
            "sync_status" -> listOf("sync_timestamp_millis")
            else -> error("Unknown expected table: $table")
        }
    return columns.joinToString("\u0000") { column -> row[column]?.toString() ?: "" }
}

internal fun prepareFixture(
    runId: String,
    fixture: SpotifyFixture,
): PreparedFixture {
    val root = fixtureElement(fixture).jsonObject
    val maps = ReplacementMaps()
    collectReplacements(root, maps, fixtureRoot = true)
    val responses = requireNotNull(root["responses"]?.jsonArray) { "Fixture is missing responses" }
    val expectedTablesElement =
        requireNotNull(root["expectedTables"]?.jsonObject) { "Fixture is missing expectedTables" }
    val expectedTables =
        expectedTablesElement.entries.associate { (table, value) ->
            table to value.jsonArray.map { it.jsonObject }
        }
    return PreparedFixture(runId, root, ReplacementPlan.from(maps), responses, expectedTables)
}

internal fun scrubFixture(fixture: SpotifyFixture): SpotifyFixture {
    val prepared = prepareFixture(fixture.name, fixture)
    prepared.responses.forEachIndexed { index, response ->
        prepared.placeResponse(index, scrubElement(response, prepared.replacementPlan))
    }
    prepared.expectedTables.forEach { (table, rows) ->
        prepared.placeTableRows(table, 0, rows.map { row -> scrubElement(row, prepared.replacementPlan) })
    }
    return prepared.assemble()
}

internal fun scrubElement(
    element: JsonElement,
    plan: ReplacementPlan,
    key: String? = null,
    parentKey: String? = null,
): JsonElement =
    when (element) {
        is JsonObject ->
            JsonObject(element.mapValues { (childKey, child) -> scrubElement(child, plan, childKey, key) })
        is JsonArray -> JsonArray(element.map { child -> scrubElement(child, plan, key, parentKey) })
        is JsonPrimitive -> scrubPrimitive(element, plan, key, parentKey)
        JsonNull -> JsonNull
    }

private fun collectReplacements(
    element: JsonElement,
    maps: ReplacementMaps,
    key: String? = null,
    parentKey: String? = null,
    fixtureRoot: Boolean = false,
) {
    when (element) {
        is JsonObject ->
            element.forEach { (childKey, child) ->
                if (!(fixtureRoot && childKey == "name")) {
                    collectFieldReplacement(childKey, child, parentKey = key, maps = maps)
                }
                collectReplacements(child, maps, childKey, key)
            }

        is JsonPrimitive ->
            when {
                key == "track_json" && element.isString ->
                    collectReplacements(spotifyFixtureJson.parseToJsonElement(element.content), maps)
                key?.sensitiveFieldName(parentKey) != null ->
                    collectFieldReplacement(key, element, parentKey, maps)
            }

        is JsonArray -> element.forEach { child -> collectReplacements(child, maps, key, parentKey) }
        JsonNull -> Unit
    }
}

private fun collectFieldReplacement(
    key: String,
    value: JsonElement,
    parentKey: String?,
    maps: ReplacementMaps,
) {
    val primitive = value as? JsonPrimitive ?: return
    val original = primitive.content
    when {
        key.isIdField() -> maps.ids.putIfAbsent(original, UUID.randomUUID().toString())
        key.isNameField() -> maps.names.putIfAbsent(original, UUID.randomUUID().toString())
        key.isReferenceField() -> collectSpotifyReferenceIds(original, maps)
        key.sensitiveFieldName(parentKey) != null && original.isNotBlank() ->
            maps.sensitive.putIfAbsent(
                SensitiveField(key.sensitiveFieldName(parentKey), original),
                syntheticReplacement(key),
            )
    }
}

private fun scrubPrimitive(
    element: JsonPrimitive,
    plan: ReplacementPlan,
    key: String?,
    parentKey: String?,
): JsonElement {
    if (key == "sync_timestamp_millis" && !element.isString) {
        return JsonPrimitive(SCRUBBED_SYNC_TIMESTAMP_MILLIS)
    }
    if (!element.isString) return element
    val original = element.content
    if (key == "track_json") {
        val scrubbedTrack = scrubElement(spotifyFixtureJson.parseToJsonElement(original), plan)
        return JsonPrimitive(scrubbedTrack.toString())
    }
    return JsonPrimitive(plan.directReplacement(key, parentKey, original) ?: plan.replaceEmbeddedValues(original))
}

private fun String.isIdField(): Boolean = this == "id" || endsWith("_id")

private fun String.isNameField(): Boolean =
    this == "name" || this == "display_name" || this == "playlist_name" || endsWith("_name")

private fun String.isReferenceField(): Boolean =
    this == "path" ||
        this == "href" ||
        this == "next" ||
        this == "previous" ||
        this == "spotify" ||
        this == "tracks_href" ||
        this == "uri"

private fun String?.sensitiveFieldName(parentKey: String?): String? =
    when {
        this == "url" && parentKey == "images" -> "image_url"
        this == "preview_url" || this == "audio_preview_url" -> this
        this == "isrc" || this == "ean" || this == "upc" -> this
        this == "description" || this == "html_description" -> this
        this == "genres" -> this
        this == "added_at" -> this
        else -> null
    }

private fun syntheticReplacement(key: String): String =
    when (key) {
        "image_url" -> "https://example.invalid/spotify-fixture/${UUID.randomUUID()}"
        "preview_url", "audio_preview_url" -> "https://example.invalid/spotify-fixture/${UUID.randomUUID()}.mp3"
        "added_at" -> SCRUBBED_ADDED_AT
        else -> UUID.randomUUID().toString()
    }

private fun collectSpotifyReferenceIds(
    value: String,
    maps: ReplacementMaps,
) {
    SPOTIFY_REFERENCE_ID_PATTERN.findAll(value).forEach { match ->
        maps.ids.putIfAbsent(match.groupValues[1], UUID.randomUUID().toString())
    }
}

private val SPOTIFY_REFERENCE_ID_PATTERN =
    Regex(
        "(?:https://(?:api\\.spotify\\.com/v1|open\\.spotify\\.com)/(?:users?|tracks?|albums?|artists?|playlists?)/|" +
            "spotify:(?:user|track|album|artist|playlist):)([^/?#:]*)",
    )

private const val SCRUBBED_ADDED_AT = "2000-01-01T00:00:00Z"
private const val SCRUBBED_SYNC_TIMESTAMP_MILLIS = 1_700_000_000_000L
