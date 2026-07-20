package com.philipwilcox.spotifybutler.spotify

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.net.HttpURLConnection
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

interface SpotifyCacheFetcher {
    fun fetchCache(accessToken: String): SpotifyCacheSnapshot
}

data class SpotifyHttpResponse(
    val statusCode: Int,
    val body: String,
)

fun interface SpotifyHttpTransport {
    fun get(
        uri: URI,
        accessToken: String,
    ): SpotifyHttpResponse

    fun post(
        uri: URI,
        accessToken: String,
        body: String,
    ): SpotifyHttpResponse = error("HTTP POST is not supported by this transport")

    fun put(
        uri: URI,
        accessToken: String,
        body: String,
    ): SpotifyHttpResponse = error("HTTP PUT is not supported by this transport")

    fun delete(
        uri: URI,
        accessToken: String,
    ): SpotifyHttpResponse = error("HTTP DELETE is not supported by this transport")
}

private class JdkSpotifyHttpTransport(
    private val httpClient: HttpClient,
) : SpotifyHttpTransport {
    override fun get(
        uri: URI,
        accessToken: String,
    ): SpotifyHttpResponse = request("GET", uri, accessToken, null)

    override fun post(
        uri: URI,
        accessToken: String,
        body: String,
    ): SpotifyHttpResponse = request("POST", uri, accessToken, body)

    override fun put(
        uri: URI,
        accessToken: String,
        body: String,
    ): SpotifyHttpResponse = request("PUT", uri, accessToken, body)

    override fun delete(
        uri: URI,
        accessToken: String,
    ): SpotifyHttpResponse = request("DELETE", uri, accessToken, null)

    private fun request(
        method: String,
        uri: URI,
        accessToken: String,
        body: String?,
    ): SpotifyHttpResponse {
        val builder =
            HttpRequest
                .newBuilder(uri)
                .header("Authorization", "Bearer $accessToken")
                .timeout(Duration.ofSeconds(20))
                .method(method, body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody())
        if (body != null) builder.header("Content-Type", "application/json")
        val request = builder.build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        return SpotifyHttpResponse(response.statusCode(), response.body())
    }
}

