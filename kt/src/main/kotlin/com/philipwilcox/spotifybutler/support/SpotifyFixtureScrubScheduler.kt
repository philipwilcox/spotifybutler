package com.philipwilcox.spotifybutler.support

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.ArrayDeque
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import kotlin.math.max
import kotlin.math.min

private const val MAX_TABLE_CHUNK_SIZE = 128
private const val TABLE_CHUNKS_PER_WORKER = 4
private const val NANOS_PER_MILLISECOND = 1_000_000L
private const val MILLIS_PER_SECOND = 1_000.0

@Suppress("TooGenericExceptionCaught")
internal fun scrubPreparedFixtures(
    fixtures: List<PreparedFixture>,
    workerCount: Int,
    executor: ExecutorService,
): List<SpotifyFixture> =
    scrubPreparedFixtures(fixtures, workerCount, executor) { element, plan -> scrubElement(element, plan) }

@Suppress("GenericException")
internal fun scrubPreparedFixtures(
    fixtures: List<PreparedFixture>,
    workerCount: Int,
    executor: ExecutorService,
    transform: (JsonElement, ReplacementPlan) -> JsonElement,
): List<SpotifyFixture> {
    require(workerCount > 0) { "Scrub worker count must be positive" }
    if (fixtures.isEmpty()) return emptyList()

    val pageQueues = buildPageQueues(fixtures)
    val tableQueue = buildTableQueue(fixtures, workerCount)
    val states = fixtures.mapIndexed { index, fixture -> FixtureScrubState(fixture, index) }
    val results = arrayOfNulls<SpotifyFixture>(fixtures.size)
    val completionService = ExecutorCompletionService<CompletedScrubWork>(executor)
    val outstanding = linkedMapOf<Future<CompletedScrubWork>, ScrubWork>()
    logScrubStarts(fixtures, pageQueues, tableQueue, workerCount)
    completeEmptyFixtures(states, results)

    try {
        while (outstanding.isNotEmpty() || pageQueues.isNotEmpty() || tableQueue.isNotEmpty()) {
            dispatchWork(pageQueues, tableQueue, outstanding, completionService, workerCount, transform)
            if (outstanding.isEmpty()) continue

            val future = completionService.take()
            val work = requireNotNull(outstanding.remove(future))
            val completed = completedWork(future, work)
            applyCompletedWork(completed, states)
            dispatchWork(pageQueues, tableQueue, outstanding, completionService, workerCount, transform)
            logCompletedWork(completed, states, pageQueues, tableQueue, outstanding.size)
            completeReadyFixtures(states, results)
        }
    } catch (exception: InterruptedException) {
        Thread.currentThread().interrupt()
        cancelOutstanding(outstanding)
        throw IllegalStateException("Fixture scrubbing was interrupted", exception)
    } catch (exception: CancellationException) {
        cancelOutstanding(outstanding)
        throw exception
    }

    return results.map { requireNotNull(it) }
}

private fun logScrubStarts(
    fixtures: List<PreparedFixture>,
    pageQueues: ArrayDeque<EndpointPageQueue>,
    tableQueue: ArrayDeque<TableChunkWork>,
    workerCount: Int,
) {
    fixtures.forEachIndexed { index, fixture ->
        val endpointCount = pageQueues.count { it.fixtureIndex == index }
        val tableRows = fixture.expectedTables.values.sumOf { rows -> rows.size }
        val tableChunks = tableQueue.count { it.fixtureIndex == index }
        progress(
            "run=${fixture.runId} scrub start; workers=$workerCount endpoints=$endpointCount " +
                "responsePages=${fixture.responses.size} expectedTableWork=$tableChunks chunks/$tableRows rows",
        )
    }
}

private fun completeEmptyFixtures(
    states: List<FixtureScrubState>,
    results: Array<SpotifyFixture?>,
) {
    states.forEachIndexed { index, state ->
        if (state.totalWork == 0) {
            state.complete = true
            results[index] = state.fixture.assemble()
            progress(
                "run=${state.fixture.runId} fixture complete; pages=0 tableRows=0 " +
                    "elapsedMs=0 throughput=0.00 pages/s",
            )
        }
    }
}

