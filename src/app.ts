import Library from "./lib/spotify-clients/library";
import {LibraryTrack} from "./lib/models/spotify/library-track.js";
import {Artist} from "./lib/models/spotify/artist.js";
import {Playlist} from "./lib/models/spotify/playlist.js";
import {Track} from "./lib/models/spotify/track.js";
import {Database} from "better-sqlite3";
import asyncPool from "tiny-async-pool";

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
        await asyncPool(3, myPlaylists, (p: Playlist) => {
            console.log(`Will try to fetch ${p.tracks.total} tracks for ${p.name} from ${p.tracks.href}`)
            // TODO: make this info level
            return this.library.getTracksForPlaylist(p.tracks.href).then(x => {
                tracksForPlaylists[p.name] = x
                console.log(`Found ${x.length} tracks for playlist ${p.name}`)
                if (x.length != p.tracks.total) throw new Error(`Expected ${p.tracks.total} tracks for ${p.name}, got ${x.length}`)
            })
        })

        const endPlaylistMs = Date.now()
        console.log(`Took ${endPlaylistMs - startPlaylistMs} milliseconds to fetch all playlist tracks`);
        // 41s for serial fetch, 36s... - in parallel, 2476!


        // Load all this into db
        this.loadTracksAndPlaylistsIntoDb(mySavedTracks, myTopTracks, myTopArtists, myPlaylists);
    }

    loadTracksAndPlaylistsIntoDb(savedTracks: LibraryTrack[], topTracks: Track[], topArtists: Artist[], playlists: Playlist[]) {
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

        // TODO: add playlist track records

    }
}