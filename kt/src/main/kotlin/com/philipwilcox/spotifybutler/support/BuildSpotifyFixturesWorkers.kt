package com.philipwilcox.spotifybutler.support

import com.philipwilcox.spotifybutler.spotify.SpotifyCaptureEvent
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.Executors

private val fixtureLogger = KotlinLogging.logger("buildSpotifyFixtures")

internal fun writeFixturesInParallel(
    runs: List<ParsedCaptureRun>,
    outputs: List<Pair<Path, Path>>,
    expectedTables: ExpectedTables,
    database: Path,
    scrubWorkers: Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
    maxPlaylistTracksCalls: Int? = null,
    maxPlaylistTracks: Int = 100,
    maxSavedTracks: Int? = 10,
    maxTopItems: Int? = 10,
    maxAvailableMarkets: Int = 5,
) {
    require(runs.size == outputs.size) { "Every capture run must have an output pair" }
    require(scrubWorkers > 0) { "Scrub worker count must be positive" }
    require(maxPlaylistTracksCalls == null || maxPlaylistTracksCalls >= 0) {
        "Maximum playlist-tracks calls must not be negative"
    }
    require(maxPlaylistTracks >= 0) { "Maximum playlist tracks must not be negative" }
    require(maxSavedTracks == null || maxSavedTracks >= 0) { "Maximum saved tracks must not be negative" }
    require(maxTopItems == null || maxTopItems >= 0) { "Maximum top items must not be negative" }
    require(maxAvailableMarkets >= 0) { "Maximum available markets must not be negative" }

    val preparedRuns =
        runs.zip(outputs).map { (run, paths) ->
            prepareFixtureBuild(
                run,
                paths,
                expectedTables,
                maxPlaylistTracksCalls,
                maxPlaylistTracks,
                maxSavedTracks,
                maxTopItems,
                maxAvailableMarkets,
            )
        }
    val executor = Executors.newFixedThreadPool(scrubWorkers)
    var succeeded = false
    try {
        val scrubbedFixtures = scrubPreparedFixtures(preparedRuns.map { it.prepared }, scrubWorkers, executor)
        preparedRuns.zip(scrubbedFixtures).forEach { (prepared, scrubbedFixture) ->
            writeFixtureOutputs(prepared, scrubbedFixture, database)
        }
        succeeded = true
    } finally {
        if (succeeded) executor.shutdown() else executor.shutdownNow()
    }
}

private data class PreparedFixtureBuild(
    val run: ParsedCaptureRun,
    val validated: ValidatedCapture,
    val output: Path,
    val report: Path,
    val prepared: PreparedFixture,
)

private fun prepareFixtureBuild(
    run: ParsedCaptureRun,
    paths: Pair<Path, Path>,
    expectedTables: ExpectedTables,
    maxPlaylistTracksCalls: Int?,
    maxPlaylistTracks: Int,
    maxSavedTracks: Int?,
    maxTopItems: Int?,
    maxAvailableMarkets: Int,
): PreparedFixtureBuild {
    val (output, report) = paths
    progress("reading and validating capture run ${run.runId}")
    val validated =
        validateCapture(run)
            .limitPlaylistTracksCalls(maxPlaylistTracksCalls)
            .limitPlaylistTrackItems(maxPlaylistTracks)
            .limitSavedTracks(maxSavedTracks)
            .limitTopItems(maxTopItems)
    progress("capture run ${run.runId} validation complete")
    val rawFixture =
        SpotifyFixture(
            schemaVersion = 1,
            name =
                output.fileName
                    .toString()
                    .removeSuffix(".draft.jsonl")
                    .ifBlank { "spotify-capture" },
            responses = validated.pageEvents.map(::fixtureResponse),
            expectedTables = expectedTables.limitToCapturedItems(validated),
        )
    progress("preparing fixture ${run.runId} for scrubbing")
    return PreparedFixtureBuild(
        run,
        validated,
        output,
        report,
        prepareFixture(run.runId, rawFixture.limitAvailableMarkets(maxAvailableMarkets)),
    )
}

private fun writeFixtureOutputs(
    prepared: PreparedFixtureBuild,
    fixture: SpotifyFixture,
    database: Path,
) {
    val output = prepared.output
    val report = prepared.report
    val fixtureText = canonicalFixtureLine(fixture) + System.lineSeparator()
    val reportText = reportText(prepared.validated, fixture, database, output)
    writeAtomically(output, report, fixtureText, reportText)
    progress("fixture ${prepared.run.runId} files complete")
    progress("Spotify fixture draft: ${output.toAbsolutePath().normalize()}")
    progress("Spotify fixture report: ${report.toAbsolutePath().normalize()}")
}

private fun writeAtomically(
    output: Path,
    report: Path,
    fixtureText: String,
    reportText: String,
) {
    Files.createDirectories(requireNotNull(output.parent))
    Files.createDirectories(requireNotNull(report.parent))
    val outputTemporary = temporarySibling(output)
    try {
        val reportTemporary = temporarySibling(report)
        try {
            Files.writeString(outputTemporary, fixtureText)
            Files.writeString(reportTemporary, reportText)
            moveIntoPlace(reportTemporary, report)
            moveIntoPlace(outputTemporary, output)
        } finally {
            Files.deleteIfExists(reportTemporary)
        }
    } finally {
        Files.deleteIfExists(outputTemporary)
    }
}

private fun temporarySibling(path: Path): Path =
    Files.createTempFile(requireNotNull(path.parent), ".${path.fileName}.", ".tmp")

@Suppress("SwallowedException")
private fun moveIntoPlace(
    source: Path,
    destination: Path,
) {
    try {
        Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch (exception: AtomicMoveNotSupportedException) {
        Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING)
    }
}

internal fun progress(message: String) {
    fixtureLogger.info { message }
}

private fun fixtureResponse(event: SpotifyCaptureEvent): SpotifyFixtureResponse =
    SpotifyFixtureResponse(
        method = event.method,
        path = event.path,
        status = event.status,
        body = spotifyFixtureJson.parseToJsonElement(event.body).let(::canonicalize),
    )

private fun reportText(
    capture: ValidatedCapture,
    fixture: SpotifyFixture,
    database: Path,
    output: Path,
): String =
    buildString {
        appendLine("runId=${capture.run.runId}")
        appendLine("database=${database.toAbsolutePath().normalize()}")
        appendLine("output=${output.toAbsolutePath().normalize()}")
        appendLine("capturedEvents=${capture.run.events.size}")
        appendLine("fixtureResponses=${fixture.responses.size}")
        appendLine("ignoredNonPageResponses=${capture.ignoredNonPageEvents.size}")
        appendLine("endpoints:")
        capture.pageCounts.toSortedMap().forEach { (endpoint, pages) -> appendLine("  $endpoint pages=$pages") }
        appendLine("tableRows:")
        appendLine("  saved_tracks=${fixture.expectedTables.savedTracks.size}")
        appendLine("  top_tracks=${fixture.expectedTables.topTracks.size}")
        appendLine("  top_artists=${fixture.expectedTables.topArtists.size}")
        appendLine("  playlists=${fixture.expectedTables.playlists.size}")
        appendLine("  playlist_tracks=${fixture.expectedTables.playlistTracks.size}")
        appendLine("  sync_status=${fixture.expectedTables.syncStatus.size}")
    }
