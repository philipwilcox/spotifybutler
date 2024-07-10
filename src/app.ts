import Library from "./lib/spotify-clients/library";
import {LibraryTrack} from "./lib/models/spotify/library-track.js";
import {Artist} from "./lib/models/spotify/artist.js";
import {Playlist} from "./lib/models/spotify/playlist.js";
import {Track} from "./lib/models/spotify/track.js";
import {Database} from "better-sqlite3";
import asyncPool from "tiny-async-pool";
import {Deserialize} from "cerialize";
import NewPlaylistInfo from "./lib/models/new-playlist-info.js";
import {PlaylistTrack} from "./lib/models/spotify/playlist-track.js";
import utils from "./utils.js";

export default class App {
    private library: Library
    private db: Database
    private minYearForDiscoverWeekly: number
    private dryRun: boolean

    constructor(library: Library, db: Database, minYearForDiscoverWeekly: number, dryRun: boolean = false) {
        this.library = library
        this.db = db
        this.minYearForDiscoverWeekly = minYearForDiscoverWeekly
        this.dryRun = dryRun
    }

    async runButler() {
        // TODO: another way of doing the typing for these would be using "@types/spotify-api"
        const startInitialMs = Date.now()
        const [
            mySavedTracks,
            myTopTracks,
            myTopArtists,
            myPlaylists
        ] = await Promise.all([
            this.library.getMySavedTracks(),
            this.library.getMyTopTracks(),
            this.library.getMyTopArtists(),
            this.library.getMyPlaylists()
        ]);
        const endInitialMs = Date.now()

        console.log(`I got ${mySavedTracks.length} saved tracks, ${myTopTracks.length} top tracks, 
            ${myTopArtists.length} top artists, and ${myPlaylists.length} playlists in 
            ${endInitialMs - startInitialMs} milliseconds`)

        const startPlaylistMs = Date.now()
        const tracksForPlaylists: Record<string, PlaylistTrack[]> = {}
        // Parallel fetch at level two cuts this from like 40 seconds to 20 for my playlists as of Mar 2022, but
        // unfortunately higher hits a rate limit sometimes.

        // TODO: if we can't go higher parallelism reliably, what's even the point?
        await this.asyncPoolAll(2, myPlaylists, (p: Playlist) => {
            console.debug(`Will try to fetch ${p.tracks.total} tracks for ${p.name} from ${p.tracks.href}`)
            // TODO: wrap console in a proper level-having logger...
            // TODO: add retries for these
            return this.library.getTracksForPlaylist(p.tracks.href).then(x => {
                tracksForPlaylists[p.name] = x
                console.info(`Found ${x.length} tracks for playlist ${p.name}`)
                // For some reason this is breaking on my shared duo playlist, it's expecting 0 tracks...
                // if (x.length != p.tracks.total) {
                //     throw new Error(`Expected ${p.tracks.total} tracks for ${p.name}, got ${x.length}`)
                // }
            })
        })

        const endPlaylistMs = Date.now()
        console.log(`Took ${endPlaylistMs - startPlaylistMs} milliseconds to fetch all playlist tracks`);

        this.loadTracksAndPlaylistsIntoDb(mySavedTracks, myTopTracks, myTopArtists, myPlaylists, tracksForPlaylists);

        await this.removeLikedSongDupesFromServerAndDb(); // TODO: do I always want to run this?

        const newPlaylistResults = this.queryForNewPlaylistResults();

        await this.saveNewPlaylistsToServer(newPlaylistResults);

        console.log("DONE!")
        // TODO: build an object we can turn into an HTML response...
    }

    // Wrapper from migration instructions from v1 to v2 https://www.npmjs.com/package/tiny-async-pool?activeTab=code
    async asyncPoolAll(...args) {
        const results = [];
        for await (const result of asyncPool(...args)) {
            results.push(result);
        }
        return results;
    }

