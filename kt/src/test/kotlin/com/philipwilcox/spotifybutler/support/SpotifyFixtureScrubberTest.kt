package com.philipwilcox.spotifybutler.support

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpotifyFixtureScrubberTest {
    @Test
    fun `limits available markets in responses and expected track json`() {
        val markets = buildJsonArray { listOf("AR", "AU", "AT").forEach { add(JsonPrimitive(it)) } }
        val track = buildJsonObject { put("available_markets", markets) }
        val fixture =
            SpotifyFixture(
                schemaVersion = 1,
                name = "available-markets",
                responses = listOf(SpotifyFixtureResponse("GET", "/v1/me/top/tracks", 200, track)),
                expectedTables =
                    ExpectedTables(
                        savedTracks = emptyList(),
                        topTracks = listOf(buildJsonObject { put("track_json", track.toString()) }),
                        topArtists = emptyList(),
                        playlists = emptyList(),
                        playlistTracks = emptyList(),
                        syncStatus = emptyList(),
                    ),
            )

        val limited = fixture.limitAvailableMarkets(2)

        assertEquals(
            listOf("AR", "AU"),
            limited.responses
                .single()
                .body
                .markets(),
        )
        assertEquals(
            listOf("AR", "AU"),
            spotifyFixtureJson
                .parseToJsonElement(
                    limited.expectedTables.topTracks
                        .single()
                        .getValue("track_json")
                        .jsonPrimitive.content,
                ).markets(),
        )
    }

    @Test
    fun `replaces identifiers and names consistently across responses and track json`() {
        val fixtureResource =
            requireNotNull(javaClass.classLoader.getResource("spotify-fixtures/synthetic-cache.jsonl"))
        val fixture =
            spotifyFixtureJson.decodeFromString<SpotifyFixture>(Files.readString(Path.of(fixtureResource.toURI())))
        val scrubbed = scrubFixture(fixture)
        val scrubbedText = canonicalFixtureLine(scrubbed)

        listOf(
            "artist-one",
            "artist-two",
            "track-one",
            "track-two",
            "playlist-one",
            "snapshot-one",
            "Artist One",
            "Playlist One",
            "Track One",
            "Track Two",
        ).forEach { original -> assertFalse(original in scrubbedText, "Original value remained: $original") }

        val responseTrack =
            scrubbed.responses
                .first { it.path == "/v1/me/tracks?limit=50&offset=0" }
                .body
                .jsonObject["items"]!!
                .jsonArray
                .first()
                .jsonObject["track"]!!
                .jsonObject
        val storedTrack =
            spotifyFixtureJson
                .parseToJsonElement(
                    scrubbed.expectedTables.savedTracks
                        .first { it["name"] == responseTrack["name"] }["track_json"]!!
                        .jsonPrimitive.content,
                ).jsonObject
        assertEquals(responseTrack["id"]!!.jsonPrimitive.content, storedTrack["id"]!!.jsonPrimitive.content)
        assertTrue(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").containsMatchIn(scrubbedText))
    }

    @Test
    fun `scrubs public catalog identifiers and user metadata from nested Spotify objects`() {
        val originalValues =
            listOf(
                "https://i.scdn.co/image/original-art-hash",
                "https://p.scdn.co/mp3-preview/original-preview-hash",
                "USRC17607839",
                "Playlist description names the original music",
                "shoegaze",
                "2030-01-02T03:04:05Z",
                "https://api.spotify.com/v1/tracks/unlisted-track-id",
                "https://open.spotify.com/track/url-only-reference",
            )
        val fixture =
            SpotifyFixture(
                schemaVersion = 1,
                name = "privacy",
                responses =
                    listOf(
                        SpotifyFixtureResponse(
                            method = "GET",
                            path = "/v1/me/tracks?limit=50&offset=0",
                            status = 200,
                            body =
                                spotifyFixtureJson.parseToJsonElement(
                                    """
                                    {
                                      "items": [{
                                        "added_at": "2030-01-02T03:04:05Z",
                                        "track": {
                                          "album": {
                                            "images": [{"url": "https://i.scdn.co/image/original-art-hash"}],
                                            "name": "Original Album"
                                          },
                                          "artists": [{"genres": ["shoegaze"], "name": "Original Artist"}],
                                          "external_ids": {"isrc": "USRC17607839"},
                                          "external_urls": {"spotify": "https://open.spotify.com/track/url-only-reference"},
                                          "href": "https://api.spotify.com/v1/tracks/unlisted-track-id",
                                          "id": "unlisted-track-id",
                                          "name": "Original Track",
                                          "preview_url": "https://p.scdn.co/mp3-preview/original-preview-hash",
                                          "uri": "spotify:track:unlisted-track-id"
                                        }
                                      }],
                                      "next": null
                                    }
                                    """.trimIndent(),
                                ),
                        ),
                        SpotifyFixtureResponse(
                            method = "GET",
                            path = "/v1/playlists/url-only-id/tracks?limit=50&offset=0",
                            status = 200,
                            body =
                                spotifyFixtureJson.parseToJsonElement(
                                    """
                                    {
                                      "items": [{
                                        "description": "Playlist description names the original music",
                                        "external_urls": {"spotify": "https://open.spotify.com/playlist/url-only-id"},
                                        "href": "https://api.spotify.com/v1/playlists/url-only-id",
                                        "id": "url-only-id",
                                        "images": [{"url": "https://i.scdn.co/image/original-art-hash"}],
                                        "name": "Original Playlist",
                                        "owner": {"display_name": "Original Owner", "id": "owner-id"},
                                        "tracks": {"href": "https://api.spotify.com/v1/playlists/url-only-id/tracks"},
                                        "uri": "spotify:playlist:url-only-id"
                                      }],
                                      "next": null
                                    }
                                    """.trimIndent(),
                                ),
                        ),
                    ),
                expectedTables =
                    ExpectedTables(
                        savedTracks = emptyList(),
                        topTracks = emptyList(),
                        topArtists = emptyList(),
                        playlists = emptyList(),
                        playlistTracks = emptyList(),
                        syncStatus = listOf(buildJsonObject { put("sync_timestamp_millis", 123456789L) }),
                    ),
            )

        val scrubbed = scrubFixture(fixture)
        val scrubbedText = canonicalFixtureLine(scrubbed)

        originalValues.forEach { original ->
            assertFalse(original in scrubbedText, "Original sensitive value remained: $original")
        }
        assertTrue(scrubbed.responses[1].path.startsWith("/v1/playlists/"))
        assertTrue(scrubbed.responses[1].path.endsWith("/tracks?limit=50&offset=0"))
        assertTrue("example.invalid/spotify-fixture" in scrubbedText)
        assertTrue("2000-01-01T00:00:00Z" in scrubbedText)
        assertEquals(
            "1700000000000",
            scrubbed.expectedTables.syncStatus
                .single()
                .getValue("sync_timestamp_millis")
                .jsonPrimitive.content,
        )
    }

    private fun kotlinx.serialization.json.JsonElement.markets(): List<String> =
        jsonObject.getValue("available_markets").jsonArray.map { it.jsonPrimitive.content }
}
