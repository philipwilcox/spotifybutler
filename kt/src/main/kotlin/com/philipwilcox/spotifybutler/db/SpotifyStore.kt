package com.philipwilcox.spotifybutler.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.philipwilcox.spotifybutler.service.CacheResourceKind
import com.philipwilcox.spotifybutler.service.CacheSourceKey
import com.philipwilcox.spotifybutler.service.CacheSourceSnapshot
import com.philipwilcox.spotifybutler.service.CacheSourceStatus
import com.philipwilcox.spotifybutler.service.CandidateSource
import com.philipwilcox.spotifybutler.service.CandidateTrack
import com.philipwilcox.spotifybutler.service.PlaylistQuery
import com.philipwilcox.spotifybutler.service.PlaylistRecipe
import com.philipwilcox.spotifybutler.service.PlaylistRecipeCodec
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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

@Suppress(
    "TooManyFunctions",
    "LongMethod",
    "CyclomaticComplexMethod",
    "LargeClass",
    "TooGenericExceptionCaught",
)
class SpotifyStore private constructor(
    private val driver: SqlDriver,
    private val connection: Connection,
) : AutoCloseable {
    fun schemaVersion(): Int =
        queryOne("SELECT version FROM schema_version WHERE singleton_id = 1") { it.getInt(1) } ?: 0

    fun exportTables(): SpotifyTableSnapshot =
        SpotifyTableSnapshot(
            savedTracks =
                rows(
                    "SELECT * FROM saved_tracks ORDER BY owner_spotify_user_id, source_position",
                ) { savedRow(it) },
            topTracks =
                rows(
                    "SELECT * FROM top_tracks ORDER BY owner_spotify_user_id, source_position",
                ) { topRow(it) },
            topArtists =
                rows(
                    "SELECT * FROM top_artists ORDER BY owner_spotify_user_id, source_position",
                ) { artistRow(it) },
            playlists =
                rows(
                    "SELECT * FROM playlists ORDER BY owner_spotify_user_id, source_position",
                ) { playlistRow(it) },
            playlistTracks =
                rows("SELECT * FROM playlist_tracks ORDER BY owner_spotify_user_id, playlist_id, position") {
                    playlistTrackRow(it)
                },
            syncStatus = emptyList(),
            cacheMetadata = emptyList(),
            playlistDetails =
                rows("SELECT * FROM playlist_details ORDER BY owner_spotify_user_id, playlist_id") {
                    jsonRow(
                        "owner_spotify_user_id" to it.getString("owner_spotify_user_id"),
                        "playlist_id" to it.getString("playlist_id"),
                        "description" to it.getString("description"),
                        "is_public" to it.getObject("is_public"),
                        "collaborative" to it.getObject("collaborative"),
                        "owner_id" to it.getString("owner_id"),
                        "item_count" to it.getObject("item_count"),
                        "display_url" to it.getString("display_url"),
                    )
                },
            playlistItems =
                rows("SELECT * FROM playlist_items ORDER BY owner_spotify_user_id, playlist_id, position") {
                    jsonRow(
                        "owner_spotify_user_id" to it.getString("owner_spotify_user_id"),
                        "playlist_id" to it.getString("playlist_id"),
                        "position" to it.getLong("position"),
                        "added_at" to it.getString("added_at"),
                        "added_by_id" to it.getString("added_by_id"),
                        "is_local" to it.getLong("is_local"),
                        "item_type" to it.getString("item_type"),
                        "is_playable" to it.getLong("is_playable"),
                        "item_id" to it.getString("item_id"),
                        "item_uri" to it.getString("item_uri"),
                        "status" to it.getString("status"),
                        "complete_item_json" to it.getString("complete_item_json"),
                    )
                },
            songs = rows("SELECT * FROM songs ORDER BY owner_spotify_user_id, id") { songRow(it) },
            songArtists =
                rows("SELECT * FROM song_artists ORDER BY owner_spotify_user_id, track_id, position") {
                    jsonRow(
                        "owner_spotify_user_id" to it.getString("owner_spotify_user_id"),
                        "track_id" to it.getString("track_id"),
                        "position" to it.getLong("position"),
                        "artist_id" to it.getString("artist_id"),
                        "name" to it.getString("name"),
                        "href" to it.getString("href"),
                        "uri" to it.getString("uri"),
                    )
                },
            managedPlaylists =
                rows("SELECT * FROM managed_playlists ORDER BY owner_spotify_user_id, definition_id") {
                    jsonRow(
                        "owner_spotify_user_id" to it.getString("owner_spotify_user_id"),
                        "definition_id" to it.getString("definition_id"),
                        "spotify_playlist_id" to it.getString("spotify_playlist_id"),
                        "created_at_millis" to it.getLong("created_at_millis"),
                        "last_synced_at_millis" to it.getObject("last_synced_at_millis"),
                        "last_seen_snapshot_id" to it.getString("last_seen_snapshot_id"),
                    )
                },
            userPlaylistDefinitions =
                rows("SELECT * FROM user_playlist_definitions ORDER BY owner_spotify_user_id, id") {
                    jsonRow(
                        "id" to it.getString("id"),
                        "owner_spotify_user_id" to it.getString("owner_spotify_user_id"),
                        "name" to it.getString("name"),
                        "description" to it.getString("description"),
                        "enabled" to it.getLong("enabled"),
                        "recipe_payload" to it.getString("recipe_payload"),
                    )
                },
            userPlaylistDefinitionItems =
                rows(
                    "SELECT * FROM user_playlist_definition_items " +
                        "ORDER BY owner_spotify_user_id, definition_id, position",
                ) {
                    jsonRow(
                        "owner_spotify_user_id" to it.getString("owner_spotify_user_id"),
                        "definition_id" to it.getString("definition_id"),
                        "position" to it.getLong("position"),
                        "recipe_item_payload" to it.getString("recipe_item_payload"),
                    )
                },
            cacheSourceSync =
                rows(
                    "SELECT * FROM cache_source_sync ORDER BY owner_spotify_user_id, source_key",
                ) {
                    jsonRow(
                        "owner_spotify_user_id" to it.getString("owner_spotify_user_id"),
                        "source_key" to it.getString("source_key"),
                        "resource_kind" to it.getString("resource_kind"),
                        "status" to it.getString("status"),
                        "source_revision" to it.getString("source_revision"),
                        "last_synced_at_millis" to it.getObject("last_synced_at_millis"),
                        "item_count" to it.getObject("item_count"),
                        "last_error_code" to it.getString("last_error_code"),
                        "last_error_at_millis" to it.getObject("last_error_at_millis"),
                    )
                },
        )

    fun sourceSnapshots(ownerSpotifyUserId: String): List<CacheSourceSnapshot> =
        rows(
            "SELECT * FROM cache_source_sync WHERE owner_spotify_user_id = ? " +
                "ORDER BY source_key",
            ownerSpotifyUserId,
        ) { sourceSnapshot(it) }

    fun aggregateStatus(ownerSpotifyUserId: String): com.philipwilcox.spotifybutler.service.CacheAggregateStatus {
        val sources = sourceSnapshots(ownerSpotifyUserId)
        if (sources.isEmpty()) return com.philipwilcox.spotifybutler.service.CacheAggregateStatus.EMPTY
        if (sources.any {
                it.status == CacheSourceStatus.REFRESHING
            }
        ) {
            return com.philipwilcox.spotifybutler.service.CacheAggregateStatus.REFRESHING
        }
        if (sources.any {
                it.status == CacheSourceStatus.ERROR || it.status == CacheSourceStatus.STALE
            }
        ) {
            return com.philipwilcox.spotifybutler.service.CacheAggregateStatus.STALE
        }
        val rootKeys =
            setOf(
                CacheSourceKey.SAVED_TRACKS,
                CacheSourceKey.TOP_TRACKS,
                CacheSourceKey.TOP_ARTISTS,
                CacheSourceKey.PLAYLISTS,
            )
        val availableRootKeys = sources.mapTo(mutableSetOf(), CacheSourceSnapshot::sourceKey).intersect(rootKeys)
        return if (availableRootKeys == rootKeys && sources.all { it.status == CacheSourceStatus.READY }) {
            com.philipwilcox.spotifybutler.service.CacheAggregateStatus.READY
        } else {
            com.philipwilcox.spotifybutler.service.CacheAggregateStatus.PARTIAL
        }
    }

    fun sourceSnapshot(
        ownerSpotifyUserId: String,
        sourceKey: String,
    ): CacheSourceSnapshot =
        queryOne(
            "SELECT * FROM cache_source_sync WHERE owner_spotify_user_id = ? AND source_key = ?",
            ownerSpotifyUserId,
            sourceKey,
        ) { sourceSnapshot(it) }
            ?: CacheSourceSnapshot(
                ownerSpotifyUserId,
                sourceKey,
                CacheSourceKey.root(ownerSpotifyUserId, sourceKey).resourceKind,
                CacheSourceStatus.EMPTY,
                null,
                null,
                null,
            )

    fun setSourceRefreshing(
        ownerSpotifyUserId: String,
        sourceKey: String,
    ) {
        val key = CacheSourceKey.root(ownerSpotifyUserId, sourceKey)
        transaction {
            execute(
                """INSERT INTO cache_source_sync
                   (owner_spotify_user_id, source_key, resource_kind, status, source_revision,
                    last_synced_at_millis, item_count, last_error_code, last_error_at_millis)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                   ON CONFLICT(owner_spotify_user_id, source_key) DO UPDATE SET
                   resource_kind=excluded.resource_kind, status=excluded.status""",
                ownerSpotifyUserId,
                sourceKey,
                CacheSourceKey.resourceKindValue(key.resourceKind),
                CacheSourceStatus.REFRESHING.name.lowercase(),
                sourceSnapshot(ownerSpotifyUserId, sourceKey).sourceRevision,
                sourceSnapshot(ownerSpotifyUserId, sourceKey).lastSyncedAt?.toEpochMilli(),
                sourceSnapshot(ownerSpotifyUserId, sourceKey).itemCount,
                sourceSnapshot(ownerSpotifyUserId, sourceKey).lastErrorCode,
                sourceSnapshot(ownerSpotifyUserId, sourceKey).lastErrorAt?.toEpochMilli(),
            )
        }
    }

    fun setSourceFailure(
        ownerSpotifyUserId: String,
        sourceKey: String,
        errorCode: String,
        failedAtMillis: Long,
    ) {
        val key = CacheSourceKey.root(ownerSpotifyUserId, sourceKey)
        val current = sourceSnapshot(ownerSpotifyUserId, sourceKey)
        execute(
            """INSERT INTO cache_source_sync
               (owner_spotify_user_id, source_key, resource_kind, status, source_revision,
                last_synced_at_millis, item_count, last_error_code, last_error_at_millis)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
               ON CONFLICT(owner_spotify_user_id, source_key) DO UPDATE SET
               status=excluded.status, last_error_code=excluded.last_error_code,
               last_error_at_millis=excluded.last_error_at_millis""",
            ownerSpotifyUserId,
            sourceKey,
            CacheSourceKey.resourceKindValue(key.resourceKind),
            CacheSourceStatus.ERROR.name.lowercase(),
            current.sourceRevision,
            current.lastSyncedAt?.toEpochMilli(),
            current.itemCount,
            errorCode.take(MAX_ERROR_CODE_LENGTH),
            failedAtMillis,
        )
    }

    fun cachedPlaylistIds(ownerSpotifyUserId: String): List<String> =
        rows(
            "SELECT id FROM playlists WHERE owner_spotify_user_id = ? ORDER BY source_position",
            ownerSpotifyUserId,
        ) { it.getString(1) }

    fun replaceSource(
        ownerSpotifyUserId: String,
        sourceKey: String,
        snapshot: SpotifyCacheSnapshot,
        syncedAtMillis: Long,
        sourceRevision: String = newSourceRevision(syncedAtMillis),
    ) {
        val key = CacheSourceKey.root(ownerSpotifyUserId, sourceKey)
        transaction {
            when (key.resourceKind) {
                CacheResourceKind.TRACK_LIST ->
                    if (sourceKey == CacheSourceKey.SAVED_TRACKS) {
                        execute("DELETE FROM saved_tracks WHERE owner_spotify_user_id = ?", ownerSpotifyUserId)
                        snapshot.savedTracks.forEachIndexed {
                            position,
                            value,
                            ->
                            insertSavedTrack(ownerSpotifyUserId, position, value)
                        }
                    } else {
                        execute("DELETE FROM top_tracks WHERE owner_spotify_user_id = ?", ownerSpotifyUserId)
                        snapshot.topTracks.forEachIndexed {
                            position,
                            value,
                            ->
                            insertTopTrack(ownerSpotifyUserId, position, value)
                        }
                    }
                CacheResourceKind.ARTIST_LIST -> {
                    execute("DELETE FROM top_artists WHERE owner_spotify_user_id = ?", ownerSpotifyUserId)
                    snapshot.topArtists.forEachIndexed {
                        position,
                        value,
                        ->
                        insertTopArtist(ownerSpotifyUserId, position, value)
                    }
                }
                CacheResourceKind.PLAYLIST_LIST -> {
                    execute("DELETE FROM playlists WHERE owner_spotify_user_id = ?", ownerSpotifyUserId)
                    execute("DELETE FROM playlist_details WHERE owner_spotify_user_id = ?", ownerSpotifyUserId)
                    snapshot.playlists.forEachIndexed {
                        position,
                        value,
                        ->
                        insertPlaylist(ownerSpotifyUserId, position, value)
                    }
                }
                CacheResourceKind.PLAYLIST_CONTENTS ->
                    replacePlaylistContentsInTransaction(
                        ownerSpotifyUserId,
                        key,
                        snapshot,
                        syncedAtMillis,
                    )
            }
            upsertSourceSnapshot(
                ownerSpotifyUserId,
                sourceKey,
                key.resourceKind,
                "ready",
                sourceRevision,
                syncedAtMillis,
                sourceItemCount(ownerSpotifyUserId, key),
            )
        }
    }

    fun replaceCache(
        snapshot: SpotifyCacheSnapshot,
        syncTimestampMillis: Long,
        ownerSpotifyUserId: String = DEFAULT_OWNER,
    ) {
        transaction {
            clearOwner(ownerSpotifyUserId)
            snapshot.savedTracks.forEachIndexed {
                position,
                value,
                ->
                insertSavedTrack(ownerSpotifyUserId, position, value)
            }
            snapshot.topTracks.forEachIndexed { position, value -> insertTopTrack(ownerSpotifyUserId, position, value) }
            snapshot.topArtists.forEachIndexed {
                position,
                value,
                ->
                insertTopArtist(ownerSpotifyUserId, position, value)
            }
            snapshot.playlists.forEachIndexed { position, value -> insertPlaylist(ownerSpotifyUserId, position, value) }
            val playlistItems = snapshot.playlistItems.ifEmpty { inferredPlaylistItems(snapshot) }
            playlistItems.groupBy(SpotifyPlaylistItem::playlistId).forEach { (playlistId, items) ->
                replacePlaylistContentsInTransaction(
                    ownerSpotifyUserId,
                    CacheSourceKey.playlistItems(ownerSpotifyUserId, playlistId),
                    snapshot.copy(playlistItems = items),
                    syncTimestampMillis,
                )
            }
            upsertSourceSnapshot(
                ownerSpotifyUserId,
                CacheSourceKey.SAVED_TRACKS,
                CacheResourceKind.TRACK_LIST,
                "ready",
                newSourceRevision(syncTimestampMillis),
                syncTimestampMillis,
                snapshot.savedTracks.size,
            )
            upsertSourceSnapshot(
                ownerSpotifyUserId,
                CacheSourceKey.TOP_TRACKS,
                CacheResourceKind.TRACK_LIST,
                "ready",
                newSourceRevision(syncTimestampMillis),
                syncTimestampMillis,
                snapshot.topTracks.size,
            )
            upsertSourceSnapshot(
                ownerSpotifyUserId,
                CacheSourceKey.TOP_ARTISTS,
                CacheResourceKind.ARTIST_LIST,
                "ready",
                newSourceRevision(syncTimestampMillis),
                syncTimestampMillis,
                snapshot.topArtists.size,
            )
            upsertSourceSnapshot(
                ownerSpotifyUserId,
                CacheSourceKey.PLAYLISTS,
                CacheResourceKind.PLAYLIST_LIST,
                "ready",
                newSourceRevision(syncTimestampMillis),
                syncTimestampMillis,
                snapshot.playlists.size,
            )
        }
    }

    fun replaceCacheContent(snapshot: SpotifyCacheSnapshot) = replaceCache(snapshot, 0L, DEFAULT_OWNER)

    fun markSyncComplete(syncTimestampMillis: Long) {
        sourceSnapshots(DEFAULT_OWNER).forEach { source ->
            upsertSourceSnapshot(
                DEFAULT_OWNER,
                source.sourceKey,
                source.resourceKind,
                "ready",
                source.sourceRevision,
                syncTimestampMillis,
                source.itemCount,
            )
        }
    }

    fun markCacheRefreshing(ownerSpotifyUserId: String) {
        sourceSnapshots(ownerSpotifyUserId).forEach { setSourceRefreshing(ownerSpotifyUserId, it.sourceKey) }
    }

    fun markCacheStale(ownerSpotifyUserId: String = DEFAULT_OWNER) {
        sourceSnapshots(ownerSpotifyUserId).forEach {
            setSourceFailure(ownerSpotifyUserId, it.sourceKey, "refresh_failed", System.currentTimeMillis())
        }
    }

    fun songs(): List<SpotifyTrack> = songs(null)

    fun songs(ownerSpotifyUserId: String?): List<SpotifyTrack> {
        val sql =
            if (ownerSpotifyUserId == null) {
                "SELECT * FROM songs ORDER BY owner_spotify_user_id, id"
            } else {
                "SELECT * FROM songs WHERE owner_spotify_user_id = ? ORDER BY id"
            }
        return rows(sql, *listOfNotNull(ownerSpotifyUserId).toTypedArray(), mapper = ::decodeSong)
    }

    fun song(
        id: String,
        ownerSpotifyUserId: String? = null,
    ): SpotifyTrack? = songs(ownerSpotifyUserId).firstOrNull { it.id == id }

    fun songEnrichment(
        ids: List<String>,
        ownerSpotifyUserId: String? = null,
    ): List<StoredSong> {
        val byId = songs(ownerSpotifyUserId).associateBy(SpotifyTrack::id)
        return ids.mapNotNull { id -> byId[id]?.let(::toStoredSong) }
    }

    fun songEnrichment(id: String): StoredSong? = songEnrichment(listOf(id)).singleOrNull()

    fun playlistItems(
        playlistId: String,
        ownerSpotifyUserId: String = defaultOwner(),
    ): List<StoredPlaylistItem> =
        rows(
            "SELECT * FROM playlist_items WHERE owner_spotify_user_id = ? AND playlist_id = ? ORDER BY position",
            ownerSpotifyUserId,
            playlistId,
        ) { playlistItem(it) }

    fun playlistDetails(
        playlistId: String,
        ownerSpotifyUserId: String = DEFAULT_OWNER,
    ): StoredPlaylistDetails? =
        queryOne(
            "SELECT * FROM playlist_details WHERE owner_spotify_user_id = ? AND playlist_id = ?",
            ownerSpotifyUserId,
            playlistId,
        ) { storedPlaylistDetails(it) }

    fun findPlaylistByName(
        name: String,
        ownerSpotifyUserId: String,
    ): ExistingPlaylistMetadata? =
        queryOne(
            "SELECT id FROM playlists WHERE owner_spotify_user_id = ? AND name = ? ORDER BY source_position LIMIT 1",
            ownerSpotifyUserId,
            name,
        ) { ExistingPlaylistMetadata(it.getString(1)) }

    fun playlistMatchesByName(name: String): List<ExistingPlaylistMetadata> =
        rows("SELECT id FROM playlists WHERE name = ? ORDER BY owner_spotify_user_id, source_position", name) {
            ExistingPlaylistMetadata(it.getString(1))
        }

    fun playlistIdByName(
        name: String,
        ownerSpotifyUserId: String,
    ): String? = findPlaylistByName(name, ownerSpotifyUserId)?.id

    fun managedPlaylist(
        definitionId: String,
        ownerSpotifyUserId: String,
    ): ManagedPlaylist? =
        queryOne(
            "SELECT * FROM managed_playlists WHERE owner_spotify_user_id = ? AND definition_id = ?",
            ownerSpotifyUserId,
            definitionId,
        ) { managedPlaylist(it) }

    fun managedPlaylists(ownerSpotifyUserId: String): List<ManagedPlaylist> =
        rows(
            "SELECT * FROM managed_playlists WHERE owner_spotify_user_id = ? ORDER BY definition_id",
            ownerSpotifyUserId,
        ) {
            managedPlaylist(it)
        }

    fun saveManagedPlaylist(
        definitionId: String,
        spotifyPlaylistId: String,
        ownerSpotifyUserId: String,
        createdAtMillis: Long = System.currentTimeMillis(),
    ) {
        execute(
            """INSERT INTO managed_playlists
               (owner_spotify_user_id, definition_id, spotify_playlist_id, created_at_millis)
               VALUES (?, ?, ?, ?)
               ON CONFLICT(owner_spotify_user_id, definition_id) DO UPDATE SET
               spotify_playlist_id=excluded.spotify_playlist_id""",
            ownerSpotifyUserId,
            definitionId,
            spotifyPlaylistId,
            createdAtMillis,
        )
    }

    fun updateManagedPlaylistState(
        definitionId: String,
        ownerSpotifyUserId: String,
        lastSyncedAtMillis: Long,
        lastSeenSnapshotId: String?,
    ) {
        execute(
            "UPDATE managed_playlists SET last_synced_at_millis = ?, last_seen_snapshot_id = ? " +
                "WHERE owner_spotify_user_id = ? AND definition_id = ?",
            lastSyncedAtMillis,
            lastSeenSnapshotId,
            ownerSpotifyUserId,
            definitionId,
        )
    }

    fun publishPlaylistTrackIds(
        playlistId: String,
        trackIds: List<String>,
        syncTimestampMillis: Long,
        ownerSpotifyUserId: String = DEFAULT_OWNER,
        snapshotId: String? = null,
    ) {
        transaction {
            replacePlaylistItemsWithTrackIds(ownerSpotifyUserId, playlistId, trackIds)
            queryOne(
                "SELECT definition_id FROM managed_playlists " +
                    "WHERE owner_spotify_user_id = ? AND spotify_playlist_id = ?",
                ownerSpotifyUserId,
                playlistId,
            ) { it.getString(1) }?.let { definitionId ->
                updateManagedPlaylistState(definitionId, ownerSpotifyUserId, syncTimestampMillis, snapshotId)
            }
        }
    }

    fun userPlaylistDefinitions(ownerSpotifyUserId: String): List<StoredUserPlaylistDefinition> =
        rows(
            "SELECT * FROM user_playlist_definitions WHERE owner_spotify_user_id = ? ORDER BY id",
            ownerSpotifyUserId,
        ) { storedDefinition(it) }

    fun userPlaylistDefinition(
        id: String,
        ownerSpotifyUserId: String,
    ): StoredUserPlaylistDefinition? =
        queryOne(
            "SELECT * FROM user_playlist_definitions WHERE id = ? AND owner_spotify_user_id = ?",
            id,
            ownerSpotifyUserId,
        ) { storedDefinition(it) }

    fun saveUserPlaylistDefinition(definition: StoredUserPlaylistDefinition) {
        val recipe = definition.recipe ?: fallbackRecipe(definition.trackIds)
        val encoded = PlaylistRecipeCodec.encode(recipe)
        transaction {
            execute(
                """INSERT INTO user_playlist_definitions
                   (id, owner_spotify_user_id, name, description, enabled, recipe_payload)
                   VALUES (?, ?, ?, ?, ?, ?)
                   ON CONFLICT(owner_spotify_user_id, id) DO UPDATE SET
                   name=excluded.name, description=excluded.description, enabled=excluded.enabled,
                   recipe_payload=excluded.recipe_payload""",
                definition.id,
                definition.ownerSpotifyUserId,
                definition.name,
                definition.description,
                if (definition.enabled) 1 else 0,
                encoded,
            )
            execute(
                "DELETE FROM user_playlist_definition_items WHERE owner_spotify_user_id = ? AND definition_id = ?",
                definition.ownerSpotifyUserId,
                definition.id,
            )
            definition.trackIds.forEachIndexed { position, trackId ->
                execute(
                    "INSERT INTO user_playlist_definition_items " +
                        "(owner_spotify_user_id, definition_id, position, recipe_item_payload) VALUES (?, ?, ?, ?)",
                    definition.ownerSpotifyUserId,
                    definition.id,
                    position,
                    trackId,
                )
            }
            execute(
                "INSERT INTO user_playlist_definition_items " +
                    "(owner_spotify_user_id, definition_id, position, recipe_item_payload) VALUES (?, ?, ?, ?)",
                definition.ownerSpotifyUserId,
                definition.id,
                definition.trackIds.size,
                "recipe:$encoded",
            )
        }
    }

    fun hasCompletedSync(ownerSpotifyUserId: String = defaultOwner()): Boolean =
        sourceSnapshots(ownerSpotifyUserId).any { it.status == CacheSourceStatus.READY }

    fun duplicateSavedTrackIds(ownerSpotifyUserId: String = defaultOwner()): List<String> =
        rows(
            """SELECT id FROM saved_tracks WHERE owner_spotify_user_id = ? AND id IN
               (SELECT id FROM saved_tracks WHERE owner_spotify_user_id = ? GROUP BY id HAVING COUNT(*) > 1)
               ORDER BY source_position""",
            ownerSpotifyUserId,
            ownerSpotifyUserId,
        ) { it.getString(1) }.drop(1)

    fun deleteSavedTracks(
        trackIds: List<String>,
        ownerSpotifyUserId: String = defaultOwner(),
    ) {
        transaction {
            trackIds.distinct().forEach {
                execute("DELETE FROM saved_tracks WHERE owner_spotify_user_id = ? AND id = ?", ownerSpotifyUserId, it)
            }
        }
    }

    fun execute(query: PlaylistQuery): List<SpotifyTrack> = execute(defaultOwner(), query)

    fun execute(
        ownerSpotifyUserId: String,
        query: PlaylistQuery,
    ): List<SpotifyTrack> {
        val saved = savedTracks(ownerSpotifyUserId)
        val topArtists = topArtistIds(ownerSpotifyUserId)
        val topTracks = topTrackIds(ownerSpotifyUserId)
        val filtered =
            when (query) {
                is PlaylistQuery.RecentLiked ->
                    savedEntries(ownerSpotifyUserId)
                        .sortedByDescending {
                            it.addedAt
                        }.map { it.track }
                        .take(query.limit.toInt())
                is PlaylistQuery.RandomLiked -> saved.sortedBy(SpotifyTrack::id).take(query.limit.toInt())
                is PlaylistQuery.CollectedDiscoverWeekly -> {
                    val collected = playlistTracksByName(ownerSpotifyUserId, query.collectedName)
                    val discover = playlistTracksByName(ownerSpotifyUserId, query.sourceName)
                    (
                        collected +
                            discover.filter {
                                it.releaseDate?.substringBefore('-')?.toLongOrNull() ?: 0L >=
                                    query.minReleaseYear &&
                                    it.id !in collected.map(SpotifyTrack::id)
                            }
                    )
                }
                is PlaylistQuery.SavedPerArtist -> perArtist(saved, query.limit.toInt())
                is PlaylistQuery.SavedInYearRangePerArtist ->
                    perArtist(
                        saved.filter {
                            yearIn(it, query.minYear, query.maxYear)
                        },
                        query.limit.toInt(),
                    )
                is PlaylistQuery.SavedThroughYearPerArtist ->
                    perArtist(
                        saved.filter {
                            yearIn(it, null, query.maxYear)
                        },
                        query.limit.toInt(),
                    )
                is PlaylistQuery.SavedSinceYearPerArtist ->
                    perArtist(
                        saved.filter {
                            yearIn(it, query.minYear, null)
                        },
                        query.limit.toInt(),
                    )
                PlaylistQuery.SavedNotByTopArtists ->
                    saved.filter {
                        it.primaryArtistId == null ||
                            it.primaryArtistId !in topArtists
                    }
                PlaylistQuery.SavedNotInTopTracks -> saved.filter { it.id !in topTracks }
                is PlaylistQuery.SavedInYearRange ->
                    saved.filter {
                        yearIn(
                            it,
                            query.minYearInclusive,
                            query.maxYearExclusive,
                        )
                    }
            }
        return filtered
    }

    fun candidates(source: CandidateSource): List<CandidateTrack> = candidates(defaultOwner(), source)

    fun candidates(
        ownerSpotifyUserId: String,
        source: CandidateSource,
    ): List<CandidateTrack> =
        when (source) {
            CandidateSource.SavedTracks ->
                savedEntries(ownerSpotifyUserId).mapIndexed {
                    index,
                    item,
                    ->
                    CandidateTrack(item.track, item.addedAt, index)
                }
            CandidateSource.TopTracks ->
                topTracks(ownerSpotifyUserId).mapIndexed {
                    index,
                    item,
                    ->
                    CandidateTrack(item, null, index)
                }
            is CandidateSource.PlaylistItems -> {
                val tracks =
                    if (source.playlistName.startsWith(CacheSourceKey.PLAYLIST_ITEMS_PREFIX)) {
                        playlistTracksById(
                            ownerSpotifyUserId,
                            source.playlistName.removePrefix(CacheSourceKey.PLAYLIST_ITEMS_PREFIX),
                        )
                    } else {
                        playlistTracksByName(ownerSpotifyUserId, source.playlistName)
                    }
                tracks.mapIndexed { index, item -> CandidateTrack(item, null, index) }
            }
            is CandidateSource.Union -> source.sources.flatMap { candidates(ownerSpotifyUserId, it) }
            is CandidateSource.Difference -> {
                val excluded =
                    candidates(
                        ownerSpotifyUserId,
                        source.right,
                    ).mapTo(mutableSetOf(), CandidateTrack::identity)
                candidates(ownerSpotifyUserId, source.left).filterNot { it.identity in excluded }
            }
            is CandidateSource.Filtered ->
                candidates(ownerSpotifyUserId, source.source).filter {
                    PlaylistRecipeEngine.matches(source.predicate, it)
                }
        }

    fun recipeExecutionContext(): RecipeExecutionContext = recipeExecutionContext(defaultOwner())

    fun recipeExecutionContext(ownerSpotifyUserId: String): RecipeExecutionContext =
        RecipeExecutionContext(topArtistIds(ownerSpotifyUserId), topTrackIds(ownerSpotifyUserId))

    fun playlistTracksById(
        ownerSpotifyUserId: String,
        playlistId: String,
    ): List<SpotifyTrack> =
        rows(
            "SELECT track_json FROM playlist_tracks " +
                "WHERE owner_spotify_user_id = ? AND playlist_id = ? ORDER BY position",
            ownerSpotifyUserId,
            playlistId,
        ) { decodeStoredTrack(it.getString(1), "playlist $playlistId") }

    fun findPlaylistTracksByName(
        name: String,
        ownerSpotifyUserId: String,
    ): List<SpotifyTrack> =
        findPlaylistByName(name, ownerSpotifyUserId)?.id?.let { playlistTracksById(ownerSpotifyUserId, it) }.orEmpty()

    private fun defaultOwner(): String =
        queryOne("SELECT owner_spotify_user_id FROM cache_source_sync ORDER BY owner_spotify_user_id LIMIT 1") {
            it.getString(1)
        }
            ?: DEFAULT_OWNER

    override fun close() {
        connection.close()
        driver.close()
    }

    private fun clearOwner(owner: String) {
        listOf(
            "top_artists",
            "top_tracks",
            "saved_tracks",
            "playlists",
            "playlist_tracks",
            "playlist_details",
            "playlist_items",
            "songs",
            "song_artists",
            "cache_source_sync",
            "managed_playlists",
        ).forEach {
            execute("DELETE FROM $it WHERE owner_spotify_user_id = ?", owner)
        }
    }

    private fun replacePlaylistContentsInTransaction(
        owner: String,
        key: CacheSourceKey,
        snapshot: SpotifyCacheSnapshot,
        syncedAtMillis: Long = System.currentTimeMillis(),
    ) {
        val playlistId = key.sourceKey.removePrefix(CacheSourceKey.PLAYLIST_ITEMS_PREFIX)
        execute("DELETE FROM playlist_items WHERE owner_spotify_user_id = ? AND playlist_id = ?", owner, playlistId)
        execute("DELETE FROM playlist_tracks WHERE owner_spotify_user_id = ? AND playlist_id = ?", owner, playlistId)
        val items = snapshot.playlistItems.filter { it.playlistId == playlistId }
        val playlistName =
            snapshot.playlists.firstOrNull { it.id == playlistId }?.name ?: items.firstOrNull()?.playlistName
                ?: playlistId
        items.forEach { insertPlaylistItem(owner, it) }
        items
            .mapNotNull { item -> item.track?.let { PlaylistTrack(playlistName, item.addedAt, it, playlistId) } }
            .forEachIndexed { position, track -> insertPlaylistTrack(owner, position, track) }
        items.mapNotNull(SpotifyPlaylistItem::track).forEach { insertSong(owner, it) }
        upsertSourceSnapshot(
            owner,
            key.sourceKey,
            key.resourceKind,
            "ready",
            newSourceRevision(syncedAtMillis),
            syncedAtMillis,
            items.size,
        )
    }

    private fun replacePlaylistItemsWithTrackIds(
        owner: String,
        playlistId: String,
        trackIds: List<String>,
    ) {
        execute("DELETE FROM playlist_items WHERE owner_spotify_user_id = ? AND playlist_id = ?", owner, playlistId)
        trackIds.forEachIndexed { position, trackId ->
            val track = song(trackId, owner) ?: error("Cannot publish unknown track $trackId")
            execute(
                """INSERT INTO playlist_items
                   (owner_spotify_user_id, playlist_id, position, added_at, added_by_id, is_local, item_type,
                    is_playable, item_id, item_uri, status, complete_item_json)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                owner,
                playlistId,
                position,
                null,
                null,
                0,
                "track",
                1,
                track.id,
                track.uri,
                "playable",
                "{\"item\":${track.rawJson}}",
            )
        }
    }

    private fun inferredPlaylistItems(snapshot: SpotifyCacheSnapshot): List<SpotifyPlaylistItem> =
        snapshot.playlistTracks.mapIndexed { position, value ->
            val playlistId =
                snapshot.playlists.firstOrNull { it.name == value.playlistName }?.id
                    ?: error("Playlist ${value.playlistName} is missing from cache snapshot")
            SpotifyPlaylistItem(
                playlistId,
                value.playlistName,
                position,
                value.addedAt,
                null,
                false,
                "track",
                true,
                value.track.id,
                value.track.uri,
                "playable",
                "{\"item\":${value.track.rawJson}}",
                value.track,
            )
        }

    private fun sourceItemCount(
        owner: String,
        key: CacheSourceKey,
    ): Int =
        when (key.resourceKind) {
            CacheResourceKind.TRACK_LIST ->
                if (key.sourceKey ==
                    CacheSourceKey.SAVED_TRACKS
                ) {
                    count("saved_tracks", owner)
                } else {
                    count("top_tracks", owner)
                }
            CacheResourceKind.ARTIST_LIST -> count("top_artists", owner)
            CacheResourceKind.PLAYLIST_LIST -> count("playlists", owner)
            CacheResourceKind.PLAYLIST_CONTENTS ->
                count(
                    "playlist_items",
                    owner,
                    "playlist_id = ?",
                    key.sourceKey.removePrefix(CacheSourceKey.PLAYLIST_ITEMS_PREFIX),
                )
        }

    private fun count(
        table: String,
        owner: String,
        extra: String? = null,
        value: String? = null,
    ): Int {
        val sql =
            "SELECT COUNT(*) FROM $table WHERE owner_spotify_user_id = ?" + if (extra == null) "" else " AND $extra"
        return queryOne(sql, *listOfNotNull(owner, value).toTypedArray()) { it.getInt(1) } ?: 0
    }

    private fun upsertSourceSnapshot(
        owner: String,
        key: String,
        kind: CacheResourceKind,
        status: String,
        revision: String?,
        timestamp: Long?,
        count: Int?,
    ) {
        execute(
            """INSERT INTO cache_source_sync
               (owner_spotify_user_id, source_key, resource_kind, status, source_revision,
                last_synced_at_millis, item_count, last_error_code, last_error_at_millis)
               VALUES (?, ?, ?, ?, ?, ?, ?, NULL, NULL)
               ON CONFLICT(owner_spotify_user_id, source_key) DO UPDATE SET
               resource_kind=excluded.resource_kind, status=excluded.status, source_revision=excluded.source_revision,
               last_synced_at_millis=excluded.last_synced_at_millis, item_count=excluded.item_count,
               last_error_code=NULL, last_error_at_millis=NULL""",
            owner,
            key,
            CacheSourceKey.resourceKindValue(kind),
            status,
            revision,
            timestamp,
            count,
        )
    }

    private fun insertSavedTrack(
        owner: String,
        position: Int,
        value: SavedTrack,
    ) {
        val track = value.track
        execute(
            """INSERT INTO saved_tracks
               (owner_spotify_user_id, source_position, name, id, primary_artist_id, release_date, release_year,
                href, uri, added_at, track_json) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            owner,
            position,
            track.name,
            track.id,
            track.primaryArtistId,
            track.releaseDate,
            year(track.releaseDate),
            track.href,
            track.uri,
            value.addedAt,
            track.rawJson,
        )
        insertSong(owner, track)
    }

    private fun insertTopTrack(
        owner: String,
        position: Int,
        track: SpotifyTrack,
    ) {
        execute(
            "INSERT INTO top_tracks " +
                "(owner_spotify_user_id, source_position, name, id, href, uri, track_json) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?)",
            owner,
            position,
            track.name,
            track.id,
            track.href,
            track.uri,
            track.rawJson,
        )
        insertSong(owner, track)
    }

    private fun insertTopArtist(
        owner: String,
        position: Int,
        artist: SpotifyArtist,
    ) = execute(
        "INSERT INTO top_artists " +
            "(owner_spotify_user_id, source_position, name, id, href, uri) VALUES (?, ?, ?, ?, ?, ?)",
        owner,
        position,
        artist.name,
        artist.id,
        artist.href,
        artist.uri,
    )

    private fun insertPlaylist(
        owner: String,
        position: Int,
        playlist: SpotifyPlaylist,
    ) {
        execute(
            "INSERT INTO playlists " +
                "(owner_spotify_user_id, source_position, name, id, href, uri, tracks_href) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?)",
            owner,
            position,
            playlist.name,
            playlist.id,
            playlist.href,
            playlist.uri,
            playlist.tracksHref,
        )
        execute(
            "INSERT INTO playlist_details " +
                "(owner_spotify_user_id, playlist_id, description, is_public, collaborative, owner_id, " +
                "item_count, display_url) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            owner,
            playlist.id,
            playlist.description,
            playlist.public?.toFlag(),
            playlist.collaborative?.toFlag(),
            playlist.ownerId,
            playlist.itemCount,
            playlist.displayUrl,
        )
    }

    private fun insertPlaylistItem(
        owner: String,
        item: SpotifyPlaylistItem,
    ) = execute(
        """INSERT INTO playlist_items (owner_spotify_user_id, playlist_id, position, added_at, added_by_id,
            is_local, item_type, is_playable, item_id, item_uri, status, complete_item_json)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
        owner,
        item.playlistId,
        item.position,
        item.addedAt,
        item.addedById,
        item.isLocal.toFlag(),
        item.itemType,
        item.isPlayable.toFlag(),
        item.itemId,
        item.itemUri,
        item.status,
        item.rawJson,
    )

    private fun insertPlaylistTrack(
        owner: String,
        position: Int,
        value: PlaylistTrack,
    ) {
        val track = value.track
        val playlistId = value.playlistId ?: findPlaylistByName(value.playlistName, owner)?.id ?: value.playlistName
        execute(
            """INSERT INTO playlist_tracks (owner_spotify_user_id, playlist_id, position, playlist_name, added_at,
                release_date, release_year, name, primary_artist_id, id, href, uri, track_json)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            owner,
            playlistId,
            position,
            value.playlistName,
            value.addedAt,
            track.releaseDate,
            year(track.releaseDate),
            track.name,
            track.primaryArtistId,
            track.id,
            track.href,
            track.uri,
            track.rawJson,
        )
        insertSong(owner, track)
    }

    private fun insertSong(
        owner: String,
        track: SpotifyTrack,
    ) {
        execute(
            """INSERT INTO songs (owner_spotify_user_id, id, name, href, uri, album_id, album_name, album_href,
                album_uri, release_date, duration_ms, explicit, available, track_json)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(owner_spotify_user_id, id) DO UPDATE SET name=excluded.name, href=excluded.href,
                uri=excluded.uri, album_id=excluded.album_id, album_name=excluded.album_name,
                album_href=excluded.album_href, album_uri=excluded.album_uri, release_date=excluded.release_date,
                duration_ms=excluded.duration_ms, explicit=excluded.explicit, available=excluded.available,
                track_json=excluded.track_json""",
            owner,
            track.id,
            track.name,
            track.href,
            track.uri,
            track.albumId,
            track.albumName,
            track.albumHref,
            track.albumUri,
            track.releaseDate,
            track.durationMs,
            track.explicit?.toFlag(),
            track.available.toFlag(),
            track.rawJson,
        )
        execute("DELETE FROM song_artists WHERE owner_spotify_user_id = ? AND track_id = ?", owner, track.id)
        track.artists.forEachIndexed {
            position,
            artist,
            ->
            execute(
                "INSERT INTO song_artists " +
                    "(owner_spotify_user_id, track_id, position, artist_id, name, href, uri) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?)",
                owner,
                track.id,
                position,
                artist.id,
                artist.name,
                artist.href,
                artist.uri,
            )
        }
    }

    private data class SavedEntry(
        val track: SpotifyTrack,
        val addedAt: String?,
    )

    private fun savedEntries(owner: String): List<SavedEntry> =
        rows("SELECT * FROM saved_tracks WHERE owner_spotify_user_id = ? ORDER BY source_position", owner) {
            SavedEntry(decodeStoredTrack(it.getString("track_json"), "saved track"), it.getString("added_at"))
        }

    private fun savedTracks(owner: String): List<SpotifyTrack> = savedEntries(owner).map(SavedEntry::track)

    private fun topTracks(owner: String): List<SpotifyTrack> =
        rows("SELECT * FROM top_tracks WHERE owner_spotify_user_id = ? ORDER BY source_position", owner) {
            decodeStoredTrack(it.getString("track_json"), "top track")
        }

    private fun topArtistIds(owner: String): Set<String> =
        rows("SELECT id FROM top_artists WHERE owner_spotify_user_id = ? ORDER BY source_position", owner) {
            it.getString(1)
        }.toSet()

    private fun topTrackIds(owner: String): Set<String> = topTracks(owner).mapTo(mutableSetOf(), SpotifyTrack::id)

    private fun playlistTracksByName(
        owner: String,
        name: String,
    ): List<SpotifyTrack> =
        findPlaylistByName(name, owner)
            ?.id
            ?.let {
                playlistTracksById(owner, it)
            }.orEmpty()

    private fun perArtist(
        tracks: List<SpotifyTrack>,
        limit: Int,
    ): List<SpotifyTrack> =
        tracks
            .groupBy {
                it.primaryArtistId
            }.flatMap { (_, values) -> values.sortedBy(SpotifyTrack::id).take(limit) }

    private fun yearIn(
        track: SpotifyTrack,
        min: Long?,
        max: Long?,
    ): Boolean {
        val year =
            year(track.releaseDate) ?: return false
        return (min == null || year >= min) && (max == null || year < max)
    }

    private fun year(value: String?): Long? = value?.substringBefore('-')?.toLongOrNull()

    private fun storedDefinition(result: ResultSet): StoredUserPlaylistDefinition {
        val owner = result.getString("owner_spotify_user_id")
        val id = result.getString("id")
        return StoredUserPlaylistDefinition(
            id,
            owner,
            result.getString("name"),
            definitionItems(owner, id),
            result.getString("description"),
            result.getInt("enabled") != 0,
            PlaylistRecipeCodec.decode(result.getString("recipe_payload")),
        )
    }

    private fun definitionItems(
        owner: String,
        id: String,
    ): List<String> =
        rows(
            "SELECT recipe_item_payload FROM user_playlist_definition_items " +
                "WHERE owner_spotify_user_id = ? AND definition_id = ? AND recipe_item_payload NOT LIKE 'recipe:%' " +
                "ORDER BY position",
            owner,
            id,
        ) { it.getString(1) }

    private fun fallbackRecipe(trackIds: List<String>): PlaylistRecipe =
        PlaylistRecipe(
            source = CandidateSource.SavedTracks,
            selection =
                com.philipwilcox.spotifybutler.service.SelectionPolicy(
                    target = trackIds.size,
                    rankBy = com.philipwilcox.spotifybutler.service.RankingStrategy.AddedAtDescending,
                ),
            ordering = com.philipwilcox.spotifybutler.service.OrderingPolicy.AddedAtDescending,
        )

    private fun decodeSong(result: ResultSet): SpotifyTrack =
        decodeStoredTrack(result.getString("track_json"), "song ${result.getString("id")}")

    private fun toStoredSong(track: SpotifyTrack) =
        StoredSong(
            track.id,
            track.name,
            track.href,
            track.uri,
            track.albumId,
            track.albumName,
            track.albumHref,
            track.albumUri,
            track.releaseDate,
            track.durationMs,
            track.explicit,
            track.available,
            track.artists
                .map {
                    StoredArtist(it.id, it.name, it.href, it.uri)
                },
        )

    private fun sourceSnapshot(result: ResultSet): CacheSourceSnapshot =
        CacheSourceSnapshot(
            result.getString("owner_spotify_user_id"),
            result.getString("source_key"),
            CacheResourceKind.entries.first {
                CacheSourceKey.resourceKindValue(it) ==
                    result.getString("resource_kind")
            },
            CacheSourceStatus.entries.first {
                it.name.equals(result.getString("status"), true)
            },
            result.getString("source_revision"),
            result.getLongOrNull("last_synced_at_millis")?.let(Instant::ofEpochMilli),
            result.getIntOrNull("item_count"),
            true,
            result.getString("last_error_code"),
            result.getLongOrNull("last_error_at_millis")?.let(Instant::ofEpochMilli),
        )

    private fun managedPlaylist(result: ResultSet) =
        ManagedPlaylist(
            result.getString("definition_id"),
            result.getString("spotify_playlist_id"),
            result.getString("owner_spotify_user_id"),
            result.getLong("created_at_millis"),
            result.getLongOrNull("last_synced_at_millis"),
            result.getString("last_seen_snapshot_id"),
        )

    private fun storedPlaylistDetails(result: ResultSet) =
        StoredPlaylistDetails(
            result.getString("playlist_id"),
            result.getString("description"),
            result.getIntOrNull("is_public")?.let {
                it !=
                    0
            },
            result.getIntOrNull("collaborative")?.let {
                it != 0
            },
            result.getString("owner_id"),
            result.getIntOrNull("item_count"),
            result.getString("display_url"),
        )

    private fun playlistItem(result: ResultSet) =
        StoredPlaylistItem(
            result.getString("playlist_id"),
            result.getInt("position"),
            result.getString("added_at"),
            result.getString("added_by_id"),
            result.getInt("is_local") != 0,
            result.getString("item_type"),
            result.getInt("is_playable") != 0,
            result.getString("item_id"),
            result.getString("item_uri"),
            result.getString("status"),
        )

    private fun savedRow(result: ResultSet) =
        jsonRow(
            "owner_spotify_user_id" to result.getString("owner_spotify_user_id"),
            "source_position" to result.getLong("source_position"),
            "name" to result.getString("name"),
            "id" to result.getString("id"),
            "primary_artist_id" to result.getString("primary_artist_id"),
            "release_date" to result.getString("release_date"),
            "release_year" to result.getObject("release_year"),
            "href" to result.getString("href"),
            "uri" to result.getString("uri"),
            "added_at" to result.getString("added_at"),
            "track_json" to result.getString("track_json"),
        )

    private fun topRow(result: ResultSet) =
        jsonRow(
            "owner_spotify_user_id" to result.getString("owner_spotify_user_id"),
            "source_position" to result.getLong("source_position"),
            "name" to result.getString("name"),
            "id" to result.getString("id"),
            "href" to result.getString("href"),
            "uri" to result.getString("uri"),
            "track_json" to result.getString("track_json"),
        )

    private fun artistRow(result: ResultSet) =
        jsonRow(
            "owner_spotify_user_id" to result.getString("owner_spotify_user_id"),
            "source_position" to result.getLong("source_position"),
            "name" to result.getString("name"),
            "id" to result.getString("id"),
            "href" to result.getString("href"),
            "uri" to result.getString("uri"),
        )

    private fun playlistRow(result: ResultSet) =
        jsonRow(
            "owner_spotify_user_id" to result.getString("owner_spotify_user_id"),
            "source_position" to result.getLong("source_position"),
            "name" to result.getString("name"),
            "id" to result.getString("id"),
            "href" to result.getString("href"),
            "uri" to result.getString("uri"),
            "tracks_href" to result.getString("tracks_href"),
        )

    private fun playlistTrackRow(result: ResultSet) =
        jsonRow(
            "owner_spotify_user_id" to result.getString("owner_spotify_user_id"),
            "playlist_id" to result.getString("playlist_id"),
            "position" to result.getLong("position"),
            "playlist_name" to result.getString("playlist_name"),
            "added_at" to result.getString("added_at"),
            "release_date" to result.getString("release_date"),
            "release_year" to result.getObject("release_year"),
            "name" to result.getString("name"),
            "primary_artist_id" to result.getString("primary_artist_id"),
            "id" to result.getString("id"),
            "href" to result.getString("href"),
            "uri" to result.getString("uri"),
            "track_json" to result.getString("track_json"),
        )

    private fun songRow(result: ResultSet) =
        jsonRow(
            "owner_spotify_user_id" to result.getString("owner_spotify_user_id"),
            "id" to result.getString("id"),
            "name" to result.getString("name"),
            "href" to result.getString("href"),
            "uri" to result.getString("uri"),
            "album_id" to result.getString("album_id"),
            "album_name" to result.getString("album_name"),
            "album_href" to result.getString("album_href"),
            "album_uri" to result.getString("album_uri"),
            "release_date" to result.getString("release_date"),
            "duration_ms" to result.getObject("duration_ms"),
            "explicit" to result.getObject("explicit"),
            "available" to result.getLong("available"),
            "track_json" to result.getString("track_json"),
        )

    private fun <T> rows(
        sql: String,
        vararg args: Any?,
        mapper: (ResultSet) -> T,
    ): List<T> =
        connection.prepareStatement(sql).use { statement ->
            args.forEachIndexed { index, value ->
                statement.setObject(
                    index + 1,
                    value,
                )
            }
            ; statement.executeQuery().use { result -> buildList { while (result.next()) add(mapper(result)) } }
        }

    private fun <T> queryOne(
        sql: String,
        vararg args: Any?,
        mapper: (ResultSet) -> T,
    ): T? = rows(sql, *args, mapper = mapper).singleOrNull()

    private fun execute(
        sql: String,
        vararg args: Any?,
    ) {
        connection.prepareStatement(sql).use { statement ->
            args.forEachIndexed { index, value ->
                statement.setObject(
                    index + 1,
                    value,
                )
            }
            ; statement.executeUpdate()
        }
    }

    private fun <T> transaction(block: () -> T): T {
        val previous = connection.autoCommit
        connection.autoCommit = false
        return try {
            block().also { connection.commit() }
        } catch (exception: Exception) {
            connection.rollback()
            throw exception
        } finally {
            connection.autoCommit =
                previous
        }
    }

    companion object {
        const val TARGET_SCHEMA_VERSION = 1
        const val DEFAULT_OWNER = "legacy-owner"
        private const val MAX_ERROR_CODE_LENGTH = 80

        fun newSourceRevision(timestamp: Long): String = "source-$timestamp-${UUID.randomUUID()}"

        fun Boolean.toFlag(): Int = if (this) 1 else 0

        fun Long.toFlag(): Int = if (this != 0L) 1 else 0

        fun Int.toFlag(): Int = if (this != 0) 1 else 0

        fun ResultSet.getLongOrNull(name: String): Long? = getObject(name)?.let { getLong(name) }

        fun ResultSet.getIntOrNull(name: String): Int? = getObject(name)?.let { getInt(name) }

        fun openConnection(
            path: Path,
            readOnly: Boolean,
        ): Connection {
            val normalizedPath = path.toAbsolutePath().normalize()
            val url = if (readOnly) "jdbc:sqlite:file:$normalizedPath?mode=ro" else "jdbc:sqlite:$normalizedPath"
            return java.sql.DriverManager.getConnection(url).also {
                it.createStatement().use { statement ->
                    statement.execute("PRAGMA foreign_keys = ON")
                }
            }
        }

        fun validateSchema(
            connection: Connection,
            path: Path,
        ) {
            val version =
                runCatching {
                    connection
                        .prepareStatement("SELECT version FROM schema_version WHERE singleton_id = 1")
                        .use { statement ->
                            statement.executeQuery().use { result -> if (result.next()) result.getInt(1) else 0 }
                        }
                }.getOrElse {
                    throw IllegalStateException(
                        "Database at $path has no compatible schema_version table; recreate it with the target schema",
                        it,
                    )
                }
            require(version == TARGET_SCHEMA_VERSION) {
                "Database at $path has schema version $version; expected $TARGET_SCHEMA_VERSION. " +
                    "Recreate the database; migrations are not supported."
            }
        }

        fun open(databasePath: Path): SpotifyStore {
            val absolutePath = databasePath.toAbsolutePath().normalize()
            val newDatabase = Files.notExists(absolutePath)
            absolutePath.parent?.let(Files::createDirectories)
            val driver = JdbcSqliteDriver("jdbc:sqlite:$absolutePath")
            if (newDatabase) {
                SpotifyDatabase.Schema.create(driver)
                openConnection(absolutePath, false).use { connection ->
                    connection
                        .prepareStatement(
                            "INSERT INTO schema_version (singleton_id, version) VALUES (1, ?)",
                        ).use { statement ->
                            statement.setInt(1, TARGET_SCHEMA_VERSION)
                            statement.executeUpdate()
                        }
                }
            }
            val connection = openConnection(absolutePath, false)
            validateSchema(connection, absolutePath)
            return SpotifyStore(driver, connection)
        }

        fun openReadOnly(databasePath: Path): SpotifyStore {
            val absolutePath = databasePath.toAbsolutePath().normalize()
            require(Files.isRegularFile(absolutePath)) { "SQLite database not found at $absolutePath" }
            val connection = openConnection(absolutePath, true)
            validateSchema(connection, absolutePath)
            return SpotifyStore(JdbcSqliteDriver("jdbc:sqlite:file:$absolutePath?mode=ro"), connection)
        }
    }
}

data class StoredUserPlaylistDefinition(
    val id: String,
    val ownerSpotifyUserId: String,
    val name: String,
    val trackIds: List<String> = emptyList(),
    val description: String = "",
    val enabled: Boolean = true,
    val recipe: PlaylistRecipe? = null,
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
    val createdAtMillis: Long = 0L,
    val lastSyncedAtMillis: Long? = null,
    val lastSeenSnapshotId: String? = null,
)

data class ExistingPlaylistMetadata(
    val id: String,
)

private fun jsonRow(vararg values: Pair<String, Any?>): JsonObject =
    buildJsonObject {
        values.forEach { (key, value) ->
            put(
                key,
                when (value) {
                    null -> JsonNull
                    is Boolean -> JsonPrimitive(value)
                    is Number -> JsonPrimitive(value)
                    else -> JsonPrimitive(value.toString())
                },
            )
        }
    }
