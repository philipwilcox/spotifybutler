import http, {ServerResponse} from 'http'
import constants from './constants.js'
import Library from './lib/spotify-clients/library.js'
import secrets from './secrets.js'
import SpotifyAuth from './lib/spotify-clients/spotify-auth.js'
import App from "./app.js";
import Database from 'better-sqlite3';


/***
 * Next steps
 * TODO: add other new limited-count playlist ideas
 * TODO: add some deduping inspection/logic around the playlists
 * TODO: investigate why sometimes we add/remove slightly different numbers when modifying the "five per artist" lists
 * TODO: bring back remaining old ones
 * TODO: try to do an in-place shuffle method
 * TODO: integrate with lastfm
 */

/**
 * This will start a server with just a few endpoints (since UI is less important to us than iterating through the
 * data).
 *
 * The `/start` path will start the authorization + data fetching process.
 *
 * The `/callback` path is where a user will be sent back to after logging in on the Spotify site.
 *
 * The Spotify auth flow is described at https://developer.spotify.com/documentation/general/guides/authorization-guide/
 * under "Authorization Code Flow."
 */
let newLogin = false;
const server = http.createServer(async (req, res) => {
    const spotifyAuth = new SpotifyAuth(constants.SPOTIFY.SPOTIFY_ACCOUNTS_HOSTNAME, secrets.client_id, secrets.client_secret, secrets.redirect_uri)
    const path = req.url.split('?')[0]
    switch (path) {
        case '/callback':
            // If we saved a previous access_token in our secrets file, we can bypass the first step until it expires!
            if (secrets.access_token && !newLogin) {
                console.log(`Using stored access token ${secrets.access_token}`)
                // Once we're here, the application begins!
                buildResponse(secrets.access_token, res)
            } else {
                const accessToken = await spotifyAuth.getAccessTokenFromCallback(req, res);
                console.log(`Got access token: ${accessToken}`)
                // Once we're here, the application begins!
                buildResponse(accessToken, res)
            }
            break;
        case '/start':
            console.log("Starting auth flow!")
            spotifyAuth.initialAuthRequest(res)
            newLogin = true
            break;
        default:
            res.statusCode = 200;
            res.end('Hello World!');
    }
});

server.listen(constants.SERVER.PORT, constants.SERVER.HOSTNAME, () => {
    console.log(`Server running at http://${constants.SERVER.HOSTNAME}:${constants.SERVER.PORT}/`);
    console.log(`Initialized with client ID ${secrets.client_id}`)
});

async function buildResponse(accessToken: string, res: ServerResponse) {
    const library = new Library(constants.SPOTIFY.SPOTIFY_API_HOSTNAME, constants.SPOTIFY.PAGED_ITEM_FETCH_LIMIT, accessToken);
    const db = createDatabase();
    const app = new App(library, db, constants.APP.MIN_YEAR_FOR_DISCOVER_WEEKLY, constants.APP.DRY_RUN)
    // Once we're here, the main logic begins!
    const stringResult = await app.runButler();
    res.statusCode = 200
    res.setHeader("Content-Type", "text/plain")
    res.end(stringResult)
}

// TODO: modularize how I use this?
function createDatabase() {
    const db = new Database(constants.SQLITE.DB_FILE);

    // TODO: add a distinct constraint on playlist name
    const tableCreations = ["CREATE TABLE top_artists (name TEXT, id TEXT, href TEXT, uri TEXT)",
        "CREATE TABLE top_tracks (name TEXT, id TEXT, href TEXT, uri TEXT, track_json JSON)",
        "CREATE TABLE saved_tracks (name TEXT, id TEXT, primary_artist_id TEXT, release_date TEXT, release_year" +
        " NUMERIC, href TEXT, uri TEXT, added_at TEXT, track_json JSON)",
        "CREATE TABLE playlists (name TEXT, id TEXT, href TEXT, uri TEXT, tracks_href TEXT, snapshot_id TEXT)",
        "CREATE TABLE playlist_tracks (playlist_name TEXT, added_at TEXT, release_date TEXT, release_year NUMERIC," +
        " name TEXT, primary_artist_id TEXT, id TEXT, href TEXT, uri TEXT, track_json JSON)"
    ]

    tableCreations.map(query => db.prepare(query).run())

    return db;
}
