package com.philipwilcox.spotifybutler.service

import com.philipwilcox.spotifybutler.spotify.SpotifyTrack
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path

private val playlistGenerationLogger = KotlinLogging.logger("playlistGenerationContract")

data class OrderedTrackDiff(
    val firstDifference: Int?,
    val missing: List<String>,
    val unexpected: List<String>,
    val moved: List<String>,
)

fun orderedTrackDiff(
    expected: List<String>,
    actual: List<String>,
): OrderedTrackDiff {
    val expectedPositions = expected.withIndex().groupBy({ it.value }, { it.index })
    val actualPositions = actual.withIndex().groupBy({ it.value }, { it.index })
    val firstDifference =
        (0 until maxOf(expected.size, actual.size)).firstOrNull { index ->
            expected.getOrNull(index) != actual.getOrNull(index)
        }
    val missing = expectedPositions.keys.filter { key -> expectedPositions[key] != actualPositions[key] }
    val unexpected = actualPositions.keys.filter { key -> actualPositions[key] != expectedPositions[key] }
    val moved =
        expectedPositions.keys.filter { key ->
            key in actualPositions && expectedPositions[key] != actualPositions[key]
        }
    return OrderedTrackDiff(firstDifference, missing, unexpected, moved)
}

data class PlaylistGenerationTestReport(
    val fixtureName: String,
    val definition: PlaylistDefinition,
    val executionPath: String,
    val actualTracks: List<SpotifyTrack>,
    val expectedUris: List<String>? = null,
    val cacheRevision: String? = null,
    val recipeRevision: String? = null,
    val recipeJson: String? = null,
    val algorithmVersion: String? = null,
    val seedHex: String? = null,
    val notes: List<String> = emptyList(),
) {
    fun render(): String {
        val actualUris = actualTracks.map(SpotifyTrack::uri)
        val lines =
            buildList {
                add("Playlist generation contract")
                add("fixture: $fixtureName")
                add("definitionId: ${definition.id.name}")
                add("playlistName: ${definition.name}")
                add("executionPath: $executionPath")
                add("definition: ${definition.query}")
                cacheRevision?.let { add("cacheRevision: $it") }
                recipeRevision?.let { add("recipeRevision: $it") }
                recipeJson?.let { add("recipe: $it") }
                algorithmVersion?.let { add("algorithmVersion: $it") }
                seedHex?.let { add("seed: $it") }
                add("candidateOrSelectedCount: ${actualTracks.size}")
                add("actualOrderedUris: ${actualUris.joinToString(", ")}")
                if (expectedUris != null) {
                    val diff = orderedTrackDiff(expectedUris, actualUris)
                    add("expectedOrderedUris: ${expectedUris.joinToString(", ")}")
                    add("firstDifference: ${diff.firstDifference ?: "none"}")
                    add("missing: ${diff.missing.joinToString(", ")}")
                    add("unexpected: ${diff.unexpected.joinToString(", ")}")
                    add("moved: ${diff.moved.joinToString(", ")}")
                }
                if (notes.isNotEmpty()) add("notes: ${notes.joinToString(" | ")}")
                add("songs:")
                actualTracks.forEachIndexed { index, track ->
                    add("  ${"%02d".format(index + 1)}  ${renderTrack(track)}")
                }
            }
        return lines.joinToString("\n")
    }

    private fun renderTrack(track: SpotifyTrack): String =
        listOf(
            track.name,
            track.uri,
            "artist=${track.primaryArtistId ?: "<null>"}",
            "release=${track.releaseDate ?: "<null>"}",
        ).joinToString(" | ")
}

fun logPlaylistGenerationReport(report: PlaylistGenerationTestReport) {
    val rendered = report.render()
    playlistGenerationLogger.info { "\n$rendered" }
    writePlaylistGenerationReport(report, rendered)
}

private fun writePlaylistGenerationReport(
    report: PlaylistGenerationTestReport,
    rendered: String,
) {
    val reportDirectory = System.getProperty("playlistGenerationReportDir") ?: return
    val directory = Path.of(reportDirectory)
    Files.createDirectories(directory)
    val filename =
        listOf(report.fixtureName, report.executionPath, report.definition.id.name)
            .joinToString("-")
            .lowercase()
            .replace(Regex("[^a-z0-9._-]+"), "-")
    Files.writeString(directory.resolve("$filename.txt"), rendered + "\n")
}
