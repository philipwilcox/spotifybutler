package com.philipwilcox.spotifybutler.service

import com.philipwilcox.spotifybutler.db.SpotifyStore
import com.philipwilcox.spotifybutler.spotify.PlaylistTrack
import com.philipwilcox.spotifybutler.spotify.SavedTrack
import com.philipwilcox.spotifybutler.spotify.SpotifyArtist
import com.philipwilcox.spotifybutler.spotify.SpotifyCacheSnapshot
import com.philipwilcox.spotifybutler.spotify.SpotifyPlaylist
import com.philipwilcox.spotifybutler.spotify.SpotifyTrack
import com.philipwilcox.spotifybutler.spotify.decodeStoredTrack
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

@Serializable
data class PlaylistQueryFixture(
    val schemaVersion: Int,
    val name: String,
    val source: String,
    val currentYear: Int,
    val minYearForDiscoverWeekly: Int,
    val seedTables: QueryFixtureSeedTables,
    val expectations: List<QueryFixtureExpectation>,
)

@Serializable
data class QueryFixtureSeedTables(
    @SerialName("saved_tracks") val savedTracks: List<QueryFixtureSavedTrack>,
    @SerialName("top_tracks") val topTracks: List<QueryFixtureTrack> = emptyList(),
    @SerialName("top_artists") val topArtists: List<QueryFixtureArtist> = emptyList(),
    val playlists: List<QueryFixturePlaylist> = emptyList(),
    @SerialName("playlist_tracks") val playlistTracks: List<QueryFixturePlaylistTrack> = emptyList(),
)

@Serializable
data class QueryFixtureTrack(
    val name: String,
    val id: String,
    val href: String,
    val uri: String,
    val releaseDate: String? = null,
    val primaryArtistId: String? = null,
    val trackJson: String,
)

@Serializable
data class QueryFixtureSavedTrack(
    val addedAt: String? = null,
    val track: QueryFixtureTrack,
)

@Serializable
data class QueryFixtureArtist(
    val name: String,
    val id: String,
    val href: String,
    val uri: String,
)

@Serializable
data class QueryFixturePlaylist(
    val name: String,
    val id: String,
    val href: String,
    val uri: String,
    val tracksHref: String,
)

@Serializable
data class QueryFixturePlaylistTrack(
    val playlistName: String,
    val addedAt: String? = null,
    val track: QueryFixtureTrack,
)

@Serializable
data class QueryFixtureExpectation(
    val definitionId: String,
    val playlistName: String,
    val exactDesiredUris: List<String>? = null,
    val selectionConstraints: QuerySelectionConstraints? = null,
    val existingPlaylist: QueryFixtureExistingPlaylist? = null,
    val existingUris: List<String> = emptyList(),
    val exactAlreadyPresentUris: List<String>? = null,
    val exactAddedUris: List<String>? = null,
    val exactRemovedUris: List<String>? = null,
)

@Serializable
data class QuerySelectionConstraints(
    val eligibleUris: List<String>,
    val expectedCount: Int,
    val maxPerPrimaryArtist: Int? = null,
)

@Serializable
data class QueryFixtureExistingPlaylist(
    val id: String,
)

val playlistQueryFixtureJson =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = true
    }

fun loadPlaylistQueryFixtures(resourceDirectory: Path): List<PlaylistQueryFixture> =
    buildList {
        Files.walk(resourceDirectory).use { paths ->
            paths.filter { it.toString().endsWith(".jsonl") }.sorted().forEach { path ->
                Files.readAllLines(path).filter(String::isNotBlank).forEach { line ->
                    add(playlistQueryFixtureJson.decodeFromString<PlaylistQueryFixture>(line))
                }
            }
        }
    }

fun PlaylistQueryFixture.validate() {
    require(schemaVersion == 1) { "Unsupported query fixture schema version $schemaVersion" }
    require(currentYear in 1900..2200) { "Query fixture current year is invalid" }
    require(minYearForDiscoverWeekly in 1900..currentYear) { "Query fixture Discover Weekly year is invalid" }
    val definitions = PlaylistQueries.definitions(currentYear, minYearForDiscoverWeekly)
    require(expectations.map { it.definitionId }.toSet().size == expectations.size) {
        "Query fixture contains duplicate definition expectations"
    }
    val definitionIds = expectations.map { it.definitionId }.toSet()
    require(definitionIds == definitions.map { it.id.name }.toSet()) {
        "Query fixture expectations do not match the playlist catalog"
    }
    val allStrings =
        buildList {
            add(source)
            addAll(
                seedTables.savedTracks.flatMap { listOf(it.track.id, it.track.uri, it.track.name, it.track.trackJson) },
            )
            addAll(expectations.flatMap { listOf(it.playlistName) + it.existingUris })
        }
    require(allStrings.none { it.matches(Regex("(?i)\\b[a-z0-9]{22}\\b")) }) {
        "Query fixture contains a raw Spotify-style identifier"
    }
    require(allStrings.none { it.contains("open.spotify.com") || it.contains("images.unsplash.com") }) {
        "Query fixture contains an unexpected personal media host"
    }
}

fun QueryFixtureSeedTables.toSnapshot(): SpotifyCacheSnapshot =
    SpotifyCacheSnapshot(
        savedTracks = savedTracks.map { SavedTrack(it.addedAt, it.track.toSpotifyTrack()) },
        topTracks = topTracks.map(QueryFixtureTrack::toSpotifyTrack),
        topArtists = topArtists.map { SpotifyArtist(it.name, it.id, it.href, it.uri) },
        playlists =
            playlists.map { SpotifyPlaylist(it.name, it.id, it.href, it.uri, it.tracksHref) },
        playlistTracks =
            playlistTracks.map {
                PlaylistTrack(it.playlistName, it.addedAt, it.track.toSpotifyTrack())
            },
    )

fun QueryFixtureTrack.toSpotifyTrack(): SpotifyTrack {
    val decoded = decodeStoredTrack(trackJson, "query fixture track $id")
    require(decoded.id == id && decoded.uri == uri) { "Query fixture track columns do not match track_json for $id" }
    return decoded
}

fun PlaylistQueryFixture.loadInto(store: SpotifyStore) {
    val snapshot = seedTables.toSnapshot()
    store.replaceCache(
        snapshot.copy(playlists = snapshot.playlists.map { it.copy(ownerId = "fixture-owner") }),
        syncTimestampMillis = 1L,
        ownerSpotifyUserId = "fixture-owner",
    )
}