private fun applyCompletedWork(
    completed: CompletedScrubWork,
    states: List<FixtureScrubState>,
) {
    when (completed) {
        is CompletedPage -> {
            val state = states[completed.work.fixtureIndex]
            state.fixture.placeResponse(completed.work.responseIndex, completed.response)
            state.completedPages++
            state.endpointProgress.getValue(completed.work.endpointKey).completedPages++
        }

        is CompletedTableChunk -> {
            val state = states[completed.work.fixtureIndex]
            state.fixture.placeTableRows(completed.work.table, completed.work.startIndex, completed.rows)
            state.completedTableRows += completed.rows.size
        }
    }
}

private fun logCompletedWork(
    completed: CompletedScrubWork,
    states: List<FixtureScrubState>,
    pageQueues: ArrayDeque<EndpointPageQueue>,
    tableQueue: ArrayDeque<TableChunkWork>,
    inFlight: Int,
) {
    when (completed) {
        is CompletedPage -> {
            val state = states[completed.work.fixtureIndex]
            val endpointProgress = state.endpointProgress.getValue(completed.work.endpointKey)
            if (endpointProgress.completedPages == endpointProgress.totalPages) {
                val elapsedMillis = elapsedMillis(state.startedAtNanos)
                val throughput = throughput(endpointProgress.completedPages, elapsedMillis)
                val throughputText = "%.2f".format(java.util.Locale.ROOT, throughput)
                progress(
                    "run=${state.fixture.runId} endpoint=${completed.work.endpoint} complete; " +
                        "pages=${endpointProgress.completedPages}/${endpointProgress.totalPages} " +
                        "elapsedMs=$elapsedMillis throughput=$throughputText pages/s",
                )
            }
            progress(
                "run=${completed.work.runId} endpoint=${completed.work.endpoint} " +
                    "page=${completed.work.endpointOrdinal}/${completed.work.endpointTotal} complete; " +
                    "pages=${state.completedPages}/${state.fixture.responses.size}; inFlight=$inFlight " +
                    "queued=${queuedWorkCount(pageQueues, tableQueue)}",
            )
        }

        is CompletedTableChunk -> {
            val state = states[completed.work.fixtureIndex]
            progress(
                "run=${completed.work.runId} table=${completed.work.table} " +
                    "rows=${completed.work.startIndex + 1}-${completed.work.endIndex} complete; " +
                    "tableRows=${state.completedTableRows}/${state.totalTableRows}; " +
                    "inFlight=$inFlight queued=${queuedWorkCount(pageQueues, tableQueue)}",
            )
        }
    }
}

private fun completeReadyFixtures(
    states: List<FixtureScrubState>,
    results: Array<SpotifyFixture?>,
) {
    states.forEachIndexed { index, state ->
        if (!state.complete && state.completedWork == state.totalWork) {
            state.complete = true
            results[index] = state.fixture.assemble()
            val elapsedMillis = elapsedMillis(state.startedAtNanos)
            val throughput = throughput(state.fixture.responses.size, elapsedMillis)
            val throughputText = "%.2f".format(java.util.Locale.ROOT, throughput)
            progress(
                "run=${state.fixture.runId} fixture complete; pages=${state.fixture.responses.size} " +
                    "tableRows=${state.completedTableRows} elapsedMs=$elapsedMillis " +
                    "throughput=$throughputText pages/s",
            )
        }
    }
}

private fun dispatchWork(
    pageQueues: ArrayDeque<EndpointPageQueue>,
    tableQueue: ArrayDeque<TableChunkWork>,
    outstanding: MutableMap<Future<CompletedScrubWork>, ScrubWork>,
    completionService: ExecutorCompletionService<CompletedScrubWork>,
    workerCount: Int,
    transform: (JsonElement, ReplacementPlan) -> JsonElement,
) {
    while (outstanding.size < workerCount) {
        val work =
            nextPage(pageQueues) ?: run {
                if (tableQueue.isEmpty()) return
                tableQueue.removeFirst()
            }
        val future = completionService.submit(Callable { scrubWork(work, transform) })
        outstanding[future] = work
    }
}

