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

export default class App {
    private library: Library
    private db: Database
    private minYearForDiscoverWeekly: number

    constructor(library: Library, db: Database, minYearForDiscoverWeekly: number) {
        this.library = library
        this.db = db
        this.minYearForDiscoverWeekly = minYearForDiscoverWeekly
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
                if (x.length != p.tracks.total) throw new Error(`Expected ${p.tracks.total} tracks for ${p.name}, got ${x.length}`)
            })
        })

        const endPlaylistMs = Date.now()
        console.log(`Took ${endPlaylistMs - startPlaylistMs} milliseconds to fetch all playlist tracks`);


        // Load all this into db
        this.loadTracksAndPlaylistsIntoDb(mySavedTracks, myTopTracks, myTopArtists, myPlaylists, tracksForPlaylists);


        // Come up with new playlist contents based on the following queries!
        // TODO: check how well date sorting works here... maybe convert timestamps before storing...
        // TODO: make this config-driven with a new config class, including like shuffle and such
        const playlistQueries = {
            // TODO: do some deduping based on artist/name/id type stuff in the DB...
            "100 Most Recent Liked Songs": "SELECT track_json FROM saved_tracks ORDER BY added_at DESC LIMIT" +
                " 100",
            // TODO: this one is special cause it's additive each time
            // "Collected Discover Weekly 2016 And On - Butler": "SELECT track_json FROM playlist_tracks WHERE" +
            //     " playlist_name = 'Discover Weekly' AND substr(json_extract(track_json," +
            //     ` '$.album.release_date'), 1, 4) >= '${this.minYearForDiscoverWeekly}'`,
            "Liked Tracks, Five Per Artist": "SELECT track_json FROM saved_tracks INNER JOIN (SELECT id," +
                " row_number() " +
                "OVER win1 as rn  FROM saved_tracks WINDOW win1 AS (PARTITION BY json_extract(track_json, " +
                "'$.artists[0].id') ORDER BY RANDOM())) as numbered where saved_tracks.id = numbered.id " +
                "and numbered.rn < 6",
            // TODO: how to know which artist is number 1 vs number 10, say
            // "Saved Tracks By My Top 20 Artists - Butler": "",
            // "Saved Tracks Not By My Top 10 Artists - Butler": "",
            // "Saved Tracks Not By My Top 25 Artists - Butler": "",
            "Saved Tracks Not By My Top 50 Artists - Butler": "SELECT track_json FROM saved_tracks WHERE" +
                " json_extract(track_json, '$.artists[0].id') NOT IN (SELECT id FROM top_artists)",
            // "Saved Tracks Not In My Top 50 Tracks - Butler": "",
            // "1960 - Butler Created": "",
            // "1970 - Butler Created": "",
            // "1980 - Butler Created": "",
            // "1990 - Butler Created": "",
            // "2000 - Butler Created": "",
            // "2010 - Butler Created": "",
            // "2020 - Butler Created": "",
        }
        const playlistResults = this.getResultsForPlaylistQueries(playlistQueries)

        for (let playlistName in playlistResults) {
            const playlistInfo = playlistResults[playlistName]
            if (playlistInfo.playlistId == null) {
                // TODO: figure out how to parallelize all my edits... probably a similar loop as above
                const newPlaylist = await this.library.createPlaylistWithName(playlistName)
                await this.library.addTracksToPlaylist(newPlaylist.id, playlistInfo.allTracks)
                const trackNames = playlistInfo.allTracks.map(x => x.name)
                console.log(`Created new playlist with name ${playlistName} and the following ${trackNames.length} tracks: ${JSON.stringify(trackNames)}`)
            } else {
                // TODO: add a way to do shuffle without removing/re-adding, if possible...
                const addedNames = playlistInfo.addedTracks.map(x => x.name)
                const removedNames = playlistInfo.removedTracks.map(x => x.name)
                const changes = []
                if (playlistInfo.addedTracks.length > 0) {
                    changes.push(this.library.addTracksToPlaylist(playlistInfo.playlistId, playlistInfo.addedTracks))
                }
                if (playlistInfo.removedTracks.length > 0) {
                    changes.push(this.library.removeTracksFromPlaylist(playlistInfo.playlistId, playlistInfo.removedTracks))
                }
                await Promise.all(changes)
                console.log(`For playlist with name ${playlistName} we added the following ${addedNames.length} tracks ${JSON.stringify(addedNames)}
                   and removed the following ${removedNames.length}  tracks ${JSON.stringify(removedNames)}`)
            }
            // TODO: build an object we can turn into an HTML response...
        }
    }

    getResultsForPlaylistQueries(playlistQueries: Record<string, string>): Record<string, NewPlaylistInfo> {
        const playlistResults: Record<string, NewPlaylistInfo> = {}
        for (let playlistName in playlistQueries) {
            const allTracks = this.db.prepare(playlistQueries[playlistName]).all().map(x =>
                Deserialize(JSON.parse(x.track_json), Track)
            )

            // TODO: now, based on this... look for any existing ones in the playlist track table and find the
            //  change sets!
            const oldTracks = this.db.prepare("SELECT track_json FROM playlist_tracks WHERE playlist_name =" +
                " @playlist_name").all({playlist_name: playlistName})
                .map(x => Deserialize(JSON.parse(x.track_json), Track))
            const playlistId = this.db.prepare("SELECT id FROM playlists WHERE name = @playlist_name")
                .get({playlist_name: playlistName})
                ?.id
            if (playlistId) {
                const [addedTracks, removedTracks] = this.getAddedAndRemovedTracks(oldTracks, allTracks)
                playlistResults[playlistName] = new NewPlaylistInfo(
                    playlistName,
                    allTracks,
                    playlistId,
                    oldTracks,
                    addedTracks,
                    removedTracks
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
        // TODO: what's URI vs ID going to do here...? will that help with reconciling multiple entries?
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
            " track_json) VALUES (@name, @id, @href, @uri, @added_at, @track_json)")
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

        const playlistQuery = this.db.prepare("INSERT INTO playlists (name, id, href, uri, tracks_href) " +
            "VALUES (@name, @id, @href, @uri, @tracks_href)")
        const insertManyPlaylist = this.db.transaction((tracks) => {
            for (const track of tracks) playlistQuery.run(track);
        })
        insertManyPlaylist(playlists.map(x => {
            return {
                name: x.name,
                id: x.id,
                href: x.href,
                uri: x.uri,
                tracks_href: x.tracks.href
            }
        }));

        const playlistTracksQuery = this.db.prepare("INSERT INTO playlist_tracks (playlist_name, added_at, name, " +
            "id, href, uri, track_json) VALUES (@playlist_name, @added_at, @name, @id, @href, @uri," +
            " @track_json)")
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
                    track_json: JSON.stringify(x.track)
                }
            }))
        }

        // TODO: only do this on debug
        // let x = this.db.prepare("SELECT playlist_name, count(*) from playlist_tracks group by playlist_name").all();
        // console.log(JSON.stringify(x))
    }
}