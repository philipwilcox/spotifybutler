package com.philipwilcox.spotifybutler.service

import com.philipwilcox.spotifybutler.spotify.SpotifyTrack
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path

@Serializable
data class PlaylistGenerationFixture(
    val schemaVersion: Int,
    val name: String,
    val tracks: Map<String, PlaylistGenerationFixtureTrack>,
    val cases: List<PlaylistGenerationFixtureCase>,
)

@Serializable
data class PlaylistGenerationFixtureTrack(
    val name: String,
    val id: String,
    val uri: String,
    val releaseDate: String? = null,
    val primaryArtistId: String? = null,
    val albumId: String? = null,
    val durationMs: Long? = null,
    val explicit: Boolean? = null,
    val addedAt: String? = null,
)

@Serializable
data class PlaylistGenerationFixtureCase(
    val name: String,
    val seedHex: String,
    val recipe: PlaylistRecipe,
    val expectedIds: List<String>,
)

fun loadPlaylistGenerationFixtures(resourceDirectory: Path): List<PlaylistGenerationFixture> =
    Files.walk(resourceDirectory).use { paths ->
        paths
            .filter { it.fileName.toString() == "composability.json" }
            .sorted()
            .map { path ->
                PlaylistRecipeCodec.json.decodeFromString<PlaylistGenerationFixture>(Files.readString(path))
            }.toList()
    }

fun PlaylistGenerationFixture.validate() {
    require(schemaVersion == 1) { "Unsupported generation fixture schema $schemaVersion" }
    require(tracks.isNotEmpty()) { "Generation fixture $name has no tracks" }
    cases.forEach { testCase ->
        require(testCase.seedHex.length == 64) { "Generation fixture case ${testCase.name} seed is not 256-bit" }
        require(testCase.expectedIds.all { expectedId -> tracks.values.any { it.id == expectedId } }) {
            "Generation fixture case ${testCase.name} references an unknown expected track"
        }
    }
}

fun PlaylistGenerationFixture.candidates(): List<CandidateTrack> =
    tracks.values.mapIndexed { index, fixtureTrack ->
        CandidateTrack(fixtureTrack.toSpotifyTrack(), fixtureTrack.addedAt, index)
    }

fun PlaylistGenerationFixture.trackById(id: String): PlaylistGenerationFixtureTrack =
    tracks.values.single { track -> track.id == id }

private fun PlaylistGenerationFixtureTrack.toSpotifyTrack(): SpotifyTrack =
    SpotifyTrack(
        name = name,
        id = id,
        href = "https://example.invalid/tracks/$id",
        uri = uri,
        releaseDate = releaseDate,
        primaryArtistId = primaryArtistId,
        rawJson = "{}",
        albumId = albumId,
        durationMs = durationMs,
        explicit = explicit,
        artistIds = listOfNotNull(primaryArtistId),
    )

private fun String.hexBytes(): ByteArray {
    require(length % 2 == 0) { "Hex value must have an even number of characters" }
    return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}

fun PlaylistGenerationFixtureCase.seedBytes(): ByteArray = seedHex.hexBytes()
