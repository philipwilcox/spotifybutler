import Library from "./lib/spotify-clients/library";
import {LibraryTrack} from "./lib/models/spotify/library-track.js";
import {Artist} from "./lib/models/spotify/artist.js";
import {Playlist} from "./lib/models/spotify/playlist.js";
import {Track} from "./lib/models/spotify/track.js";
import {Database} from "better-sqlite3";
import asyncPool from "tiny-async-pool";
import {Deserialize} from "cerialize";
import NewPlaylistInfo from "./lib/models/new-playlist-info.js";

export default class App {
    private library: Library
    private db: Database

    constructor(library: Library, db: Database) {
        this.library = library
        this.db = db
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
        const tracksForPlaylists: Record<string, Track[]> = {}
        // Parallel fetch at level two cuts this from like 40 seconds to 20 for my playlists as of Mar 2022, but
        // unfortunately higher hits a rate limit sometimes.
        // await asyncPool(2, myPlaylists, (p: Playlist) => {
        //     console.debug(`Will try to fetch ${p.tracks.total} tracks for ${p.name} from ${p.tracks.href}`)
        //     // TODO: wrap console in a proper level-having logger...
        //     return this.library.getTracksForPlaylist(p.tracks.href).then(x => {
        //         tracksForPlaylists[p.name] = x
        //         console.info(`Found ${x.length} tracks for playlist ${p.name}`)
        //         if (x.length != p.tracks.total) throw new Error(`Expected ${p.tracks.total} tracks for ${p.name}, got ${x.length}`)
        //     })
        // })

        const endPlaylistMs = Date.now()
        console.log(`Took ${endPlaylistMs - startPlaylistMs} milliseconds to fetch all playlist tracks`);


        // Load all this into db
        this.loadTracksAndPlaylistsIntoDb(mySavedTracks, myTopTracks, myTopArtists, myPlaylists, tracksForPlaylists);

        // Come up with new playlist contents based on the following queries!
        // TODO: check how well date sorting works here... maybe convert timestamps before storing...
        const playlistQueries = {
            // TODO: do some deduping based on artist/name/id type stuff in the DB...
            "100 Most Recent Liked Songs": "SELECT track_json FROM saved_tracks ORDER BY added_at DESC LIMIT 100"
        }
        const playlistResults = this.getResultsForPlaylistQueries(playlistQueries)

        for (let playlistName in playlistResults) {
            const playlistInfo = playlistResults[playlistName]
            if (playlistInfo.playlistId == null) {
                // TODO: figure out how to parallelize all my edits... probably a similar loop as above
                const playlistId = await this.library.createPlaylistWithName(playlistName)
                await this.library.addTracksToPlaylist(playlistId, playlistInfo.allTracks)
                const trackNames = playlistInfo.allTracks.map(x => x.name)
                console.log(`Created new playlist with name ${playlistName} and the following tracks: ${JSON.stringify(trackNames)}`)
            }
            // TODO: have an "update playlist" method that takes the shuffle arg stuff..
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
                console.log("HIIII")
                throw new Error("TODO NOT IMPLEMENTED")
            } else {
                playlistResults[playlistName] = new NewPlaylistInfo(playlistName, allTracks)
            }
        }
        return playlistResults
    }

    loadTracksAndPlaylistsIntoDb(savedTracks: LibraryTrack[], topTracks: Track[], topArtists: Artist[],
                                 playlists: Playlist[], tracksForPlaylists: Record<string, Track[]>) {
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

        const playlistTracksQuery = this.db.prepare("INSERT INTO playlist_tracks (playlist_name, name, id, " +
            "href, uri, track_json) VALUES (@playlist_name, @name, @id, @href, @uri, @track_json)")
        const insertManyPlaylistTrack = this.db.transaction((playlistTracks) => {
            for (const track of playlistTracks) playlistTracksQuery.run(track);
        })
        for (let k in tracksForPlaylists) {
            const tracks = tracksForPlaylists[k]
            insertManyPlaylistTrack(tracks.map(x => {
                return {
                    playlist_name: k,
                    name: x.name,
                    id: x.id,
                    href: x.href,
                    uri: x.uri,
                    track_json: JSON.stringify(x)
                }
            }))
        }

        // TODO: only do this on debug
        let x = this.db.prepare("SELECT playlist_name, count(*) from playlist_tracks group by playlist_name").all();
        console.log(JSON.stringify(x))
    }
}