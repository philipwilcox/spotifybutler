import {ServerResponse} from "http";
import Library from "./lib/spotify-clients/library";
import constants from "./constants";
import sqlite3 from "sqlite3";

export default class App {
    private library: Library
    private db: sqlite3.Database

    constructor(library: Library, db: sqlite3.Database) {
        this.library = library
        this.db = db
    }

    async runButler() {
        // TODO: another way of doing the typing for these would be using "@types/spotify-api"
        const [
            mySavedTracks,
            topTracks,
            topArtists
        ] = await Promise.all([
            this.library.getMySavedTracks(),
            this.library.getMyTopTracks(),
            this.library.getMyTopArtists()
        ]);

        console.log(`I got ${mySavedTracks.length} saved tracks!`)
        // get my playlists of interest

        // Load all this into
    }
}