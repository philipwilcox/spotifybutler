package com.philipwilcox.spotifybutler.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.philipwilcox.spotifybutler.service.CandidateSource
import com.philipwilcox.spotifybutler.service.CandidateTrack
import com.philipwilcox.spotifybutler.service.PlaylistQuery
import com.philipwilcox.spotifybutler.service.PlaylistRecipeEngine
import com.philipwilcox.spotifybutler.service.RecipeExecutionContext
import com.philipwilcox.spotifybutler.spotify.PlaylistTrack
import com.philipwilcox.spotifybutler.spotify.SavedTrack
import com.philipwilcox.spotifybutler.spotify.SpotifyArtist
import com.philipwilcox.spotifybutler.spotify.SpotifyCacheSnapshot
import com.philipwilcox.spotifybutler.spotify.SpotifyPlaylist
import com.philipwilcox.spotifybutler.spotify.SpotifyPlaylistItem
import com.philipwilcox.spotifybutler.spotify.SpotifyTrack
import com.philipwilcox.spotifybutler.spotify.decodeStoredTrack
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

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

    fun cacheMetadata(): CacheMetadata? =
        queries.selectCacheMetadata().executeAsOneOrNull()?.let {
            CacheMetadata(
                revision = it.cache_revision,
                syncTimestampMillis = it.sync_timestamp_millis,
                ownerSpotifyUserId = it.owner_spotify_user_id,
                completionState = it.completion_state,
            )
        }

    fun userPlaylistDefinitions(ownerSpotifyUserId: String): List<StoredUserPlaylistDefinition> =
        queries.selectUserPlaylistDefinitionsByOwner(ownerSpotifyUserId).executeAsList().map { row ->
            StoredUserPlaylistDefinition(
                id = requireNotNull(row.id),
                ownerSpotifyUserId = requireNotNull(row.owner_spotify_user_id),
                name = requireNotNull(row.name),
                trackIds = userPlaylistTrackIds(requireNotNull(row.id)),
            )
        }

    fun userPlaylistDefinition(
        id: String,
        ownerSpotifyUserId: String,
    ): StoredUserPlaylistDefinition? =
        queries.selectUserPlaylistDefinition(id, ownerSpotifyUserId).executeAsOneOrNull()?.let { row ->
            StoredUserPlaylistDefinition(
                id = requireNotNull(row.id),
                ownerSpotifyUserId = requireNotNull(row.owner_spotify_user_id),
                name = requireNotNull(row.name),
                trackIds = userPlaylistTrackIds(id),
            )
        }

    fun saveUserPlaylistDefinition(definition: StoredUserPlaylistDefinition) {
        database.transaction {
            queries.upsertUserPlaylistDefinition(
                definition.id,
                definition.ownerSpotifyUserId,
                definition.name,
            )
            queries.deleteUserPlaylistDefinitionItems(definition.id)
            definition.trackIds.forEachIndexed { position, trackId ->
                queries.insertUserPlaylistDefinitionItem(definition.id, position.toLong(), trackId)
            }
        }
    }

    fun playlistItems(playlistId: String): List<StoredPlaylistItem> =
        queries.selectPlaylistItemsById(playlistId).executeAsList().map { row ->
            StoredPlaylistItem(
                playlistId = requireNotNull(row.playlist_id),
                position = row.position.toInt(),
                addedAt = row.added_at,
                addedById = row.added_by_id,
                isLocal = row.is_local != 0L,
                itemType = row.item_type,
                isPlayable = row.is_playable != 0L,
                itemId = row.item_id,
                itemUri = row.item_uri,
                status = row.status,
            )
        }

    fun songs(): List<SpotifyTrack> =
        queries.selectAllSongs().executeAsList().map { row ->
            decodeStoredTrack(requireNotNull(row.track_json), "song ${row.id}")
        }

    fun song(id: String): SpotifyTrack? = songs().firstOrNull { it.id == id }

    fun songEnrichment(id: String): StoredSong? = songEnrichment(listOf(id)).singleOrNull()

    fun songEnrichment(ids: List<String>): List<StoredSong> {
        if (ids.isEmpty()) return emptyList()
        val distinctIds = ids.distinct()
        val byId = queries.selectSongsByIds(distinctIds).executeAsList().associateBy { requireNotNull(it.id) }
        return ids.mapNotNull { id -> byId[id]?.toStoredSong() }
    }

    fun playlistDetails(playlistId: String): StoredPlaylistDetails? =
        queries.selectPlaylistDetailsById(playlistId).executeAsOneOrNull()?.let { row ->
            StoredPlaylistDetails(
                playlistId = requireNotNull(row.playlist_id),
                description = row.description,
                public = row.is_public?.toBooleanFlag(),
                collaborative = row.collaborative?.toBooleanFlag(),
                ownerId = row.owner_id,
                itemCount = row.item_count?.toInt(),
                displayUrl = row.display_url,
            )
        }

    fun playlistIdByName(
        name: String,
        ownerSpotifyUserId: String,
    ): String? = findPlaylistByName(name, ownerSpotifyUserId)?.id

    fun playlistMatchesByName(name: String): List<ExistingPlaylistMetadata> =
        queries.selectPlaylistByName(name).executeAsList().map {
            ExistingPlaylistMetadata(requireNotNull(it.id) { "Cached playlist $name has no id" })
        }

    fun managedPlaylist(
        definitionId: String,
        ownerSpotifyUserId: String,
    ): ManagedPlaylist? =
        queries.selectManagedPlaylist(definitionId, ownerSpotifyUserId).executeAsOneOrNull()?.let {
            ManagedPlaylist(
                definitionId = requireNotNull(it.definition_id),
                spotifyPlaylistId = requireNotNull(it.spotify_playlist_id),
                ownerSpotifyUserId = requireNotNull(it.owner_spotify_user_id),
            )
        }

    fun saveManagedPlaylist(
        definitionId: String,
        spotifyPlaylistId: String,
        ownerSpotifyUserId: String,
    ) {
        queries.insertManagedPlaylist(definitionId, spotifyPlaylistId, ownerSpotifyUserId)
    }

    fun publishPlaylistTrackIds(
        playlistId: String,
        trackIds: List<String>,
        syncTimestampMillis: Long,
    ) {
        database.transaction {
            queries.clearPlaylistItemsById(playlistId)
            trackIds.forEachIndexed { position, trackId ->
                val track = song(trackId) ?: error("Cannot publish unknown track $trackId")
                queries.insertPlaylistItem(
                    playlist_id = playlistId,
                    position = position.toLong(),
                    added_at = null,
                    added_by_id = null,
                    is_local = 0L,
                    item_type = "track",
                    is_playable = 1L,
                    item_id = track.id,
                    item_uri = track.uri,
                    status = "playable",
                    complete_item_json = "{\"item\":${track.rawJson}}",
                )
            }
            queries.updatePlaylistItemCount(trackIds.size.toLong(), playlistId)
            queries.updateCacheMetadata(newCacheRevision(), syncTimestampMillis, CACHE_READY)
        }
    }

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

    fun candidates(source: CandidateSource): List<CandidateTrack> =
        when (source) {
            CandidateSource.SavedTracks ->
                queries.selectAllSavedCandidates().executeAsList().mapIndexed { index, row ->
                    CandidateTrack(
                        decodeStoredTrack(requireNotNull(row.track_json), "saved candidate $index"),
                        row.added_at,
                        index,
                    )
                }
            CandidateSource.TopTracks ->
                queries.selectAllTopCandidates().executeAsList().mapIndexed { index, row ->
                    CandidateTrack(
                        decodeStoredTrack(requireNotNull(row.track_json), "top candidate $index"),
                        null,
                        index,
                    )
                }
            is CandidateSource.PlaylistItems ->
                queries.selectPlaylistCandidatesByName(source.playlistName).executeAsList().mapIndexed { index, row ->
                    CandidateTrack(
                        decodeStoredTrack(requireNotNull(row.track_json), "playlist candidate $index"),
                        row.added_at,
                        index,
                    )
                }
            is CandidateSource.Union -> source.sources.flatMap { candidates(it) }
            is CandidateSource.Difference -> {
                val excluded = candidates(source.right).mapTo(mutableSetOf(), CandidateTrack::identity)
                candidates(source.left).filterNot { it.identity in excluded }
            }
            is CandidateSource.Filtered ->
                candidates(source.source).filter { candidate ->
                    PlaylistRecipeEngine.matches(source.predicate, candidate)
                }
        }

    fun recipeExecutionContext(): RecipeExecutionContext =
        RecipeExecutionContext(
            topArtistIds =
                queries
                    .selectTopArtistIds()
                    .executeAsList()
                    .mapNotNull { it.id }
                    .toSet(),
            topTrackIds =
                queries
                    .selectTopTrackIds()
                    .executeAsList()
                    .mapNotNull { it.id }
                    .toSet(),
        )

    fun findPlaylistByName(
        name: String,
        ownerSpotifyUserId: String,
    ): ExistingPlaylistMetadata? {
        val matches = queries.selectPlaylistByNameAndOwner(name, ownerSpotifyUserId).executeAsList()
        require(matches.size <= 1) { "Multiple cached playlists have the generated name $name" }
        return matches.singleOrNull()?.let {
            ExistingPlaylistMetadata(requireNotNull(it.id) { "Cached playlist $name has no id" })
        }
    }

    fun findPlaylistTracksByName(
        name: String,
        ownerSpotifyUserId: String,
    ): List<SpotifyTrack> =
        queries.selectPlaylistTracksByNameAndOwner(name, ownerSpotifyUserId).executeAsList().mapIndexed { index, row ->
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
        ownerSpotifyUserId: String? = null,
    ) {
        database.transaction {
            replaceCacheContentInTransaction(snapshot)
            queries.clearSyncStatus()
            queries.insertSyncStatus(syncTimestampMillis)
            queries.clearCacheMetadata()
            queries.insertCacheMetadata(
                cache_revision = newCacheRevision(),
                sync_timestamp_millis = syncTimestampMillis,
                owner_spotify_user_id = ownerSpotifyUserId,
                completion_state = CACHE_READY,
            )
        }
    }

    fun replaceCacheContent(snapshot: SpotifyCacheSnapshot) {
        database.transaction {
            replaceCacheContentInTransaction(snapshot)
            queries.clearCacheMetadata()
        }
    }

    fun markSyncComplete(syncTimestampMillis: Long) {
        database.transaction {
            queries.clearSyncStatus()
            queries.insertSyncStatus(syncTimestampMillis)
            queries.clearCacheMetadata()
            queries.insertCacheMetadata(
                cache_revision = newCacheRevision(),
                sync_timestamp_millis = syncTimestampMillis,
                owner_spotify_user_id = null,
                completion_state = CACHE_READY,
            )
        }
    }

    fun markCacheRefreshing(ownerSpotifyUserId: String) {
        database.transaction {
            val current = cacheMetadata()
            queries.clearCacheMetadata()
            queries.insertCacheMetadata(
                cache_revision = current?.revision ?: newCacheRevision(),
                sync_timestamp_millis = current?.syncTimestampMillis ?: 0L,
                owner_spotify_user_id = current?.ownerSpotifyUserId ?: ownerSpotifyUserId,
                completion_state = CACHE_REFRESHING,
            )
        }
    }

    fun markCacheStale() {
        database.transaction {
            val current = cacheMetadata() ?: return@transaction
            queries.clearCacheMetadata()
            queries.insertCacheMetadata(
                current.revision,
                current.syncTimestampMillis,
                current.ownerSpotifyUserId,
                CACHE_STALE,
            )
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
        )
        queries.insertPlaylistDetails(
            playlist_id = playlist.id,
            description = playlist.description,
            is_public = playlist.public?.toLongFlag(),
            collaborative = playlist.collaborative?.toLongFlag(),
            owner_id = playlist.ownerId,
            item_count = playlist.itemCount?.toLong(),
            display_url = playlist.displayUrl,
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

    private fun insertPlaylistItem(item: SpotifyPlaylistItem) {
        queries.insertPlaylistItem(
            playlist_id = item.playlistId,
            position = item.position.toLong(),
            added_at = item.addedAt,
            added_by_id = item.addedById,
            is_local = if (item.isLocal) 1L else 0L,
            item_type = item.itemType,
            is_playable = if (item.isPlayable) 1L else 0L,
            item_id = item.itemId,
            item_uri = item.itemUri,
            status = item.status,
            complete_item_json = item.rawJson,
        )
    }

    private fun insertSong(track: SpotifyTrack) {
        queries.insertSong(
            id = track.id,
            name = track.name,
            href = track.href,
            uri = track.uri,
            album_id = track.albumId,
            album_name = track.albumName,
            album_href = track.albumHref,
            album_uri = track.albumUri,
            release_date = track.releaseDate,
            duration_ms = track.durationMs,
            explicit = track.explicit?.let { if (it) 1L else 0L },
            available = if (track.available) 1L else 0L,
            track_json = track.rawJson,
        )
        track.artists.forEachIndexed { position, artist ->
            queries.insertSongArtist(
                track_id = track.id,
                position = position.toLong(),
                artist_id = artist.id,
                name = artist.name,
                href = artist.href,
                uri = artist.uri,
            )
        }
    }

    private fun replaceCacheContentInTransaction(snapshot: SpotifyCacheSnapshot) {
        queries.clearTopArtists()
        queries.clearTopTracks()
        queries.clearSavedTracks()
        queries.clearPlaylists()
        queries.clearPlaylistTracks()
        queries.clearPlaylistDetails()
        queries.clearPlaylistItems()
        queries.clearSongs()
        queries.clearSongArtists()
        snapshot.savedTracks.forEach(::insertSavedTrack)
        snapshot.topTracks.forEach(::insertTopTrack)
        snapshot.topArtists.forEach(::insertTopArtist)
        snapshot.playlists.forEach(::insertPlaylist)
        snapshot.playlistTracks.forEach(::insertPlaylistTrack)
        val playlistItems =
            if (snapshot.playlistItems.isNotEmpty()) {
                snapshot.playlistItems
            } else {
                snapshot.playlistTracks.groupBy(PlaylistTrack::playlistName).flatMap { (playlistName, items) ->
                    val playlistId =
                        requireNotNull(snapshot.playlists.firstOrNull { it.name == playlistName }?.id) {
                            "Playlist $playlistName is missing from cache snapshot"
                        }
                    items.mapIndexed { position, item ->
                        SpotifyPlaylistItem(
                            playlistId = playlistId,
                            playlistName = item.playlistName,
                            position = position,
                            addedAt = item.addedAt,
                            addedById = null,
                            isLocal = false,
                            itemType = "track",
                            isPlayable = true,
                            itemId = item.track.id,
                            itemUri = item.track.uri,
                            status = "playable",
                            rawJson = "{\"track\":${item.track.rawJson}}",
                            track = item.track,
                        )
                    }
                }
            }
        playlistItems.forEach(::insertPlaylistItem)
        val tracks =
            (
                snapshot.savedTracks.map(SavedTrack::track) +
                    snapshot.topTracks +
                    snapshot.playlistTracks.map(PlaylistTrack::track) +
                    playlistItems.mapNotNull(SpotifyPlaylistItem::track)
            ).distinctBy(SpotifyTrack::id)
        tracks.forEach(::insertSong)
        queries.clearSyncStatus()
    }

    private fun newCacheRevision(): String = "cache-${UUID.randomUUID()}"

    private fun userPlaylistTrackIds(definitionId: String): List<String> =
        queries.selectUserPlaylistDefinitionItems(definitionId).executeAsList().map { requireNotNull(it.track_id) }

    private fun Songs.toStoredSong(): StoredSong =
        StoredSong(
            id = requireNotNull(id),
            name = requireNotNull(name),
            href = requireNotNull(href),
            uri = requireNotNull(uri),
            albumId = album_id,
            albumName = album_name,
            albumHref = album_href,
            albumUri = album_uri,
            releaseDate = release_date,
            durationMs = duration_ms,
            explicit = explicit?.toBooleanFlag(),
            available = available != 0L,
            artists =
                queries.selectSongArtists(requireNotNull(id)).executeAsList().map { artist ->
                    StoredArtist(artist.artist_id, artist.name, artist.href, artist.uri)
                },
        )

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

    private fun Long.toBooleanFlag(): Boolean = this != 0L

    private fun Boolean.toLongFlag(): Long = if (this) 1L else 0L

    companion object {
        private const val CACHE_READY = "ready"
        private const val CACHE_REFRESHING = "refreshing"
        private const val CACHE_STALE = "stale"

        fun open(databasePath: Path): SpotifyStore {
            val absolutePath = databasePath.toAbsolutePath().normalize()
            val newDatabase = Files.notExists(absolutePath)
            absolutePath.parent?.let(Files::createDirectories)
            val driver = JdbcSqliteDriver("jdbc:sqlite:$absolutePath")
            if (newDatabase) {
                SpotifyDatabase.Schema.create(driver)
            }
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

data class CacheMetadata(
    val revision: String,
    val syncTimestampMillis: Long,
    val ownerSpotifyUserId: String?,
    val completionState: String,
)

data class StoredUserPlaylistDefinition(
    val id: String,
    val ownerSpotifyUserId: String,
    val name: String,
    val trackIds: List<String>,
)

data class StoredSong(
    val id: String,
    val name: String,
    val href: String,
    val uri: String,
    val albumId: String?,
    val albumName: String?,
    val albumHref: String?,
    val albumUri: String?,
    val releaseDate: String?,
    val durationMs: Long?,
    val explicit: Boolean?,
    val available: Boolean,
    val artists: List<StoredArtist>,
)

data class StoredArtist(
    val id: String?,
    val name: String?,
    val href: String?,
    val uri: String?,
)

data class StoredPlaylistItem(
    val playlistId: String,
    val position: Int,
    val addedAt: String?,
    val addedById: String?,
    val isLocal: Boolean,
    val itemType: String?,
    val isPlayable: Boolean,
    val itemId: String?,
    val itemUri: String?,
    val status: String,
)

data class StoredPlaylistDetails(
    val playlistId: String,
    val description: String?,
    val public: Boolean?,
    val collaborative: Boolean?,
    val ownerId: String?,
    val itemCount: Int?,
    val displayUrl: String?,
)

data class ManagedPlaylist(
    val definitionId: String,
    val spotifyPlaylistId: String,
    val ownerSpotifyUserId: String,
)

data class ExistingPlaylistMetadata(
    val id: String,
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
