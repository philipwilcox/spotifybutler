package com.philipwilcox.spotifybutler.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.philipwilcox.spotifybutler.service.PlaylistQuery
import com.philipwilcox.spotifybutler.spotify.PlaylistTrack
import com.philipwilcox.spotifybutler.spotify.SavedTrack
import com.philipwilcox.spotifybutler.spotify.SpotifyArtist
import com.philipwilcox.spotifybutler.spotify.SpotifyCacheSnapshot
import com.philipwilcox.spotifybutler.spotify.SpotifyPlaylist
import com.philipwilcox.spotifybutler.spotify.SpotifyTrack
import com.philipwilcox.spotifybutler.spotify.decodeStoredTrack
import java.nio.file.Files
import java.nio.file.Path

@Suppress("TooManyFunctions")
class SpotifyStore private constructor(
    private val driver: SqlDriver,
    private val database: SpotifyDatabase,
) : AutoCloseable {
    private val queries = database.spotifyDatabaseQueries

    fun hasCompletedSync(): Boolean = queries.syncStatusCount().executeAsOne() > 0

    fun duplicateSavedTrackIds(): List<String> =
        queries
            .selectDuplicateSavedTrackRows()
            .executeAsList()
            .groupBy { it.primary_artist_id to it.name }
            .values
            .flatMap { rows -> rows.drop(1).mapNotNull { it.id } }

    fun deleteSavedTracks(trackIds: List<String>) {
        database.transaction {
            trackIds.distinct().forEach(queries::deleteSavedTracksById)
        }
    }

    fun exportTables(): SpotifyTableSnapshot = database.exportTables(queries)

    fun execute(query: PlaylistQuery): List<SpotifyTrack> =
        storedRows(query).map { row ->
            decodeStoredTrack(
                row.trackJson,
                "query row id=${row.id} uri=${row.uri}",
            ).also { track ->
                require(track.id == row.id) { "Stored track id does not match query row id=${row.id}" }
                require(track.uri == row.uri) { "Stored track uri does not match query row id=${row.id}" }
            }
        }

    fun findPlaylistByName(name: String): ExistingPlaylistMetadata? {
        val matches = queries.selectPlaylistByName(name).executeAsList()
        require(matches.size <= 1) { "Multiple cached playlists have the generated name $name" }
        return matches.singleOrNull()?.let {
            ExistingPlaylistMetadata(
                requireNotNull(it.id) { "Cached playlist $name has no id" },
                it.snapshot_id,
            )
        }
    }

    fun findPlaylistTracksByName(name: String): List<SpotifyTrack> =
        queries.selectPlaylistTracksByName(name).executeAsList().mapIndexed { index, row ->
            decodeStoredTrack(
                requireNotNull(row.track_json) { "Cached playlist $name row $index has no track_json" },
                "playlist $name row $index",
            ).also { track ->
                require(track.id == row.id) { "Cached playlist $name row $index has mismatched track id" }
                require(track.uri == row.uri) { "Cached playlist $name row $index has mismatched track uri" }
            }
        }

    fun replaceCache(
        snapshot: SpotifyCacheSnapshot,
        syncTimestampMillis: Long,
    ) {
        replaceCacheContent(snapshot)
        markSyncComplete(syncTimestampMillis)
    }

    fun replaceCacheContent(snapshot: SpotifyCacheSnapshot) {
        database.transaction {
            queries.clearTopArtists()
            queries.clearTopTracks()
            queries.clearSavedTracks()
            queries.clearPlaylists()
            queries.clearPlaylistTracks()
            snapshot.savedTracks.forEach(::insertSavedTrack)
            snapshot.topTracks.forEach(::insertTopTrack)
            snapshot.topArtists.forEach(::insertTopArtist)
            snapshot.playlists.forEach(::insertPlaylist)
            snapshot.playlistTracks.forEach(::insertPlaylistTrack)
            queries.clearSyncStatus()
        }
    }

    fun markSyncComplete(syncTimestampMillis: Long) {
        database.transaction {
            queries.clearSyncStatus()
            queries.insertSyncStatus(syncTimestampMillis)
        }
    }

    override fun close() {
        driver.close()
    }

    private fun insertSavedTrack(savedTrack: SavedTrack) {
        savedTrack.track.let { track ->
            queries.insertSavedTrack(
                name = track.name,
                id = track.id,
                primary_artist_id = track.primaryArtistId,
                release_date = track.releaseDate,
                release_year = track.releaseDate.releaseYear(),
                href = track.href,
                uri = track.uri,
                added_at = savedTrack.addedAt,
                track_json = track.rawJson,
            )
        }
    }

    private fun insertTopTrack(track: SpotifyTrack) {
        queries.insertTopTrack(track.name, track.id, track.href, track.uri, track.rawJson)
    }

    private fun insertTopArtist(artist: SpotifyArtist) {
        queries.insertTopArtist(artist.name, artist.id, artist.href, artist.uri)
    }

    private fun insertPlaylist(playlist: SpotifyPlaylist) {
        queries.insertPlaylist(
            name = playlist.name,
            id = playlist.id,
            href = playlist.href,
            uri = playlist.uri,
            tracks_href = playlist.tracksHref,
            snapshot_id = playlist.snapshotId,
        )
    }

    private fun insertPlaylistTrack(playlistTrack: PlaylistTrack) {
        playlistTrack.track.let { track ->
            queries.insertPlaylistTrack(
                playlist_name = playlistTrack.playlistName,
                added_at = playlistTrack.addedAt,
                release_date = track.releaseDate,
                release_year = track.releaseDate.releaseYear(),
                name = track.name,
                primary_artist_id = track.primaryArtistId,
                id = track.id,
                href = track.href,
                uri = track.uri,
                track_json = track.rawJson,
            )
        }
    }

    private fun storedRows(query: PlaylistQuery): List<StoredTrackRow> =
        when (query) {
            is PlaylistQuery.RecentLiked ->
                queries.selectRecentLikedTracks(query.limit).executeAsList().map {
                    it.toStoredTrackRow()
                }
            is PlaylistQuery.RandomLiked ->
                queries.selectRandomLikedTracks(query.limit).executeAsList().map {
                    it.toStoredTrackRow()
                }
            is PlaylistQuery.CollectedDiscoverWeekly ->
                queries
                    .selectCollectedDiscoverWeekly(
                        query.collectedName,
                        query.sourceName,
                        query.minReleaseYear,
                        query.collectedName,
                    ).executeAsList()
                    .map { it.toStoredTrackRow() }
            is PlaylistQuery.SavedPerArtist ->
                queries.selectSavedTracksPerArtist(query.limit).executeAsList().map {
                    it.toStoredTrackRow()
                }
            is PlaylistQuery.SavedInYearRangePerArtist ->
                queries
                    .selectSavedTracksInYearRangePerArtist(query.minYear, query.maxYear, query.limit)
                    .executeAsList()
                    .map { it.toStoredTrackRow() }
            is PlaylistQuery.SavedThroughYearPerArtist ->
                queries
                    .selectSavedTracksThroughYearPerArtist(
                        query.maxYear,
                        query.limit,
                    ).executeAsList()
                    .map { it.toStoredTrackRow() }
            is PlaylistQuery.SavedSinceYearPerArtist ->
                queries
                    .selectSavedTracksSinceYearPerArtist(
                        query.minYear,
                        query.limit,
                    ).executeAsList()
                    .map { it.toStoredTrackRow() }
            PlaylistQuery.SavedNotByTopArtists ->
                queries.selectSavedTracksNotByTopArtists().executeAsList().map {
                    it.toStoredTrackRow()
                }
            PlaylistQuery.SavedNotInTopTracks ->
                queries.selectSavedTracksNotInTopTracks().executeAsList().map {
                    it.toStoredTrackRow()
                }
            is PlaylistQuery.SavedInYearRange ->
                queries
                    .selectSavedTracksInYearRange(
                        query.minYearInclusive,
                        query.maxYearExclusive,
                    ).executeAsList()
                    .map {
                        it.toStoredTrackRow()
                    }
        }

    private fun String?.releaseYear(): Long? = this?.substringBefore('-')?.toLongOrNull()

    companion object {
        fun open(databasePath: Path): SpotifyStore {
            val absolutePath = databasePath.toAbsolutePath().normalize()
            val newDatabase = Files.notExists(absolutePath)
            absolutePath.parent?.let(Files::createDirectories)
            val driver = JdbcSqliteDriver("jdbc:sqlite:$absolutePath")
            if (newDatabase) SpotifyDatabase.Schema.create(driver)
            return SpotifyStore(driver, SpotifyDatabase(driver))
        }

        fun openReadOnly(databasePath: Path): SpotifyStore {
            val absolutePath = databasePath.toAbsolutePath().normalize()
            require(Files.isRegularFile(absolutePath)) { "SQLite database not found at $absolutePath" }
            val driver = JdbcSqliteDriver("jdbc:sqlite:${absolutePath.toUri()}?mode=ro")
            return SpotifyStore(driver, SpotifyDatabase(driver))
        }
    }
}

