package com.philipwilcox.spotifybutler.http

import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OpenApiContractTest {
    @Test
    fun targetRoutesExcludeRemovedContracts() {
        val paths = document()["paths"].asMap()
        val expected =
            setOf(
                "/health",
                "/start",
                "/callback",
                "/api/v1/session",
                "/api/v1/session/refresh",
                "/api/v1/library",
                "/api/v1/library/refresh",
                "/api/v1/library/playlists/{playlistId}",
                "/api/v1/playlists",
                "/api/v1/playlists/{definitionId}",
                "/api/v1/playlists/{definitionId}/preview",
                "/api/v1/playlists/{definitionId}/recipe-settings",
                "/api/v1/playlists/{definitionId}/publish-plan",
                "/api/v1/playlists/{definitionId}/publish",
                "/api/v1/playlists/{definitionId}/current",
                "/api/v1/playlists/{definitionId}/syncs",
                "/api/v1/songs",
                "/api/v1/songs/bulk",
                "/api/v1/operations/{operationId}/events",
            )
        assertEquals(expected, paths.keys.map { it.toString() }.toSet())
        assertEquals(
            setOf("get"),
            paths["/api/v1/playlists/{definitionId}/preview"]
                .asMap()
                .keys
                .map { it.toString() }
                .filter {
                    it ==
                        "get" ||
                        it == "post"
                }.toSet(),
        )
        assertEquals(null, paths["/api/v1/run"])
        assertEquals(null, paths["/api/v1/songs/{trackId}"])
    }

    @Test
    fun typedResponsesAndSanitizedErrorsAreDocumented() {
        val source = requireNotNull(javaClass.getResource("/openapi.yaml")).readText()
        assertTrue(source.contains("#/components/responses/ErrorResponse"))
        assertTrue(source.contains("ownerSpotifyUserId"))
        assertTrue(source.contains("sourceDependencies"))
        assertTrue(source.contains("recipeRevision"))
        assertTrue(source.contains("PublishPlan"))
        assertTrue(source.contains("PublishDestinationRequest"))
    }

    private fun document(): Map<*, *> {
        val source = requireNotNull(javaClass.getResource("/openapi.yaml")).readText()
        return Load(LoadSettings.builder().build()).loadFromString(source).asMap()
    }

    private fun Any?.asMap(): Map<*, *> = assertNotNull(this as? Map<*, *>)

    private fun Any?.asList(): List<*> = assertNotNull(this as? List<*>)

    private companion object {
        val HTTP_METHODS = setOf("get", "post", "put", "patch", "delete", "head", "options", "trace")
    }
}