    getResultsForPlaylistQueries(playlistQueries: Record<string, string>): Record<string, NewPlaylistInfo> {
        const playlistResults: Record<string, NewPlaylistInfo> = {}
        for (let playlistName in playlistQueries) {
            console.log(`About to query: ${playlistQueries[playlistName]}`)
            const allTracks = this.db.prepare(playlistQueries[playlistName]).all().map(x =>
                Deserialize(JSON.parse(x.track_json), Track)
            )

            const oldTracks = this.db.prepare("SELECT track_json FROM playlist_tracks WHERE playlist_name =" +
                " @playlist_name").all({playlist_name: playlistName})
                .map(x => Deserialize(JSON.parse(x.track_json), Track))
            const playlistResult = this.db.prepare("SELECT id, snapshot_id FROM playlists WHERE name = @playlist_name")
                .get({playlist_name: playlistName})
            const playlistId = playlistResult?.id
            const playlistSnapshotId = playlistResult?.snapshot_id
            if (playlistId) {
                const [addedTracks, removedTracks] = this.getAddedAndRemovedTracks(oldTracks, allTracks)
                playlistResults[playlistName] = new NewPlaylistInfo(
                    playlistName,
                    allTracks,
                    playlistId,
                    oldTracks,
                    addedTracks,
                    removedTracks,
                    playlistSnapshotId
                )
            } else {
                playlistResults[playlistName] = new NewPlaylistInfo(playlistName, allTracks)
            }
        }
        return playlistResults
    }

    /**
     * Return an object like {removed: [tracks], added: [tracks]} that result from comparing the tracks already in the
     * playlist to the given list of desired tracks.
     */
    getAddedAndRemovedTracks(oldTracks: Track[], newTracks: Track[]): [Track[], Track[]] {
        const oldUris = new Set(oldTracks.map(x => x.uri))
        const newUris = new Set(newTracks.map(x => x.uri))
        const removedTracks = oldTracks.filter(x => !newUris.has(x.uri))
        const addedTracks = newTracks.filter(x => !oldUris.has(x.uri))
        return [
            addedTracks,
            removedTracks,
        ]
    }

    loadTracksAndPlaylistsIntoDb(savedTracks: LibraryTrack[], topTracks: Track[], topArtists: Artist[],
                                 playlists: Playlist[], tracksForPlaylists: Record<string, PlaylistTrack[]>) {
        const savedTrackQuery = this.db.prepare("INSERT INTO saved_tracks (name, id, href, uri, added_at," +
            "release_date, release_year, primary_artist_id, track_json) VALUES (@name, @id, @href, @uri, @added_at," +
            " @release_date, @release_year, @primary_artist_id, @track_json)")
        const insertManySavedTrack = this.db.transaction((tracks) => {
            for (const track of tracks) savedTrackQuery.run(track);
        })
        insertManySavedTrack(savedTracks.map(x => {
            return {
                name: x.track.name,
                id: x.track.id,
                href: x.track.href,
                uri: x.track.uri,
                added_at: x.added_at,
                release_date: x.track.album.release_date,
                release_year: x.track.album.release_date.split('-')[0],
                primary_artist_id: x.track.artists[0].id,
                track_json: JSON.stringify(x.track)
            }
        }));

        const topTrackQuery = this.db.prepare("INSERT INTO top_tracks (name, id, href, uri," +
            " track_json) VALUES (@name, @id, @href, @uri, @track_json)")
        const insertManyTopTrack = this.db.transaction((tracks) => {
            for (const track of tracks) topTrackQuery.run(track);
        })
        insertManyTopTrack(topTracks.map(x => {
            return {
                name: x.name,
                id: x.id,
                href: x.href,
                uri: x.uri,
                track_json: JSON.stringify(x)
            }
        }));

        const topArtistQuery = this.db.prepare("INSERT INTO top_artists (name, id, href, uri) " +
            "VALUES (@name, @id, @href, @uri)")
        const insertManyTopArtist = this.db.transaction((tracks) => {
            for (const track of tracks) topArtistQuery.run(track);
        })
        insertManyTopArtist(topArtists.map(x => {
            return {
                name: x.name,
                id: x.id,
                href: x.href,
                uri: x.uri,
            }
        }));

        const playlistQuery = this.db.prepare("INSERT INTO playlists (name, id, href, uri, tracks_href, snapshot_id) " +
            "VALUES (@name, @id, @href, @uri, @tracks_href, @snapshot_id)")
        const insertManyPlaylist = this.db.transaction((tracks) => {
            for (const track of tracks) playlistQuery.run(track);
        })
        insertManyPlaylist(playlists.map(x => {
            return {
                name: x.name,
                id: x.id,
                href: x.href,
                uri: x.uri,
                tracks_href: x.tracks.href,
                snapshot_id: x.snapshot_id
            }
        }));

        const playlistTracksQuery = this.db.prepare("INSERT INTO playlist_tracks (playlist_name, added_at, name, " +
            "id, href, uri, release_date, release_year, primary_artist_id, track_json) VALUES (@playlist_name," +
            " @added_at, @name, @id, @href, @uri, @release_date, @release_year, @primary_artist_id, @track_json)")
        const insertManyPlaylistTrack = this.db.transaction((playlistTracks) => {
            for (const track of playlistTracks) playlistTracksQuery.run(track);
        })
        for (let k in tracksForPlaylists) {
            const tracks = tracksForPlaylists[k]
            insertManyPlaylistTrack(tracks.map(x => {
                return {
                    playlist_name: k,
                    added_at: x.added_at,
                    name: x.track.name,
                    id: x.track.id,
                    href: x.track.href,
                    uri: x.track.uri,
                    release_date: x.track.album.release_date,
                    release_year: x.track.album.release_date.split('-')[0],
                    primary_artist_id: x.track.artists[0].id,
                    track_json: JSON.stringify(x.track)
                }
            }))
        }

        // TODO: only do this on debug
        // let x = this.db.prepare("SELECT playlist_name, count(*) from playlist_tracks group by playlist_name").all();
        // console.log(JSON.stringify(x))
    }