data class ExistingPlaylistMetadata(
    val id: String,
    val snapshotId: String?,
)

private data class StoredTrackRow(
    val id: String,
    val uri: String,
    val primaryArtistId: String?,
    val trackJson: String,
)

private fun SelectRecentLikedTracks.toStoredTrackRow() =
    StoredTrackRow(requireNotNull(id), requireNotNull(uri), primary_artist_id, requireNotNull(track_json))

private fun SelectRandomLikedTracks.toStoredTrackRow() =
    StoredTrackRow(requireNotNull(id), requireNotNull(uri), primary_artist_id, requireNotNull(track_json))

private fun SelectCollectedDiscoverWeekly.toStoredTrackRow() =
    StoredTrackRow(requireNotNull(id), requireNotNull(uri), primary_artist_id, requireNotNull(track_json))

private fun SelectSavedTracksPerArtist.toStoredTrackRow() =
    StoredTrackRow(requireNotNull(id), requireNotNull(uri), primary_artist_id, requireNotNull(track_json))

private fun SelectSavedTracksInYearRangePerArtist.toStoredTrackRow() =
    StoredTrackRow(requireNotNull(id), requireNotNull(uri), primary_artist_id, requireNotNull(track_json))

private fun SelectSavedTracksThroughYearPerArtist.toStoredTrackRow() =
    StoredTrackRow(requireNotNull(id), requireNotNull(uri), primary_artist_id, requireNotNull(track_json))

private fun SelectSavedTracksSinceYearPerArtist.toStoredTrackRow() =
    StoredTrackRow(requireNotNull(id), requireNotNull(uri), primary_artist_id, requireNotNull(track_json))

private fun SelectSavedTracksNotByTopArtists.toStoredTrackRow() =
    StoredTrackRow(requireNotNull(id), requireNotNull(uri), primary_artist_id, requireNotNull(track_json))

private fun SelectSavedTracksNotInTopTracks.toStoredTrackRow() =
    StoredTrackRow(requireNotNull(id), requireNotNull(uri), primary_artist_id, requireNotNull(track_json))

private fun SelectSavedTracksInYearRange.toStoredTrackRow() =
    StoredTrackRow(requireNotNull(id), requireNotNull(uri), primary_artist_id, requireNotNull(track_json))
