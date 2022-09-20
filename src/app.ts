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
        await asyncPool(2, myPlaylists, (p: Playlist) => {
            console.debug(`Will try to fetch ${p.tracks.total} tracks for ${p.name} from ${p.tracks.href}`)
            // TODO: wrap console in a proper level-having logger...
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


        // Load all this into db
        this.loadTracksAndPlaylistsIntoDb(mySavedTracks, myTopTracks, myTopArtists, myPlaylists, tracksForPlaylists);


        // Come up with new playlist contents based on the following queries!
        // TODO: check how well date sorting works here... maybe convert timestamps before storing...
        // TODO: make this config-driven with a new config class, including like shuffle and such
        const numberedSubquery = "(SELECT id, row_number() OVER win1 AS rn FROM saved_tracks WINDOW win1 AS" +
            " (PARTITION BY primary_artist_id ORDER BY RANDOM())) as numbered"
        const playlistQueries = {
            // TODO: add like a "Random 100" mix...
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
            "Liked Tracks, Five Per Artist": `
                SELECT track_json
                FROM saved_tracks
                         INNER JOIN ${numberedSubquery}
                where saved_tracks.id = numbered.id
                  and numbered.rn < 6`,
            "2005-2024, Five Per Artist": `
                SELECT track_json
                FROM saved_tracks as s
                         INNER JOIN ${numberedSubquery}
                WHERE s.id = numbered.id
                  and numbered.rn < 6
                  and release_year >= 2005
                  and release_year <= 2024
            `,
            "1985-2004, Five Per Artist": `
                SELECT track_json
                FROM saved_tracks as s
                         INNER JOIN ${numberedSubquery}
                WHERE s.id = numbered.id
                  and numbered.rn < 6
                  and release_year >= 1985
                  and release_year <= 2004
            `,
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
        }
        const playlistResults = this.getResultsForPlaylistQueries(playlistQueries)

        for (let playlistName in playlistResults) {
            const playlistInfo = playlistResults[playlistName]
            let playlistId = playlistInfo.playlistId
            if (playlistInfo.playlistId == null) {
                const trackNames = playlistInfo.allTracks.map(x => x.name)
                const logString = `Created new playlist with name ${playlistName} and the following ${trackNames.length} tracks: ${JSON.stringify(trackNames)}`
                if (!this.dryRun) {
                    const newPlaylist = await this.library.createPlaylistWithName(playlistName)
                    await this.library.addTracksToPlaylist(newPlaylist.id, playlistInfo.allTracks)
                    playlistId = newPlaylist.id
                    console.log(logString)
                } else {
                    console.log("DRY RUN --- " + logString)
                }
            } else {
                const addedNames = playlistInfo.addedTracks.map(x => x.name)
                const removedNames = playlistInfo.removedTracks.map(x => x.name)
                const logString = `For playlist with name ${playlistName} we added the following ${addedNames.length} tracks ${JSON.stringify(addedNames)}
                   and removed the following ${removedNames.length}  tracks ${JSON.stringify(removedNames)}`
                if (!this.dryRun) {
                    const changes = []
                    if (playlistInfo.addedTracks.length > 0) {
                        changes.push(this.library.addTracksToPlaylist(playlistInfo.playlistId, playlistInfo.addedTracks))
                    }
                    if (playlistInfo.removedTracks.length > 0) {
                        changes.push(this.library.removeTracksFromPlaylist(playlistInfo.playlistId, playlistInfo.removedTracks))
                    }
                    await Promise.all(changes)
                    // TODO: we could optimize this by async-ing these operations outside of this loop
                    console.log(logString)
                } else {
                    console.log("DRY RUN --- " + logString)
                }
            }

            // TODO: make shuffling a config-driven thing, per-playlist
            // In-place-shuffling of every track in every playlist, after adding the new ones
            // This will preserve original added-to-playlist timestamp

            let snapshotId = (await this.library.getPlaylistInfo(playlistId)).snapshot_id
            const originalTracksWithIndex = playlistInfo.allTracks.map((x, i) => [x, i])
            const shuffledTracksWithOriginalIndex = utils.shuffle(originalTracksWithIndex)

            const changes = shuffledTracksWithOriginalIndex.map((originalTuple, i) => {
                const originalI = originalTuple[1]
                return [i, originalI]
            })

            const logString = `For playlist with name ${playlistName} we are shuffling the tracks in-place`
            if (!this.dryRun) {
                console.log(logString)
                await asyncPool(4, changes, (c) => {
                    // console.debug("Issuing command for " + c)
                    return this.library.reorderTracksInPlaylist(playlistInfo.playlistId, c[1], 1, c[0], snapshotId)
                })
            } else {
                console.log("DRY RUN --- " + logString)
            }
        }
        console.log("DONE!")
        // TODO: build an object we can turn into an HTML response...
    }

    getResultsForPlaylistQueries(playlistQueries: Record<string, string>): Record<string, NewPlaylistInfo> {
        const playlistResults: Record<string, NewPlaylistInfo> = {}
        for (let playlistName in playlistQueries) {
            console.log(`About to query: ${playlistQueries[playlistName]}`)
            const allTracks = this.db.prepare(playlistQueries[playlistName]).all().map(x =>
                Deserialize(JSON.parse(x.track_json), Track)
            )

            // TODO: now, based on this... look for any existing ones in the playlist track table and find the
            //  change sets!
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

        // TODO: neither URI nor ID will help us dedupe, we'll also have to look at artist... build some separate audit
        //  functions for that

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
}