    private async removeLikedSongDupesFromServerAndDb() {
        // This will return all dupes in my saved tracks, sorted by artist/name and then most-recently-added-first
        // My theory is that the most recent added one most accurately represents which one would show as "currently"
        // available browsing in spotify, so is most likely to avoid "re-adding" a dupe
        const query = `select saved_tracks.primary_artist_id, saved_tracks.name, id, added_at
                       from saved_tracks
                                inner join (select name, primary_artist_id, count(*)
                                            from saved_tracks
                                            group by 1, 2
                                            having count(*) > 1) dupes
                       where saved_tracks.name = dupes.name
                         and saved_tracks.primary_artist_id = dupes.primary_artist_id
                       order by 1, 2, 4 desc;`

        const seenTracks = new Set<string>();
        const idsToRemove = new Set<string>();
        const dupeResults = this.db.prepare(query).all();
        dupeResults.forEach((x) => {
            const artistName = x.primary_artist_id + "--" + x.name
            // Let's keep the first one we see, as mentioned above
            if (seenTracks.has(artistName)) {
                idsToRemove.add(x.id)
            } else {
                seenTracks.add(artistName)
            }
        })
        const idArray = Array.from(idsToRemove);
        if (idArray.length > 0) {
            console.log(`Removing dupes from library for ${idArray}`)
        } else {
            console.log("No duplicates found in library!")
        }

        this.library.removeFromMySavedTracks(idArray)

        // remove these from saved tracks table
        const removeQuery = `DELETE
                             FROM saved_tracks
                             WHERE id IN (${idArray.map(x => "?").join(",")})`;
        this.db.prepare(removeQuery).run(...idArray)
    }


