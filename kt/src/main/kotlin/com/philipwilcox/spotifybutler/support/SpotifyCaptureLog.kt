package com.philipwilcox.spotifybutler.support

import com.philipwilcox.spotifybutler.spotify.SPOTIFY_CAPTURE_EVENT_MARKER
import com.philipwilcox.spotifybutler.spotify.SpotifyCaptureEvent
import com.philipwilcox.spotifybutler.spotify.capturePath
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

data class ParsedCaptureRun(
    val runId: String,
    val events: List<SpotifyCaptureEvent>,
)

data class ValidatedCapture(
    val run: ParsedCaptureRun,
    val pageEvents: List<SpotifyCaptureEvent>,
    val ignoredNonPageEvents: List<SpotifyCaptureEvent>,
    val pageCounts: Map<String, Int>,
)

fun ValidatedCapture.limitPlaylistTracksCalls(maxCalls: Int?): ValidatedCapture {
    if (maxCalls == null) return this
    require(maxCalls >= 0) { "Maximum playlist-tracks calls must not be negative" }

    val playlistTrackEndpoints =
        pageCounts.keys
            .filter(String::isPlaylistTracksEndpoint)
            .sortedWith(compareBy<String> { pageCounts.getValue(it) }.thenBy { it })
    val retainedEndpoints = playlistTrackEndpoints.take(maxCalls).toSet()
    val retainedPageEvents =
        pageEvents.filter { event ->
            val endpoint = event.path.substringBefore('?')
            !endpoint.isPlaylistTracksEndpoint() || endpoint in retainedEndpoints
        }
    val droppedEvents = pageEvents.size - retainedPageEvents.size
    if (droppedEvents > 0) {
        progress(
            "run=${run.runId} limited playlist-tracks endpoints to ${retainedEndpoints.size}/" +
                "${playlistTrackEndpoints.size}; droppedPages=$droppedEvents",
        )
    }
    return copy(
        pageEvents = retainedPageEvents,
        pageCounts =
            pageCounts.filterKeys { endpoint ->
                !endpoint.isPlaylistTracksEndpoint() || endpoint in retainedEndpoints
            },
    )
}

fun ValidatedCapture.limitSavedTracks(maxItems: Int?): ValidatedCapture =
    limitItems(maxItems, String::isSavedTracksEndpoint, "saved tracks")

fun ValidatedCapture.limitTopItems(maxItems: Int?): ValidatedCapture =
    limitItems(maxItems, String::isTopEndpoint, "top items")

fun ValidatedCapture.limitPlaylistTrackItems(maxItems: Int): ValidatedCapture {
    require(maxItems >= 0) { "Maximum playlist tracks must not be negative" }
    var remainingItems = maxItems
    val playlistPageEvents = pageEvents.filter { it.path.substringBefore('?').isPlaylistTracksEndpoint() }
    val retainedByEndpoint = linkedMapOf<String, MutableList<SpotifyCaptureEvent>>()
    playlistPageEvents.forEach { event ->
        val retainedItems = event.pageItems().take(remainingItems)
        if (retainedItems.isNotEmpty()) {
            retainedByEndpoint.getOrPut(event.path.substringBefore('?')) { mutableListOf() } +=
                event.withPageItems(retainedItems)
            remainingItems -= retainedItems.size
        }
    }
    playlistPageEvents.groupBy { it.path.substringBefore('?') }.forEach { (endpoint, events) ->
        retainedByEndpoint.getOrPut(endpoint) { mutableListOf(events.first().withPageItems(emptyList())) }
    }
    val linkedByPath = retainedByEndpoint.values.flatMap { it.withLinkedPages() }.associateBy(SpotifyCaptureEvent::path)
    val retainedPageEvents =
        pageEvents
            .filter { event ->
                val endpoint = event.path.substringBefore('?')
                !endpoint.isPlaylistTracksEndpoint() || event.path in linkedByPath
            }.map { event -> linkedByPath[event.path] ?: event }
    val droppedEvents = pageEvents.size - retainedPageEvents.size
    if (droppedEvents > 0) {
        progress("run=${run.runId} limited playlist tracks to $maxItems items; droppedPages=$droppedEvents")
    }
    return copy(
        pageEvents = retainedPageEvents,
        pageCounts = retainedPageEvents.groupingBy { it.path.substringBefore('?') }.eachCount(),
    )
}

