package com.philipwilcox.spotifybutler.spotify

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.JsonObject
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

class SpotifyApiClient(
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
    private val apiBaseUri: URI = URI("https://api.spotify.com/"),
) : SpotifyCacheFetcher {
    private val logger = KotlinLogging.logger {}

    fun getCurrentUser(accessToken: String): SpotifyCurrentUser {
        val response = getJsonObject(apiUri("/v1/me"), accessToken)
        return parseSpotifyCurrentUser(response)
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
        val playlistTracks = fetchPlaylistTracks(playlists, accessToken)

        return SpotifyCacheSnapshot(savedTracks, topTracks, topArtists, playlists, playlistTracks)
    }

    private fun fetchPlaylistTracks(
        playlists: List<SpotifyPlaylist>,
        accessToken: String,
    ): List<PlaylistTrack> {
        val executor = Executors.newFixedThreadPool(PLAYLIST_FETCH_CONCURRENCY)
        return try {
            playlists
                .map { playlist ->
                    CompletableFuture.supplyAsync(
                        { fetchPlaylistTracks(playlist, accessToken) },
                        executor,
                    )
                }.flatMap { future -> future.join() }
        } finally {
            executor.shutdown()
        }
    }

    private fun fetchPlaylistTracks(
        playlist: SpotifyPlaylist,
        accessToken: String,
    ): List<PlaylistTrack> {
        val tracks =
            pagedItems(playlist.tracksHref, accessToken).mapNotNull { item ->
                parsePlaylistTrack(item, playlist.name) ?: run {
                    logger.warn { "Skipping a playlist-track response item with no track object for ${playlist.name}." }
                    null
                }
            }
        logger.info { "Fetched ${tracks.size} tracks for Spotify playlist ${playlist.name}." }
        return tracks
    }

    private fun pagedItems(
        path: String,
        accessToken: String,
    ): List<JsonObject> {
        val items = mutableListOf<JsonObject>()
        var page: URI? = firstPageUri(apiUri(path))
        while (page != null) {
            val response = getJsonObject(page, accessToken)
            items += parsePageItems(response, page)
            page = response.optionalString("next")?.takeIf(String::isNotBlank)?.let(::apiUri)
        }
        return items
    }

    private fun getJsonObject(
        uri: URI,
        accessToken: String,
    ): JsonObject {
        val request =
            HttpRequest
                .newBuilder(uri)
                .header("Authorization", "Bearer $accessToken")
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        logger.info {
            "Spotify API scraped response: method=GET uri=$uri status=${response.statusCode()} body=${response.body()}"
        }
        require(response.statusCode() in HttpURLConnection.HTTP_OK until HttpURLConnection.HTTP_MULT_CHOICE) {
            "Spotify API request failed with HTTP ${response.statusCode()} for $uri"
        }
        return parseSpotifyResponse(response.body())
    }

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
    }
}
