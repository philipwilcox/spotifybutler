package com.philipwilcox.spotifybutler.http

import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OpenApiContractTest {
    @Test
    fun `openapi exposes exactly the supported browser routes and methods`() {
        val paths = document()["paths"].asMap()
        val expected =
            mapOf(
                "/health" to setOf("get"),
                "/api/v1/session" to setOf("get", "delete"),
                "/api/v1/session/refresh" to setOf("post"),
                "/api/v1/playlists" to setOf("get", "post"),
                "/api/v1/playlists/{definitionId}" to setOf("get", "put"),
                "/api/v1/playlists/{definitionId}/current" to setOf("get"),
                "/api/v1/playlists/{definitionId}/syncs" to setOf("post"),
                "/api/v1/library" to setOf("get"),
                "/api/v1/library/refresh" to setOf("post"),
                "/api/v1/run" to setOf("post"),
                "/api/v1/songs" to setOf("get"),
                "/api/v1/songs/{trackId}" to setOf("get"),
            )

        assertEquals(expected.keys, paths.keys.map { it.toString() }.toSet())
        expected.forEach { (path, methods) ->
            val pathDocument = paths[path].asMap()
            assertEquals(
                methods,
                pathDocument.keys
                    .filter { it.toString() in HTTP_METHODS }
                    .map { it.toString() }
                    .toSet(),
            )
        }
    }

    @Test
    fun `every operation documents typed success and reusable sanitized errors`() {
        val paths = document()["paths"].asMap()
        paths.values.forEach { pathValue ->
            pathValue.asMap().filterKeys { it.toString() in HTTP_METHODS }.values.forEach { operationValue ->
                val operation = operationValue.asMap()
                val responses = operation["responses"].asMap()
                val success = responses.entries.first { it.key.toString().startsWith("2") }
                val successResponse = success.value.asMap()
                if (success.key.toString() != "204") {
                    val schema = successResponse["content"].asMap()["application/json"].asMap()["schema"].asMap()
                    assertTrue(schema["\$ref"].toString().startsWith("#/components/schemas/"))
                }
                assertEquals("#/components/responses/ErrorResponse", responses["default"].asMap()["\$ref"])
            }
        }
    }

    @Test
    fun `wire schemas document required nullable fields and request defaults`() {
        val schemas = document()["components"].asMap()["schemas"].asMap()
        assertRequired(schemas, "PlaylistReference", "id", "name", "state", "spotifyPlaylistId", "trackIds")
        assertRequired(schemas, "CurrentEnvelope", "current")
        assertRequired(schemas, "Library", "status", "ownerId", "completedAt", "counts")
        assertRequired(schemas, "Album", "id", "name", "href", "uri", "releaseDate")
        assertRequired(schemas, "Artist", "id", "name", "href", "uri")
        assertRequired(
            schemas,
            "Song",
            "id",
            "name",
            "href",
            "uri",
            "album",
            "artists",
            "durationMs",
            "explicit",
            "available",
        )

        assertNullable(schemas, "PlaylistReference", "spotifyPlaylistId", "string")
        assertNullable(schemas, "Library", "ownerId", "string")
        assertNullable(schemas, "Library", "completedAt", "string")
        assertNullable(schemas, "Album", "id", "string")
        assertNullable(schemas, "Song", "durationMs", "integer")
        assertNullable(schemas, "Song", "explicit", "boolean")

        val createRequest = schemas["CreatePlaylistRequest"].asMap()
        assertEquals(listOf("name"), createRequest["required"])
        assertEquals(emptyList<Any?>(), createRequest["properties"].asMap()["trackIds"].asMap()["default"])
    }

    @Test
    fun `openapi records bounded enrichment synchronization and csrf mutations`() {
        val document = document()
        val paths = document["paths"].asMap()
        val songParameters = paths["/api/v1/songs"].asMap()["get"].asMap()["parameters"].asList()
        val ids = songParameters.single { it.asMap()["name"] == "ids" }.asMap()
        assertEquals(50, ids["x-maxIds"])

        val schemas = document["components"].asMap()["schemas"].asMap()
        assertEquals(5000, schemas["SyncRequest"].asMap()["properties"].asMap()["trackIds"].asMap()["maxItems"])

        val stateChanging =
            paths.values
                .flatMap { pathValue ->
                    pathValue
                        .asMap()
                        .filterKeys {
                            it.toString() in HTTP_METHODS && it.toString() in STATE_CHANGING_METHODS
                        }.map { operation ->
                            operation.value.asMap()
                        }
                }
        stateChanging.forEach { operation ->
            val csrf = operation["parameters"].asList().map { it.asMap()["\$ref"] }
            assertTrue("#/components/parameters/CsrfToken" in csrf)
        }

        assertEquals(true, paths["/api/v1/run"].asMap()["post"].asMap()["deprecated"])
    }

    private fun document(): Map<*, *> {
        val source = requireNotNull(javaClass.getResource("/openapi.yaml")).readText()
        return Load(LoadSettings.builder().build()).loadFromString(source).asMap()
    }

    private fun assertRequired(
        schemas: Map<*, *>,
        schemaName: String,
        vararg properties: String,
    ) {
        val required =
            schemas[schemaName]
                .asMap()["required"]
                .asList()
                .map { it.toString() }
                .toSet()
        assertEquals(properties.toSet(), required)
    }

    private fun assertNullable(
        schemas: Map<*, *>,
        schemaName: String,
        propertyName: String,
        nonNullType: String,
    ) {
        val types =
            schemas[schemaName]
                .asMap()["properties"]
                .asMap()[propertyName]
                .asMap()["type"]
                .asList()
                .map { it.toString() }
                .toSet()
        assertEquals(setOf(nonNullType, "null"), types)
    }

    private fun Any?.asMap(): Map<*, *> = assertNotNull(this as? Map<*, *>)

    private fun Any?.asList(): List<*> = assertNotNull(this as? List<*>)

    private companion object {
        val HTTP_METHODS = setOf("get", "post", "put", "patch", "delete", "head", "options", "trace")
        val STATE_CHANGING_METHODS = setOf("post", "put", "patch", "delete")
    }
}
