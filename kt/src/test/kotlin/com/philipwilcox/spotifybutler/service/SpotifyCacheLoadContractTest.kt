package com.philipwilcox.spotifybutler.service

import com.philipwilcox.spotifybutler.db.SpotifyStore
import com.philipwilcox.spotifybutler.spotify.SpotifyApiClient
import com.philipwilcox.spotifybutler.spotify.SpotifyHttpResponse
import com.philipwilcox.spotifybutler.spotify.SpotifyHttpTransport
import com.philipwilcox.spotifybutler.spotify.capturePath
import com.philipwilcox.spotifybutler.support.SpotifyFixture
import com.philipwilcox.spotifybutler.support.SpotifyFixtureResponse
import com.philipwilcox.spotifybutler.support.spotifyFixtureJson
import com.philipwilcox.spotifybutler.support.toExpectedTables
import org.junit.jupiter.api.Named
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.ArrayDeque
import java.util.stream.Stream
import kotlin.test.assertEquals

class SpotifyCacheLoadContractTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtureCases")
    fun `loads every fixture through the real parser and cache service`(
        source: String,
        fixture: SpotifyFixture,
    ) {
        val transport = ScriptedSpotifyTransport(fixture.responses)
        val databasePath = Files.createTempDirectory("spotify-fixture-").resolve("cache.db")
        SpotifyStore.open(databasePath).use { store ->
            val service = fixedCacheService(transport, store)
            service.loadIfNeeded("fixture-token", refresh = false)
            assertEquals(fixture.expectedTables, store.exportTables().toExpectedTables(), source)
        }
        SpotifyStore.openReadOnly(databasePath).use { store ->
            assertEquals(fixture.expectedTables, store.exportTables().toExpectedTables(), source)
        }
        transport.assertAllConsumed()
    }

    @ParameterizedTest(name = "{0} reuse and refresh")
    @MethodSource("fixtureCases")
    fun `reuses a completed cache and replaces it when refresh is requested`(
        source: String,
        fixture: SpotifyFixture,
    ) {
        val transport = ScriptedSpotifyTransport(fixture.responses + fixture.responses)
        val databasePath = Files.createTempDirectory("spotify-fixture-refresh-").resolve("cache.db")
        SpotifyStore.open(databasePath).use { store ->
            val service = fixedCacheService(transport, store)
            assertEquals(
                CacheLoadResult.Loaded(
                    savedTrackCount = fixture.expectedTables.savedTracks.size,
                    topTrackCount = fixture.expectedTables.topTracks.size,
                    topArtistCount = fixture.expectedTables.topArtists.size,
                    playlistCount = fixture.expectedTables.playlists.size,
                    playlistTrackCount = fixture.expectedTables.playlistTracks.size,
                ),
                service.loadIfNeeded("fixture-token", refresh = false),
                source,
            )
            assertEquals(CacheLoadResult.SkippedExistingCache, service.loadIfNeeded("fixture-token", refresh = false))
            service.loadIfNeeded("fixture-token", refresh = true)
            assertEquals(fixture.expectedTables, store.exportTables().toExpectedTables(), source)
        }
        transport.assertAllConsumed()
    }

    private fun fixedCacheService(
        transport: ScriptedSpotifyTransport,
        store: SpotifyStore,
    ): SpotifyCacheService =
        SpotifyCacheService(
            apiClient = SpotifyApiClient(transport = transport),
            store = store,
            clock = Clock.fixed(Instant.ofEpochMilli(1700000000000), ZoneOffset.UTC),
        )

    companion object {
        @JvmStatic
        fun fixtureCases(): Stream<Arguments> {
            val fixtureDirectory =
                requireNotNull(SpotifyCacheLoadContractTest::class.java.classLoader.getResource("spotify-fixtures")) {
                    "spotify-fixtures test resources are missing"
                }
            val root = Path.of(fixtureDirectory.toURI())
            val cases = mutableListOf<Arguments>()
            Files.walk(root).use { paths ->
                paths.filter { path -> path.toString().endsWith(".jsonl") }.sorted().forEach { path ->
                    Files.readAllLines(path).forEachIndexed { lineNumber, line ->
                        if (line.isNotBlank()) {
                            val fixture = spotifyFixtureJson.decodeFromString<SpotifyFixture>(line)
                            cases +=
                                Arguments.of(
                                    Named.of("$path:${lineNumber + 1}", "$path:${lineNumber + 1}"),
                                    fixture,
                                )
                        }
                    }
                }
            }
            return cases.stream()
        }
    }
}

private class ScriptedSpotifyTransport(
    responses: List<SpotifyFixtureResponse>,
) : SpotifyHttpTransport {
    private val remaining =
        responses
            .groupBy { requestKey(it.method, it.path) }
            .mapValues { (_, values) -> ArrayDeque(values) }
            .toMutableMap()

    override fun get(
        uri: URI,
        accessToken: String,
    ): SpotifyHttpResponse {
        require(accessToken == "fixture-token") { "Contract tests must not use a real Spotify access token" }
        val key = requestKey("GET", capturePath(uri))
        val responses = remaining[key] ?: error("Unexpected fake Spotify request: $key")
        require(responses.isNotEmpty()) { "Fake Spotify response already consumed for $key" }
        val response = responses.removeFirst()
        return SpotifyHttpResponse(response.status, response.body.toString())
    }

    fun assertAllConsumed() {
        val unused = remaining.filterValues { it.isNotEmpty() }.map { (key, values) -> "$key (${values.size})" }
        assertEquals(emptyList(), unused, "Fixture responses were not consumed: ${unused.joinToString()}")
    }

    private fun requestKey(
        method: String,
        path: String,
    ): String = "$method $path"
}