    private async saveNewPlaylistsToServer(newPlaylistResults: Record<string, NewPlaylistInfo>) {
        for (let playlistName in newPlaylistResults) {
            const playlistInfo = newPlaylistResults[playlistName]
            let playlistId = playlistInfo.playlistId
            if (playlistInfo.playlistId == null) {
                const trackNames = playlistInfo.allTracks.map(x => x.name)
                const logString = `Created new playlist with name ${playlistName} and the following ${trackNames.length} tracks: ${JSON.stringify(trackNames)}`
                if (!this.dryRun) {
                    const shuffledTracks = utils.shuffle(playlistInfo.allTracks)
                    const newPlaylist = await this.library.createPlaylistWithName(playlistName)
                    await this.library.addTracksToPlaylist(newPlaylist.id, shuffledTracks)
                    playlistId = newPlaylist.id
                    console.log(logString)
                } else {
                    console.log("DRY RUN --- " + logString)
                }
            } else {
                const addedNames = playlistInfo.addedTracks.map(x => x.name)
                const removedNames = playlistInfo.removedTracks.map(x => x.name)
                const logString = `For playlist with name ${playlistName} we added the following ${addedNames.length} tracks ${JSON.stringify(addedNames)}
                   and removed the following ${removedNames.length}  tracks ${JSON.stringify(removedNames)} to give ${playlistInfo.allTracks.length} tracks`
                if (!this.dryRun) {
                    const shuffledTracks = utils.shuffle(playlistInfo.allTracks)
                    await this.library.replaceTracksInPlaylist(playlistInfo.playlistId, shuffledTracks)

                    // TODO: clean up / extract out "in place" modifications in favor of shuffling completely every time
                    // const changes = []
                    // if (playlistInfo.addedTracks.length > 0) {
                    //     changes.push(this.library.addTracksToPlaylist(playlistInfo.playlistId, playlistInfo.addedTracks))
                    // }
                    // if (playlistInfo.removedTracks.length > 0) {
                    //     changes.push(this.library.removeTracksFromPlaylist(playlistInfo.playlistId, playlistInfo.removedTracks))
                    // }
                    // await Promise.all(changes)
                    // TODO: we could optimize this by async-ing these operations outside of this loop
                    console.log(logString)
                } else {
                    console.log("DRY RUN --- " + logString)
                }
            }

            // TODO: remove / clean up / extract attempt at "in place" shuffling due to poor speed and results
            // // TODO: make shuffling a config-driven thing, per-playlist
            // // In-place-shuffling of every track in every playlist, after adding the new ones
            // // This will preserve original added-to-playlist timestamp
            // const newPlaylistMeta = (await this.library.getPlaylistInfo(playlistId))
            // const newTrackList = (await this.library.getTracksForPlaylist(newPlaylistMeta.tracks.href)).map(x => x.track)
            // const originalTracksWithIndex = newTrackList.map((x, i) => [x, i]).slice()
            // const shuffledTracksWithOriginalIndex = utils.shuffle(originalTracksWithIndex.slice())
            //
            // const changes = shuffledTracksWithOriginalIndex.map((originalTuple, i) => {
            //     const originalI = originalTuple[1]
            //     // "what was in originalI should now be in i"
            //     return [i, originalI]
            // })
            //
            // const logString = `For playlist with name ${playlistName} we are shuffling the tracks in-place`
            // // The problem here is that if we use the same, consistent snapshot ID, when we merge the "try to put a new
            // // track in position 0" + "try to put a new track in position 1", etc, changes, we end up skipping every
            // // other original track - we put the second one in front of what was the second originally, but that's now
            // // the 3rd, etc... This seems to be because it's inserting it before the position of something that is then
            // // moved, vs a specific index.
            // // If we use no snapshot ID, on the other hand... the same thing is happening for reasons I don't
            // // understand... maybe a new snapshot isn't being generated quickly enough?
            // // So let's just try adding each to the front, aka "in front of the original front one"
            // // But seems like sometimes these merges still resolve funny... :| so we'd rather go backwards to forward,
            // // synchronously, with no snapshot id... :| This doesn't give us what we want, but it seems more random
            // // than any other method I've tried to preserve original time-added...
            // if (!this.dryRun) {
            //     console.log(logString)
            //     await asyncPool(1, changes.reverse(), (c) => {
            //         const oldLocation = c[1]// + i // update this as we move other things to the front of the list...
            //         // console.log(`Moving for ${c} - track at ${oldLocation} - ${originalTracksWithIndex[oldLocation][0].name} - to the front!`)
            //         return this.library.reorderTracksInPlaylist(playlistInfo.playlistId, oldLocation, 1, 0)
            //     })
            // } else {
            //     console.log("DRY RUN --- " + logString)
            // }
        }
    }