private fun nextPage(pageQueues: ArrayDeque<EndpointPageQueue>): PageWork? {
    if (pageQueues.isEmpty()) return null
    val queue = pageQueues.removeFirst()
    val page = queue.pages.removeFirst()
    if (queue.pages.isNotEmpty()) pageQueues.addLast(queue)
    return page
}

private fun scrubWork(
    work: ScrubWork,
    transform: (JsonElement, ReplacementPlan) -> JsonElement,
): CompletedScrubWork =
    when (work) {
        is PageWork -> CompletedPage(work, transform(work.response, work.replacementPlan))
        is TableChunkWork -> CompletedTableChunk(work, work.rows.map { row -> transform(row, work.replacementPlan) })
    }

private fun completedWork(
    future: Future<CompletedScrubWork>,
    work: ScrubWork,
): CompletedScrubWork =
    try {
        future.get()
    } catch (exception: java.util.concurrent.ExecutionException) {
        val cause = exception.cause ?: exception
        throw IllegalStateException("Scrubbing failed for ${work.description()}", cause)
    } catch (exception: CancellationException) {
        throw IllegalStateException("Scrubbing was cancelled for ${work.description()}", exception)
    }

private fun cancelOutstanding(outstanding: Map<Future<CompletedScrubWork>, ScrubWork>) {
    outstanding.keys.forEach { future -> future.cancel(true) }
}

private fun buildPageQueues(fixtures: List<PreparedFixture>): ArrayDeque<EndpointPageQueue> {
    val queues = ArrayDeque<EndpointPageQueue>()
    fixtures.forEachIndexed { fixtureIndex, fixture ->
        val endpointPages = linkedMapOf<String, MutableList<SpotifyFixtureResponseDescriptor>>()
        val endpointOrdinals = linkedMapOf<String, Int>()
        fixture.responses.forEachIndexed { responseIndex, response ->
            val address = responseAddress(response)
            val endpoint = address.endpoint
            val ordinal = endpointOrdinals[endpoint]?.plus(1) ?: 1
            endpointOrdinals[endpoint] = ordinal
            endpointPages.getOrPut(endpoint) { mutableListOf() }.add(
                SpotifyFixtureResponseDescriptor(responseIndex, response, address.path, ordinal),
            )
        }
        endpointPages.forEach { (endpoint, pages) ->
            val key = EndpointKey(fixtureIndex, endpoint)
            queues.addLast(
                EndpointPageQueue(
                    fixtureIndex,
                    ArrayDeque(
                        pages.map { page ->
                            PageWork(
                                fixtureIndex = fixtureIndex,
                                runId = fixture.runId,
                                endpointKey = key,
                                endpoint = endpoint,
                                path = page.path,
                                responseIndex = page.responseIndex,
                                endpointOrdinal = page.endpointOrdinal,
                                endpointTotal = pages.size,
                                response = page.response,
                                replacementPlan = fixture.replacementPlan,
                            )
                        },
                    ),
                ),
            )
        }
    }
    return queues
}

private fun buildTableQueue(
    fixtures: List<PreparedFixture>,
    workerCount: Int,
): ArrayDeque<TableChunkWork> {
    val queue = ArrayDeque<TableChunkWork>()
    fixtures.forEachIndexed { fixtureIndex, fixture ->
        fixture.expectedTables.forEach { (table, rows) ->
            val chunkSize = tableChunkSize(rows.size, workerCount)
            for (startIndex in rows.indices step chunkSize) {
                val endIndex = min(startIndex + chunkSize, rows.size)
                queue.addLast(
                    TableChunkWork(
                        fixtureIndex = fixtureIndex,
                        runId = fixture.runId,
                        table = table,
                        startIndex = startIndex,
                        endIndex = endIndex,
                        rows = rows.subList(startIndex, endIndex).toList(),
                        replacementPlan = fixture.replacementPlan,
                    ),
                )
            }
        }
    }
    return queue
}