private fun ValidatedCapture.limitItems(
    maxItems: Int?,
    matchesEndpoint: (String) -> Boolean,
    description: String,
): ValidatedCapture {
    if (maxItems == null) return this
    require(maxItems >= 0) { "Maximum $description must not be negative" }

    val limitedEventsByEndpoint =
        pageCounts.keys
            .filter(matchesEndpoint)
            .associateWith { endpoint ->
                pageEvents
                    .filter { it.path.substringBefore('?') == endpoint }
                    .limitPageItems(maxItems)
            }
    if (limitedEventsByEndpoint.isEmpty()) return this

    val retainedPageEvents =
        pageEvents
            .filter { event ->
                val endpoint = event.path.substringBefore('?')
                limitedEventsByEndpoint[endpoint]?.any { it.path == event.path } ?: true
            }.map { event ->
                val endpoint = event.path.substringBefore('?')
                limitedEventsByEndpoint[endpoint]?.firstOrNull { it.path == event.path } ?: event
            }
    val droppedEvents = pageEvents.size - retainedPageEvents.size
    if (droppedEvents > 0) {
        progress("run=${run.runId} limited $description to $maxItems initial items; droppedPages=$droppedEvents")
    }
    return copy(
        pageEvents = retainedPageEvents,
        pageCounts = retainedPageEvents.groupingBy { it.path.substringBefore('?') }.eachCount(),
    )
}

private fun List<SpotifyCaptureEvent>.limitPageItems(maxItems: Int): List<SpotifyCaptureEvent> {
    if (isEmpty()) return this
    var remainingItems = maxItems
    val prefix =
        buildList {
            this@limitPageItems.forEach { event ->
                if (remainingItems == 0) return@forEach
                val items = event.pageItems()
                val retainedItems = items.take(remainingItems)
                if (retainedItems.isNotEmpty()) {
                    add(event.withPageItems(retainedItems))
                    remainingItems -= retainedItems.size
                }
            }
        }
    return (prefix + last()).distinctBy(SpotifyCaptureEvent::path).withLinkedPages()
}

private fun List<SpotifyCaptureEvent>.withLinkedPages(): List<SpotifyCaptureEvent> =
    mapIndexed { index, event ->
        event.withNextPage(getOrNull(index + 1))
    }

internal fun SpotifyCaptureEvent.pageItems(): List<JsonObject> =
    spotifyFixtureJson
        .parseToJsonElement(body)
        .jsonObject
        .getValue("items")
        .jsonArray
        .map(JsonElement::jsonObject)

private fun SpotifyCaptureEvent.withPageItems(items: List<JsonObject>): SpotifyCaptureEvent =
    copy(
        body =
            buildJsonObject {
                spotifyFixtureJson.parseToJsonElement(body).jsonObject.forEach { (key, value) ->
                    put(key, if (key == "items") kotlinx.serialization.json.JsonArray(items) else value)
                }
            }.toString(),
    )

private fun SpotifyCaptureEvent.withNextPage(nextPage: SpotifyCaptureEvent?): SpotifyCaptureEvent =
    copy(
        body =
            buildJsonObject {
                spotifyFixtureJson.parseToJsonElement(body).jsonObject.forEach { (key, value) ->
                    val next = nextPage?.let { JsonPrimitive("https://api.spotify.com${it.path}") } ?: JsonNull
                    put(key, if (key == "next") next else value)
                }
            }.toString(),
    )

fun parseCaptureLog(
    logPath: Path,
    requestedRunId: String? = null,
): ParsedCaptureRun {
    val runs = parseCaptureRuns(logPath)
    val runId =
        requestedRunId ?: runs.singleOrNull()?.runId ?: error(
            "Capture log contains multiple runs (${runs.joinToString { it.runId }}); pass --run-id explicitly.",
        )
    return runs.singleOrNull { it.runId == runId }
        ?: error("Capture run $runId was not found in $logPath")
}

fun parseCaptureRuns(logPath: Path): List<ParsedCaptureRun> {
    require(Files.isRegularFile(logPath)) { "Capture log not found at $logPath" }
    val events =
        buildList {
            Files.readAllLines(logPath).forEachIndexed { index, line ->
                val markerIndex = line.indexOf(SPOTIFY_CAPTURE_EVENT_MARKER)
                if (markerIndex < 0) return@forEachIndexed
                val encodedEvent = line.substring(markerIndex + SPOTIFY_CAPTURE_EVENT_MARKER.length).trim()
                require(encodedEvent.isNotEmpty()) {
                    "Capture event on $logPath:$index has no JSON payload"
                }
                add(
                    runCatching { spotifyFixtureJson.decodeFromString(SpotifyCaptureEvent.serializer(), encodedEvent) }
                        .getOrElse { exception ->
                            error("Malformed Spotify capture event on $logPath:${index + 1}: ${exception.message}")
                        },
                )
            }
        }
    require(events.isNotEmpty()) { "No $SPOTIFY_CAPTURE_EVENT_MARKER events found in $logPath" }
    return events.groupBy(SpotifyCaptureEvent::runId).map { (runId, runEvents) ->
        require(runEvents.all { it.event == "spotify.response" }) {
            "Capture run $runId contains an unsupported structured event"
        }
        ParsedCaptureRun(runId, runEvents)
    }
}