// This client intentionally owns the Spotify endpoint operations so batching and HTTP details stay at the API boundary.
@Suppress("TooManyFunctions")
class SpotifyApiClient(
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
    private val apiBaseUri: URI = URI("https://api.spotify.com/"),
    private val transport: SpotifyHttpTransport = JdkSpotifyHttpTransport(httpClient),
) : SpotifyCacheFetcher {
    private val logger = KotlinLogging.logger {}
    private val captureLogger = SpotifyCaptureLogger()

    fun getCurrentUser(accessToken: String): SpotifyCurrentUser {
        val response = getJsonObject(apiUri("/v1/me"), accessToken, pageSequence = 0)
        return parseSpotifyCurrentUser(response)
    }

    fun createPlaylist(
        accessToken: String,
        name: String,
    ): String {
        val body =
            buildJsonObject {
                put("name", name)
                put("public", false)
                put("collaborative", false)
                put("description", "Automatically generated playlist from Spotify Butler app")
            }.toString()
        val response = transport.post(apiUri("/v1/me/playlists"), accessToken, body)
        return parseMutationResponse(response, "create playlist").optionalString("id")
            ?: error("Spotify create playlist response did not contain id")
    }

    fun addTracks(
        accessToken: String,
        playlistId: String,
        tracks: List<SpotifyTrack>,
    ) {
        tracks.chunked(PLAYLIST_WRITE_BATCH_SIZE).forEach { batch ->
            val body = trackUrisBody(batch)
            requireMutationSuccess(
                transport.post(apiUri("/v1/playlists/$playlistId/items"), accessToken, body),
                "add tracks to playlist",
            )
        }
    }

    fun replaceTracks(
        accessToken: String,
        playlistId: String,
        tracks: List<SpotifyTrack>,
    ) {
        val batches = tracks.chunked(PLAYLIST_WRITE_BATCH_SIZE)
        val firstBatch = batches.firstOrNull().orEmpty()
        requireMutationSuccess(
            transport.put(apiUri("/v1/playlists/$playlistId/items"), accessToken, trackUrisBody(firstBatch)),
            "replace playlist tracks",
        )
        batches.drop(1).forEach { batch ->
            requireMutationSuccess(
                transport.post(apiUri("/v1/playlists/$playlistId/items"), accessToken, trackUrisBody(batch)),
                "append playlist tracks",
            )
        }
    }

    fun getPlaylistSnapshot(
        accessToken: String,
        playlistId: String,
    ): String? =
        getJsonObject(apiUri("/v1/playlists/$playlistId"), accessToken, pageSequence = 0)
            .optionalString("snapshot_id")

    fun replaceTrackIds(
        accessToken: String,
        playlistId: String,
        trackIds: List<String>,
    ): String? {
        val batches = trackIds.chunked(PLAYLIST_WRITE_BATCH_SIZE)
        val firstBatch = batches.firstOrNull().orEmpty()
        var snapshotId =
            replaceTrackUris(
                accessToken,
                playlistId,
                firstBatch.map(::trackUri),
                append = false,
            )
        batches.drop(1).forEach { batch ->
            snapshotId =
                replaceTrackUris(
                    accessToken,
                    playlistId,
                    batch.map(::trackUri),
                    append = true,
                ) ?: snapshotId
        }
        return snapshotId
    }

    fun removeSavedTracks(
        accessToken: String,
        trackIds: List<String>,
    ) {
        trackIds.distinct().chunked(SAVED_TRACK_WRITE_BATCH_SIZE).forEach { batch ->
            val uris =
                batch.joinToString(",") {
                    java.net.URLEncoder.encode("spotify:track:$it", Charsets.UTF_8)
                }
            requireMutationSuccess(
                transport.delete(apiUri("/v1/me/library?uris=$uris"), accessToken),
                "remove saved tracks",
            )
        }
    }

    override fun fetchCache(accessToken: String): SpotifyCacheSnapshot {
        logger.info { "Fetching Spotify collections for the local SQLite cache." }
        val savedTracksFuture =
            CompletableFuture.supplyAsync {
                pagedItems("/v1/me/tracks", accessToken).mapNotNull { item ->
                    parseSavedTrack(item) ?: run {
                        logger.warn { "Skipping a saved-track response item with no track object." }
                        null
                    }
                }
            }
        val topTracksFuture =
            CompletableFuture.supplyAsync {
                pagedItems("/v1/me/top/tracks", accessToken).map { parseSpotifyTrack(it, "top track") }
            }
        val topArtistsFuture =
            CompletableFuture.supplyAsync {
                pagedItems("/v1/me/top/artists", accessToken).map(::parseSpotifyArtist)
            }
        val playlistsFuture =
            CompletableFuture.supplyAsync {
                pagedItems("/v1/me/playlists", accessToken).map(::parseSpotifyPlaylist)
            }
        val savedTracks = savedTracksFuture.join()
        val topTracks = topTracksFuture.join()
        val topArtists = topArtistsFuture.join()
        val playlists = playlistsFuture.join()
        val playlistItems = fetchPlaylistItems(playlists, accessToken)
        val playlistTracks =
            playlistItems.mapNotNull { item ->
                item.track?.let { track -> PlaylistTrack(item.playlistName, item.addedAt, track) }
            }

        return SpotifyCacheSnapshot(savedTracks, topTracks, topArtists, playlists, playlistTracks, playlistItems)
    }

    private fun fetchPlaylistItems(
        playlists: List<SpotifyPlaylist>,
        accessToken: String,
    ): List<SpotifyPlaylistItem> {
        val executor = Executors.newFixedThreadPool(PLAYLIST_FETCH_CONCURRENCY)
        return try {
            playlists
                .map { playlist ->
                    CompletableFuture.supplyAsync(
                        { fetchPlaylistItems(playlist, accessToken) },
                        executor,
                    )
                }.flatMap { future -> future.join() }
        } finally {
            executor.shutdown()
        }
    }

    private fun fetchPlaylistItems(
        playlist: SpotifyPlaylist,
        accessToken: String,
    ): List<SpotifyPlaylistItem> {
        val items =
            pagedItemsWithPositions(playlist.tracksHref, accessToken).map { (position, item) ->
                parsePlaylistItem(item, playlist, position)
            }
        logger.info { "Fetched ${items.size} items for Spotify playlist ${playlist.name}." }
        return items
    }

    private fun pagedItems(
        path: String,
        accessToken: String,
    ): List<JsonObject> {
        val items = mutableListOf<JsonObject>()
        var page: URI? = firstPageUri(apiUri(path))
        var pageSequence = 1
        while (page != null) {
            val response = getJsonObject(page, accessToken, pageSequence)
            items += parsePageItems(response, page)
            page = response.optionalString("next")?.takeIf(String::isNotBlank)?.let(::apiUri)
            pageSequence++
        }
        return items
    }

    private fun pagedItemsWithPositions(
        path: String,
        accessToken: String,
    ): List<Pair<Int, JsonObject>> {
        val items = mutableListOf<Pair<Int, JsonObject>>()
        var page: URI? = firstPageUri(apiUri(path))
        var pageSequence = 1
        while (page != null) {
            val response = getJsonObject(page, accessToken, pageSequence)
            val offset = response.optionalLong("offset")?.toInt() ?: items.size
            items += parsePageItems(response, page).mapIndexed { index, item -> offset + index to item }
            page = response.optionalString("next")?.takeIf(String::isNotBlank)?.let(::apiUri)
            pageSequence++
        }
        return items
    }

    private fun getJsonObject(
        uri: URI,
        accessToken: String,
        pageSequence: Int,
    ): JsonObject {
        val response = transport.get(uri, accessToken)
        require(response.statusCode in HttpURLConnection.HTTP_OK until HttpURLConnection.HTTP_MULT_CHOICE) {
            "Spotify API request failed with HTTP ${response.statusCode} for $uri"
        }
        logger.info {
            "$SPOTIFY_CAPTURE_EVENT_MARKER ${captureLogger.successfulResponse(
                method = "GET",
                uri = uri,
                status = response.statusCode,
                pageSequence = pageSequence,
                body = response.body,
            )}"
        }
        return parseSpotifyResponse(response.body)
    }

    private fun parseMutationResponse(
        response: SpotifyHttpResponse,
        operation: String,
    ): JsonObject {
        requireMutationSuccess(response, operation)
        return parseSpotifyResponse(response.body)
    }

    private fun requireMutationSuccess(
        response: SpotifyHttpResponse,
        operation: String,
    ) {
        require(response.statusCode in HttpURLConnection.HTTP_OK until HttpURLConnection.HTTP_MULT_CHOICE) {
            "Spotify API request failed with HTTP ${response.statusCode} during $operation"
        }
    }

    private fun trackUrisBody(tracks: List<SpotifyTrack>): String = uriBody(tracks.map(SpotifyTrack::uri))

    private fun replaceTrackUris(
        accessToken: String,
        playlistId: String,
        uris: List<String>,
        append: Boolean,
    ): String? {
        val response =
            if (append) {
                transport.post(apiUri("/v1/playlists/$playlistId/items"), accessToken, uriBody(uris))
            } else {
                transport.put(apiUri("/v1/playlists/$playlistId/items"), accessToken, uriBody(uris))
            }
        return parseMutationResponse(response, if (append) "append playlist tracks" else "replace playlist tracks")
            .optionalString("snapshot_id")
    }

    private fun trackUri(trackId: String): String = "spotify:track:$trackId"

    private fun uriBody(uris: List<String>): String =
        buildJsonObject {
            putJsonArray("uris") { uris.forEach { add(JsonPrimitive(it)) } }
        }.toString()

    private fun apiUri(pathOrUri: String): URI {
        val candidate = URI(pathOrUri)
        val uri = if (candidate.isAbsolute) candidate else apiBaseUri.resolve(candidate)
        require(
            uri.scheme.equals(apiBaseUri.scheme, ignoreCase = true) &&
                uri.host.equals(apiBaseUri.host, ignoreCase = true),
        ) {
            "Spotify returned a pagination URL outside the configured API host: $uri"
        }
        return uri
    }

    private fun firstPageUri(endpoint: URI): URI {
        val separator = if (endpoint.rawQuery.isNullOrBlank()) "?" else "&"
        return URI("${endpoint.toASCIIString()}${separator}limit=$PAGE_SIZE&offset=0")
    }

    companion object {
        private const val PAGE_SIZE = 50
        private const val PLAYLIST_FETCH_CONCURRENCY = 2
        private const val PLAYLIST_WRITE_BATCH_SIZE = 100
        private const val SAVED_TRACK_WRITE_BATCH_SIZE = 40
    }
}