private fun tableChunkSize(
    rowCount: Int,
    workerCount: Int,
): Int =
    if (rowCount == 0) {
        1
    } else {
        max(
            1,
            min(
                MAX_TABLE_CHUNK_SIZE,
                (rowCount + workerCount * TABLE_CHUNKS_PER_WORKER - 1) / (workerCount * TABLE_CHUNKS_PER_WORKER),
            ),
        )
    }

private fun queuedWorkCount(
    pageQueues: ArrayDeque<EndpointPageQueue>,
    tableQueue: ArrayDeque<TableChunkWork>,
): Int = pageQueues.sumOf { it.pages.size } + tableQueue.size

private fun responseAddress(response: JsonElement): ResponseAddress {
    val path = (response as? JsonObject)?.get("path") as? JsonPrimitive
    val fullPath = path?.content ?: "<unknown>"
    return ResponseAddress(fullPath, fullPath.substringBefore('?'))
}

private data class ResponseAddress(
    val path: String,
    val endpoint: String,
)

private data class EndpointKey(
    val fixtureIndex: Int,
    val endpoint: String,
)

private data class SpotifyFixtureResponseDescriptor(
    val responseIndex: Int,
    val response: JsonElement,
    val path: String,
    val endpointOrdinal: Int,
)

private data class EndpointPageQueue(
    val fixtureIndex: Int,
    val pages: ArrayDeque<PageWork>,
)

private sealed interface ScrubWork {
    val fixtureIndex: Int
    val runId: String

    fun description(): String
}

private data class PageWork(
    override val fixtureIndex: Int,
    override val runId: String,
    val endpointKey: EndpointKey,
    val endpoint: String,
    val path: String,
    val responseIndex: Int,
    val endpointOrdinal: Int,
    val endpointTotal: Int,
    val response: JsonElement,
    val replacementPlan: ReplacementPlan,
) : ScrubWork {
    override fun description(): String = "run=$runId endpoint=$endpoint path=$path page=$endpointOrdinal/$endpointTotal"
}

private data class TableChunkWork(
    override val fixtureIndex: Int,
    override val runId: String,
    val table: String,
    val startIndex: Int,
    val endIndex: Int,
    val rows: List<JsonObject>,
    val replacementPlan: ReplacementPlan,
) : ScrubWork {
    override fun description(): String = "run=$runId table=$table rows=${startIndex + 1}-$endIndex"
}

private sealed interface CompletedScrubWork

private data class CompletedPage(
    val work: PageWork,
    val response: JsonElement,
) : CompletedScrubWork

private data class CompletedTableChunk(
    val work: TableChunkWork,
    val rows: List<JsonElement>,
) : CompletedScrubWork

private class EndpointProgress(
    val totalPages: Int,
) {
    var completedPages: Int = 0
}

private class FixtureScrubState(
    val fixture: PreparedFixture,
    fixtureIndex: Int,
) {
    val startedAtNanos = System.nanoTime()
    val endpointProgress =
        fixture.responses
            .groupingBy { response -> responseAddress(response).endpoint }
            .eachCount()
            .mapKeys { (endpoint, _) -> EndpointKey(fixtureIndex, endpoint) }
            .mapValues { (_, totalPages) -> EndpointProgress(totalPages) }
    val totalWork = fixture.responses.size + fixture.expectedTables.values.sumOf { rows -> rows.size }
    val totalTableRows = fixture.expectedTables.values.sumOf { rows -> rows.size }
    var completedPages = 0
    var completedTableRows = 0
    var complete = false

    val completedWork: Int
        get() = completedPages + completedTableRows
}

private fun elapsedMillis(startedAtNanos: Long): Long =
    ((System.nanoTime() - startedAtNanos) / NANOS_PER_MILLISECOND).coerceAtLeast(0)

private fun throughput(
    pages: Int,
    elapsedMillis: Long,
): Double = if (elapsedMillis == 0L) pages.toDouble() else pages * MILLIS_PER_SECOND / elapsedMillis
