package com.philipwilcox.spotifybutler.support

import com.philipwilcox.spotifybutler.db.SpotifyStore
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.nameWithoutExtension

fun main(arguments: Array<String>) {
    val options = parseArguments(arguments)
    val captureLog = Path.of(options.required("--capture-log")).toAbsolutePath().normalize()
    val scrubWorkers =
        options["--scrub-workers"]?.let(::parseScrubWorkers)
            ?: Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
    val maxPlaylistTracksCalls = options["--max-playlist-tracks-calls"]?.let(::parseMaxPlaylistTracksCalls)
    val maxPlaylistTracks =
        options["--max-playlist-tracks"]?.let(::parseMaxPlaylistTracks)
            ?: DEFAULT_MAX_PLAYLIST_TRACKS
    val maxSavedTracks = options["--max-saved-tracks"]?.let(::parseMaxSavedTracks) ?: DEFAULT_MAX_SAVED_TRACKS
    val maxTopItems = options["--max-top-items"]?.let(::parseMaxTopItems) ?: DEFAULT_MAX_TOP_ITEMS
    val maxAvailableMarkets =
        options["--max-available-markets"]?.let(::parseMaxAvailableMarkets)
            ?: DEFAULT_MAX_AVAILABLE_MARKETS
    val requestedRunId = options["--run-id"]
    val runs =
        if (requestedRunId == null) {
            parseCaptureRuns(captureLog)
        } else {
            listOf(parseCaptureLog(captureLog, requestedRunId))
        }
    val database = resolveDatabase(options["--database"], captureLog)
    val outputs = resolveOutputs(options["--output"], options["--report"], captureLog, runs)
    outputs.forEach { (output, _) ->
        require(!output.toAbsolutePath().normalize().isUnderTestResources()) {
            "Raw fixture drafts must not be written under src/test/resources; sanitize and copy them explicitly."
        }
    }

    progress(
        "starting fixture build scrubWorkers=$scrubWorkers " +
            "maxPlaylistTracksCalls=${maxPlaylistTracksCalls ?: "unlimited"} " +
            "maxPlaylistTracks=$maxPlaylistTracks " +
            "maxSavedTracks=$maxSavedTracks " +
            "maxTopItems=$maxTopItems " +
            "maxAvailableMarkets=$maxAvailableMarkets",
    )
    progress("opening database")
    val expectedTables = SpotifyStore.openReadOnly(database).use { it.exportTables().toExpectedTables() }
    progress("database export complete")
    writeFixturesInParallel(
        runs,
        outputs,
        expectedTables,
        database,
        scrubWorkers,
        maxPlaylistTracksCalls,
        maxPlaylistTracks,
        maxSavedTracks,
        maxTopItems,
        maxAvailableMarkets,
    )
    progress("UUID-scrubbed drafts are ready for review before copying them into src/test/resources/spotify-fixtures/.")
}

private fun resolveDatabase(
    configured: String?,
    captureLog: Path,
): Path {
    val fromStartup =
        Files
            .readAllLines(captureLog)
            .firstNotNullOfOrNull { line ->
                line
                    .substringAfter("Spotify startup paths: database=", "")
                    .substringBefore(" captureLog=")
                    .takeIf(String::isNotBlank)
            }
    val candidate = configured ?: fromStartup ?: defaultDatabasePath().toString()
    return Path.of(candidate).toAbsolutePath().normalize()
}

private fun resolveOutput(
    configured: String?,
    captureLog: Path,
): Path {
    if (configured != null) return Path.of(configured).toAbsolutePath().normalize()
    val base = captureLog.fileName.toString().substringBeforeLast('.', captureLog.fileName.toString())
    return captureLog.resolveSibling("$base.draft.jsonl").toAbsolutePath().normalize()
}

private fun resolveOutputs(
    configured: String?,
    configuredReport: String?,
    captureLog: Path,
    runs: List<ParsedCaptureRun>,
): List<Pair<Path, Path>> {
    val output = resolveOutput(configured, captureLog)
    if (runs.size == 1) return listOf(output to resolveReport(configuredReport, output))
    require(configuredReport == null) {
        "--report cannot be used when building multiple capture runs; use --run-id or omit --report."
    }
    return runs.map { run ->
        val extension = output.fileName.toString().substringAfterLast('.', "")
        val suffix = if (extension.isBlank()) "" else ".$extension"
        val runOutput = output.resolveSibling("${output.nameWithoutExtension}-${run.runId}$suffix")
        runOutput to resolveReport(null, runOutput)
    }
}

private fun resolveReport(
    configured: String?,
    output: Path,
): Path =
    configured?.let { Path.of(it) }?.toAbsolutePath()?.normalize()
        ?: output.resolveSibling("${output.nameWithoutExtension}.report.txt")

private fun defaultDatabasePath(): Path =
    if (Files.isDirectory(Path.of("kt"))) Path.of("kt", "spotify.db") else Path.of("spotify.db")

private fun Path.isUnderTestResources(): Boolean {
    val resources = resolve("src", "test", "resources").toAbsolutePath().normalize()
    return toAbsolutePath().normalize().startsWith(resources)
}

private fun parseArguments(arguments: Array<String>): Map<String, String> {
    require(arguments.size % 2 == 0) { "Arguments must be supplied as --name value pairs" }
    return arguments.toList().chunked(2).associate { (key, value) ->
        require(key.startsWith("--")) { "Unknown argument $key" }
        key to value
    }
}

private fun Map<String, String>.required(key: String): String =
    get(key)?.takeIf(String::isNotBlank) ?: error("Missing required argument $key")

private fun parseScrubWorkers(value: String): Int =
    value.toIntOrNull()?.takeIf { it > 0 }
        ?: error("--scrub-workers must be a positive integer")

private fun parseMaxPlaylistTracksCalls(value: String): Int =
    value.toIntOrNull()?.takeIf { it >= 0 }
        ?: error("--max-playlist-tracks-calls must be a non-negative integer")

private fun parseMaxSavedTracks(value: String): Int =
    value.toIntOrNull()?.takeIf { it >= 0 }
        ?: error("--max-saved-tracks must be a non-negative integer")

private fun parseMaxPlaylistTracks(value: String): Int =
    value.toIntOrNull()?.takeIf { it >= 0 }
        ?: error("--max-playlist-tracks must be a non-negative integer")

private fun parseMaxTopItems(value: String): Int =
    value.toIntOrNull()?.takeIf { it >= 0 }
        ?: error("--max-top-items must be a non-negative integer")

private fun parseMaxAvailableMarkets(value: String): Int =
    value.toIntOrNull()?.takeIf { it >= 0 }
        ?: error("--max-available-markets must be a non-negative integer")

private const val DEFAULT_MAX_AVAILABLE_MARKETS = 5
private const val DEFAULT_MAX_PLAYLIST_TRACKS = 100
private const val DEFAULT_MAX_SAVED_TRACKS = 10
private const val DEFAULT_MAX_TOP_ITEMS = 10
