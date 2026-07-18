package com.philipwilcox.spotifybutler.support

import com.philipwilcox.spotifybutler.spotify.SpotifyCaptureEvent
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SpotifyCaptureLogTest {
    @Test
    fun `limits playlist tracks endpoints by ascending page count`() {
        val events =
            listOf(
                pageEvent(1, "/v1/playlists/most-pages/tracks?limit=50&offset=0"),
                pageEvent(2, "/v1/playlists/fewest-pages/tracks?limit=50&offset=0"),
                pageEvent(3, "/v1/me/playlists?limit=50&offset=0"),
                pageEvent(4, "/v1/playlists/middle-pages/tracks?limit=50&offset=0"),
            )
        val capture =
            ValidatedCapture(
                run = ParsedCaptureRun("run-test", events),
                pageEvents = events,
                ignoredNonPageEvents = emptyList(),
                pageCounts =
                    linkedMapOf(
                        "/v1/playlists/most-pages/tracks" to 5,
                        "/v1/playlists/fewest-pages/tracks" to 1,
                        "/v1/me/playlists" to 1,
                        "/v1/playlists/middle-pages/tracks" to 3,
                    ),
            )

        val limited = capture.limitPlaylistTracksCalls(2)

        assertEquals(
            listOf(
                "/v1/playlists/fewest-pages/tracks?limit=50&offset=0",
                "/v1/me/playlists?limit=50&offset=0",
                "/v1/playlists/middle-pages/tracks?limit=50&offset=0",
            ),
            limited.pageEvents.map { it.path },
        )
        assertEquals(
            setOf(
                "/v1/playlists/fewest-pages/tracks",
                "/v1/me/playlists",
                "/v1/playlists/middle-pages/tracks",
            ),
            limited.pageCounts.keys,
        )
    }

    @Test
    fun `allows a terminal page with a null next link`() {
        val event =
            SpotifyCaptureEvent(
                event = "spotify.response",
                runId = "run-test",
                sequence = 1,
                method = "GET",
                path = "/v1/me/playlists?limit=50&offset=0",
                status = 200,
                pageSequence = 1,
                body =
                    buildJsonObject {
                        put("items", buildJsonArray { })
                        put("next", null as String?)
                    }.toString(),
            )

        val validated = validateCapture(ParsedCaptureRun("run-test", listOf(event)))

        assertEquals(listOf(event), validated.pageEvents)
        assertEquals(mapOf("/v1/me/playlists" to 1), validated.pageCounts)
    }

    @Test
    fun `limits saved tracks to initial items and the terminal page`() {
        val events =
            listOf(
                pageEvent(1, "/v1/me/tracks?limit=2&offset=0", listOf("saved-1", "saved-2")),
                pageEvent(2, "/v1/me/tracks?limit=2&offset=2", listOf("saved-3", "saved-4")),
                pageEvent(3, "/v1/me/tracks?limit=2&offset=4", listOf("saved-5", "saved-6")),
                pageEvent(4, "/v1/me/tracks?limit=2&offset=6", listOf("saved-7")),
            )
        val capture =
            ValidatedCapture(
                ParsedCaptureRun("run-test", events),
                events,
                emptyList(),
                mapOf("/v1/me/tracks" to 4),
            )

        val limited = capture.limitSavedTracks(3)

        assertEquals(
            listOf(
                "/v1/me/tracks?limit=2&offset=0",
                "/v1/me/tracks?limit=2&offset=2",
                "/v1/me/tracks?limit=2&offset=6",
            ),
            limited.pageEvents.map { it.path },
        )
        assertEquals(
            listOf("saved-1", "saved-2", "saved-3"),
            limited.pageEvents.take(2).flatMap { it.itemIds() },
        )
        assertEquals(listOf("saved-7"), limited.pageEvents.last().itemIds())
        assertEquals(
            "https://api.spotify.com/v1/me/tracks?limit=2&offset=6",
            limited.pageEvents[1].nextPage(),
        )
        assertNull(limited.pageEvents.last().nextPage())
    }

    @Test
    fun `limits every top endpoint independently`() {
        val events =
            listOf(
                pageEvent(1, "/v1/me/top/tracks?limit=2&offset=0", listOf("track-1", "track-2")),
                pageEvent(2, "/v1/me/top/tracks?limit=2&offset=2", listOf("track-3")),
                pageEvent(3, "/v1/me/top/artists?limit=2&offset=0", listOf("artist-1", "artist-2")),
                pageEvent(4, "/v1/me/top/artists?limit=2&offset=2", listOf("artist-3")),
            )
        val capture =
            ValidatedCapture(
                ParsedCaptureRun("run-test", events),
                events,
                emptyList(),
                mapOf("/v1/me/top/tracks" to 2, "/v1/me/top/artists" to 2),
            )

        val limited = capture.limitTopItems(1)

        assertEquals(listOf("track-1", "track-3", "artist-1", "artist-3"), limited.pageEvents.flatMap { it.itemIds() })
        assertEquals("https://api.spotify.com/v1/me/top/tracks?limit=2&offset=2", limited.pageEvents[0].nextPage())
        assertEquals("https://api.spotify.com/v1/me/top/artists?limit=2&offset=2", limited.pageEvents[2].nextPage())
    }

    private fun pageEvent(
        sequence: Long,
        path: String,
        ids: List<String> = emptyList(),
    ) = SpotifyCaptureEvent(
        event = "spotify.response",
        runId = "run-test",
        sequence = sequence,
        method = "GET",
        path = path,
        status = 200,
        pageSequence = 1,
        body =
            buildJsonObject {
                put("items", buildJsonArray { ids.forEach { id -> add(buildJsonObject { put("id", id) }) } })
                put("next", null as String?)
            }.toString(),
    )

    private fun SpotifyCaptureEvent.itemIds(): List<String> =
        spotifyFixtureJson.parseToJsonElement(body).jsonObject.getValue("items").jsonArray.map {
            it.jsonObject
                .getValue("id")
                .jsonPrimitive.content
        }

    private fun SpotifyCaptureEvent.nextPage(): String? =
        (spotifyFixtureJson.parseToJsonElement(body).jsonObject["next"] as? JsonPrimitive)
            ?.takeIf(JsonPrimitive::isString)
            ?.content
}
