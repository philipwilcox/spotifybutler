import http, {ServerResponse} from 'http'
import constants from './constants.js'
import Library from './lib/spotify-clients/library.js'
import secrets from './secrets.js'
import SpotifyAuth from './lib/spotify-clients/spotify-auth.js'
import App from "./app.js";
import Database from 'better-sqlite3';


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
        "CREATE TABLE playlists (name TEXT, id TEXT, href TEXT, uri TEXT, tracks_href TEXT)",
        "CREATE TABLE playlist_tracks (playlist_name TEXT, added_at TEXT, release_date TEXT, release_year NUMERIC," +
        " name TEXT, primary_artist_id TEXT, id TEXT, href TEXT, uri TEXT, track_json JSON)"
    ]

    tableCreations.map(query => db.prepare(query).run())

    return db;
}


//
// function tracksByDecadeToString(tracksByDecade) {
//     const decadeArray = Array.from(tracksByDecade.keys()).sort()
//     let trackStrings = ""
//     decadeArray.map(decade => {
//         trackStrings += `\n\n${decade}`
//         const decadeTracks = tracksByDecade.get(decade)
//         decadeTracks.map(x => {
//             trackStrings += `\n   ${x.track.name} (${x.track.artists[0].name} - ${x.track.album.name} (${x.track.album.release_date})) (# ${x.track.id}) from ${x.added_at}`
//         })
//     })
//
//     const resultString = `Hi, here are all the tracks by decade:
// ${trackStrings}
//     `
//     return resultString;
// }
//
// async function fetchTracksAndBuildResponse(accessToken, res) {
//     const [
//         mySavedTracks,
//         topTracks,
//         topArtists
//     ] = await Promise.all([
//         Library.getMySavedTracks(accessToken),
//         Library.getMyTopTracks(accessToken),
//         Library.getMyTopArtists(accessToken)
//     ]);
//     const tracksByDecade = TrackSorting.groupTracksByDecade(mySavedTracks)
//     // TODO: maybe add a "top track count" limit here too?
//     // TODO: how am I getting dupes in here?
//     const savedTracksNotInTop50Tracks = TrackSorting.trackListWithoutOtherList(mySavedTracks, topTracks)
//     // Note that the API won't give me more than top 50 artists
//     // TODO: note that the API now lets you pass in different "time ranges" like "long_term" vs "medium_term", update to use this! default is medium_term, about six months
//     const savedTracksNotByTop50Artists = TrackSorting.trackListNotByArtists(mySavedTracks, topArtists, 50)
//     const savedTracksNotByTop25Artists = TrackSorting.trackListNotByArtists(mySavedTracks, topArtists, 25)
//     const savedTracksNotByTop10Artists = TrackSorting.trackListNotByArtists(mySavedTracks, topArtists, 10)
//     const savedTracksByTop20Artists = TrackSorting.trackListByArtists(mySavedTracks, topArtists, 15)
//
//
//     // TODO: liked songs that aren't in recent plays
//     // TODO: liked songs that aren't from followed artists
//
//     // TODO: refactor to flatten out the "track lists" i'm passing around here to not have the "added_at" wrapper layer before sending to playlist module
//
//
//     // Scrape discover weekly for any new stuff each time
//     // TODO: could improve playlist API here for sure
//     const discoverWeeklyPlaylist = await Playlists.getPlaylistAndTracksByName( "Discover Weekly", accessToken)
//     const currentDiscoverWeeklyCollectedTracks = await Playlists.getPlaylistAndTracksByName( `Collected Discover Weekly ${MIN_YEAR_FOR_DISCOVER_WEEKLY} And On - Butler`, accessToken)
//     const existingTrackUris = new Set(currentDiscoverWeeklyCollectedTracks.tracks.map(x => x.track.uri))
//     const filteredTracks = discoverWeeklyPlaylist.tracks
//         .filter(x => Number(x.track.album.release_date.split('-')[0]) >= MIN_YEAR_FOR_DISCOVER_WEEKLY)
//         .filter(x => !existingTrackUris.has(x.track.uri));
//     const concatenatedTracks = currentDiscoverWeeklyCollectedTracks.tracks.concat(filteredTracks);
//     const discoverWeeklyCollectedChangesList = await Playlists.savePlaylistByName(`Collected Discover Weekly ${MIN_YEAR_FOR_DISCOVER_WEEKLY} And On - Butler`, concatenatedTracks, accessToken)
//     // TODO: disable shuffling for this one
//
//
//     const changesListsSoFar = []
//     // for (const [decade, trackList] of tracksByDecade) {
//     //     changesListsSoFar.push(await Playlists.savePlaylistByName(`${decade} - Butler Created`, trackList, accessToken))
//     // } // TODO: could map this to an array of promises that I then await all of...
//
//     // TODO: investigate why it's currently reporting adding a lot to these each time...
//     const otherChangesList = await Promise.all([
//         Playlists.savePlaylistByName(`Saved Tracks Not In My Top 50 Tracks - Butler`, savedTracksNotInTop50Tracks, accessToken),
//         // Playlists.savePlaylistByName(`Saved Tracks Not By My Top 50 Artists - Butler`, savedTracksNotByTop50Artists, accessToken),
//         // Playlists.savePlaylistByName(`Saved Tracks Not By My Top 25 Artists - Butler`, savedTracksNotByTop25Artists, accessToken),
//         // Playlists.savePlaylistByName(`Saved Tracks Not By My Top 10 Artists - Butler`, savedTracksNotByTop10Artists, accessToken),
//         // Playlists.savePlaylistByName(`Saved Tracks By My Top 20 Artists - Butler`, savedTracksByTop20Artists, accessToken),
//     ])
//
//     const allChangesList = changesListsSoFar.concat(otherChangesList)
//
//     const changesString = allChangesList
//         .map(changes => `For playlist ${changes.name} (${changes.newLength} tracks)\n   Added tracks: ${changes.added.map(x => x.track.name)}\n   Removed tracks: ${changes.removed.map(x => x.track.name)}`)
//         .join("\n\n")
//
//     const resultString = changesString + "\n\n\n" + tracksByDecadeToString(tracksByDecade);
//
//     res.statusCode = 200
//     res.setHeader('Content-Type', 'text/plain');
//     res.end(resultString);
// }
//