fun validateCapture(run: ParsedCaptureRun): ValidatedCapture {
    val parsedBodies =
        run.events.associateWith { event ->
            require(event.method == "GET") { "Unsupported captured method ${event.method} at ${event.path}" }
            require(event.status in 200 until 300) {
                "Captured response for ${event.path} was HTTP ${event.status}; " +
                    "only successful responses can become fixtures"
            }
            runCatching { spotifyFixtureJson.parseToJsonElement(event.body).jsonObject }
                .getOrElse { exception ->
                    error("Captured response for ${event.path} is not a JSON object: ${exception.message}")
                }
        }
    val pageEvents = run.events.filter { event -> parsedBodies.getValue(event).isPageResponse() }
    val ignoredEvents = run.events - pageEvents
    ignoredEvents.forEach { event ->
        require(event.path == "/v1/me" && event.pageSequence == 0) {
            "Captured successful response ${event.path} is not a Spotify page and cannot be paired with a cache fixture"
        }
    }
    ignoredEvents.forEachIndexed { index, _ ->
        progress("validated non-page response ${index + 1}/${ignoredEvents.size} for run ${run.runId}")
    }
    require(pageEvents.isNotEmpty()) { "Capture run ${run.runId} contains no cache page responses" }

    val pathSet = pageEvents.mapTo(mutableSetOf(), SpotifyCaptureEvent::path)
    val pageByPath = pageEvents.associateBy(SpotifyCaptureEvent::path)
    pageEvents.forEach { event ->
        require(event.pageSequence > 0) { "Cache page ${event.path} has invalid page sequence ${event.pageSequence}" }
        val body = parsedBodies.getValue(event)
        val nextElement = body["next"]
        val next =
            if (nextElement is JsonPrimitive && nextElement !is JsonNull) {
                nextElement.content.takeIf(String::isNotBlank)
            } else {
                null
            }
        if (next != null) {
            val nextPath =
                runCatching { capturePath(URI(next)) }.getOrElse { exception ->
                    error("Pagination link in ${event.path} is not a valid URI: ${exception.message}")
                }
            require(nextPath in pathSet) {
                "Missing captured pagination page $nextPath referenced by ${event.path}"
            }
            require(pageByPath.getValue(nextPath).pageSequence == event.pageSequence + 1) {
                "Pagination from ${event.path} does not lead to page ${event.pageSequence + 1} at $nextPath"
            }
        }
    }

    val pageCounts =
        pageEvents
            .groupBy { it.path.substringBefore('?') }
            .mapValues { (_, pages) ->
                val sequences = pages.map(SpotifyCaptureEvent::pageSequence).sorted()
                require(sequences == (1..sequences.size).toList()) {
                    "Pagination sequence for ${pages.first().path.substringBefore('?')} was $sequences"
                }
                sequences.size
            }
    pageCounts.forEach { (endpoint, pages) ->
        progress("validated capture endpoint $endpoint pages=$pages for run ${run.runId}")
    }
    return ValidatedCapture(run, pageEvents, ignoredEvents, pageCounts)
}

private fun JsonObject.isPageResponse(): Boolean =
    get("items")?.let { itemElement ->
        runCatching { itemElement.jsonArray }.isSuccess
    } == true

internal fun String.isPlaylistTracksEndpoint(): Boolean {
    val segments = substringBefore('?').split('/')
    return segments.size == SPOTIFY_ENDPOINT_SEGMENT_COUNT &&
        segments[API_VERSION_INDEX] == "v1" &&
        segments[RESOURCE_INDEX] == "playlists" &&
        segments[RESOURCE_ID_INDEX].isNotBlank() &&
        segments[ACTION_INDEX] in setOf("tracks", "items")
}

private fun String.isSavedTracksEndpoint(): Boolean = substringBefore('?') == "/v1/me/tracks"

private fun String.isTopEndpoint(): Boolean =
    substringBefore('?').split('/').let { segments ->
        segments.size == SPOTIFY_ENDPOINT_SEGMENT_COUNT &&
            segments[API_VERSION_INDEX] == "v1" &&
            segments[RESOURCE_INDEX] == "me" &&
            segments[RESOURCE_ID_INDEX] == "top"
    }

private const val SPOTIFY_ENDPOINT_SEGMENT_COUNT = 5
private const val API_VERSION_INDEX = 1
private const val RESOURCE_INDEX = 2
private const val RESOURCE_ID_INDEX = 3
private const val ACTION_INDEX = 4