    private queryForNewPlaylistResults(): Record<string, NewPlaylistInfo> {
        // Come up with new playlist contents based on the following queries!
        // TODO: check how well date sorting works here... maybe convert timestamps before storing...
        // TODO: make this config-driven with a new config class, including like shuffle and such
        const createArtistCountLimitedQuery = (innerQuery, limit) => `SELECT track_json
                                                                      FROM (SELECT track_json, row_number() OVER win1 AS rn
                                                                            FROM (${innerQuery})
                                                                            WINDOW win1 AS (PARTITION BY primary_artist_id ORDER BY RANDOM()))
                                                                      WHERE rn <= ${limit}`
        const playlistQueries = {
            "100 Most Recent Liked Songs": "SELECT track_json FROM saved_tracks ORDER BY added_at DESC LIMIT" +
                " 100",
            "100 Random Liked Songs": "SELECT track_json FROM saved_tracks ORDER BY RANDOM() LIMIT 100",
            "Collected Discover Weekly 2016 And On - Butler": `
                SELECT track_json
                FROM playlist_tracks
                WHERE playlist_name = 'Collected Discover Weekly 2016 And On - Butler'
                UNION
                SELECT track_json
                FROM playlist_tracks
                WHERE playlist_name = 'Discover Weekly'
                  AND release_year >= ${this.minYearForDiscoverWeekly}
                  AND id NOT IN
                      (SELECT id
                       FROM playlist_tracks
                       WHERE playlist_name = 'Collected Discover Weekly 2016 And On - Butler')
            `,
            "Liked Tracks, Twelve Per Artist": createArtistCountLimitedQuery("SELECT track_json, primary_artist_id" +
                " FROM" +
                " saved_tracks", 12),
            "2005-2024, Twelve Per Artist": createArtistCountLimitedQuery("SELECT track_json, primary_artist_id FROM" +
                " saved_tracks WHERE release_year >= 2005 AND release_year <= 2024", 12),
            "1985-2004, Twelve Per Artist": createArtistCountLimitedQuery("SELECT track_json, primary_artist_id FROM" +
                " saved_tracks WHERE release_year >= 1985 and release_year <= 2004", 12),
            // TODO: how to know which artist is number 1 vs number 10, say
            // "Saved Tracks By My Top 20 Artists - Butler": "",
            // "Saved Tracks Not By My Top 10 Artists - Butler": "",
            // "Saved Tracks Not By My Top 25 Artists - Butler": "",
            "Saved Tracks Not By My Top 50 Artists - Butler": "SELECT track_json FROM saved_tracks WHERE" +
                " primary_artist_id NOT IN (SELECT id FROM top_artists)",
            "Saved Tracks Not In My Top 50 Tracks - Butler": "SELECT track_json FROM saved_tracks WHERE" +
                " id NOT IN (SELECT id FROM top_tracks)",
            "Pre-1980": "SELECT track_json FROM saved_tracks WHERE release_year < 1980",
            "1980 - Butler Created": "SELECT track_json FROM saved_tracks WHERE release_year < 1990 AND release_year" +
                " >= 1980",
            "1990 - Butler Created": "SELECT track_json FROM saved_tracks WHERE release_year < 2000 AND release_year" +
                " >= 1990",
            "2000 - Butler Created": "SELECT track_json FROM saved_tracks WHERE release_year < 2010 AND release_year" +
                " >= 2000",
            "2010 - Butler Created": "SELECT track_json FROM saved_tracks WHERE release_year < 2020 AND release_year" +
                " >= 2010",
            "2020 - Butler Created": "SELECT track_json FROM saved_tracks WHERE release_year < 2030 AND release_year" +
                " >= 2020",
            "Last 5 Years, Eight Per Artist": createArtistCountLimitedQuery("SELECT track_json, primary_artist_id" +
                " FROM saved_tracks WHERE release_year >= (strftime('%Y', 'now') - 5)", 8),
        }
        return this.getResultsForPlaylistQueries(playlistQueries)
    }
}