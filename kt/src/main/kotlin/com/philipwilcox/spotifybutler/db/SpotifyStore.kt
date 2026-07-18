package com.philipwilcox.spotifybutler.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.philipwilcox.spotifybutler.spotify.PlaylistTrack
import com.philipwilcox.spotifybutler.spotify.SavedTrack
import com.philipwilcox.spotifybutler.spotify.SpotifyArtist
import com.philipwilcox.spotifybutler.spotify.SpotifyCacheSnapshot
import com.philipwilcox.spotifybutler.spotify.SpotifyPlaylist
import com.philipwilcox.spotifybutler.spotify.SpotifyTrack
import java.nio.file.Files
import java.nio.file.Path

class SpotifyStore private constructor(
    private val driver: SqlDriver,
    private val database: SpotifyDatabase,
) : AutoCloseable {
    private val queries = database.spotifyDatabaseQueries

    fun hasCompletedSync(): Boolean = queries.syncStatusCount().executeAsOne() > 0

    fun exportTables(): SpotifyTableSnapshot = database.exportTables(queries)

    fun replaceCache(
        snapshot: SpotifyCacheSnapshot,
        syncTimestampMillis: Long,
    ) {
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
