import http from 'http'
import https from 'https'
import fs from 'fs'
import crypto from 'crypto'
import querystring from 'querystring'


const hostname = '127.0.0.1';
const port = 8888;

const MIN_YEAR_FOR_DISCOVER_WEEKLY = 2016;

import constants from './constants.js'
import Library from './apiClients/library.js'
import Playlists from './apiClients/playlists.js'
import TrackSorting from './trackSorting.js'

// Expects a file with a json body with three keys: 'client_id', 'client_secret', 'redirect_uri'
const secrets = JSON.parse(fs.readFileSync('secrets.json'))

/**
 * This will start a server with just a few endpoints (since UI is less important to us than iterating through the data).
 *
 * The `/start` path will start the authorization + data fetching process.
 *
 * The `/callback` path is where a user will be sent back to after logging in on the Spotify site.
 *
 * The Spotify auth flow is described at https://developer.spotify.com/documentation/general/guides/authorization-guide/
 * under "Authorization Code Flow."
 */
const server = http.createServer(async (req, res) => {
    const path = req.url.split('?')[0]
    switch (path) {
        case '/callback':
            const accessToken = await getAccessTokenFromCallback(req, res);
            console.log(`Got access token: ${accessToken}`)
            await fetchTracksAndBuildResponse(accessToken, res);
            break;
        case '/start':
            // If we saved a previous access_token in our secrets file, we can bypass the first step until it expires!
            if (secrets.access_token) {
                console.log(`Using stored access token ${secrets.access_token}`)
                await fetchTracksAndBuildResponse(secrets.access_token, res);
            } else {
                makeInitialAuthRequest(res);
            }
            break;
        default:
            res.statusCode = 200;
            res.end('Hello World!');
    }
});

server.listen(port, hostname, () => {
    console.log(`Server running at http://${hostname}:${port}/`);
    console.log(`Initialized with client ID ${secrets.client_id}`)
});

function tracksByDecadeToString(tracksByDecade) {
    const decadeArray = Array.from(tracksByDecade.keys()).sort()
    var trackStrings = ""
    decadeArray.map(decade => {
        trackStrings += `\n\n${decade}`
        const decadeTracks = tracksByDecade.get(decade)
        decadeTracks.map(x => {
            trackStrings += `\n   ${x.track.name} (${x.track.artists[0].name} - ${x.track.album.name} (${x.track.album.release_date})) (# ${x.track.id}) from ${x.added_at}`
        })
    })

    const resultString = `Hi, here are all the tracks by decade:
${trackStrings}
    `
    return resultString;
}

async function fetchTracksAndBuildResponse(accessToken, res) {
    const [
        mySavedTracks,
        topTracks,
        topArtists
    ] = await Promise.all([
        Library.getMySavedTracks(accessToken),
        Library.getMyTopTracks(accessToken),
        Library.getMyTopArtists(accessToken)
    ]);
    const tracksByDecade = TrackSorting.groupTracksByDecade(mySavedTracks)
    // TODO: maybe add a "top track count" limit here too?
    const savedTracksNotInTop50Tracks = TrackSorting.trackListWithoutOtherList(mySavedTracks, topTracks)
    // Note that the API won't give me more than top 50 artists
    // TODO: note that the API now lets you pass in different "time ranges" like "long_term" vs "medium_term", update to use this! default is medium_term, about six months
    const savedTracksNotByTop50Artists = TrackSorting.trackListNotByArtists(mySavedTracks, topArtists, 50)
    const savedTracksNotByTop25Artists = TrackSorting.trackListNotByArtists(mySavedTracks, topArtists, 25)
    const savedTracksNotByTop10Artists = TrackSorting.trackListNotByArtists(mySavedTracks, topArtists, 10)
    const savedTracksByTop20Artists = TrackSorting.trackListByArtists(mySavedTracks, topArtists, 15)


    // TODO: liked songs that aren't in recent plays
    // TODO: liked songs that aren't from followed artists

    // TODO: refactor to flatten out the "track lists" i'm passing around here to not have the "added_at" wrapper layer before sending to playlist module


    // Scrape discover weekly for any new stuff each time
    // TODO: could improve playlist API here for sure
    const discoverWeeklyPlaylist = await Playlists.getPlaylistAndTracksByName( "Discover Weekly", accessToken)
    const currentDiscoverWeeklyCollectedTracks = await Playlists.getPlaylistAndTracksByName( `Collected Discover Weekly ${MIN_YEAR_FOR_DISCOVER_WEEKLY} And On - Butler`, accessToken)
    const existingTrackUris = new Set(currentDiscoverWeeklyCollectedTracks.tracks.map(x => x.track.uri))
    const filteredTracks = discoverWeeklyPlaylist.tracks
        .filter(x => Number(x.track.album.release_date.split('-')[0]) >= MIN_YEAR_FOR_DISCOVER_WEEKLY)
        .filter(x => !existingTrackUris.has(x.track.uri));
    const concatenatedTracks = currentDiscoverWeeklyCollectedTracks.tracks.concat(filteredTracks);
    const discoverWeeklyCollectedChangesList = await Playlists.savePlaylistByName(`Collected Discover Weekly ${MIN_YEAR_FOR_DISCOVER_WEEKLY} And On - Butler`, concatenatedTracks, accessToken)
    // TODO: disable shuffling for this one


    const changesListsSoFar = [discoverWeeklyCollectedChangesList]
    for (const [decade, trackList] of tracksByDecade) {
        changesListsSoFar.push(await Playlists.savePlaylistByName(`${decade} - Butler Created`, trackList, accessToken))
    } // TODO: could map this to an array of promises that I then await all of...

    // TODO: investigate why it's currently reporting adding a lot to these each time...
    const otherChangesList = await Promise.all([
        Playlists.savePlaylistByName(`Saved Tracks Not In My Top 50 Tracks - Butler`, savedTracksNotInTop50Tracks, accessToken),
        Playlists.savePlaylistByName(`Saved Tracks Not By My Top 50 Artists - Butler`, savedTracksNotByTop50Artists, accessToken),
        Playlists.savePlaylistByName(`Saved Tracks Not By My Top 25 Artists - Butler`, savedTracksNotByTop25Artists, accessToken),
        Playlists.savePlaylistByName(`Saved Tracks Not By My Top 10 Artists - Butler`, savedTracksNotByTop10Artists, accessToken),
        Playlists.savePlaylistByName(`Saved Tracks By My Top 20 Artists - Butler`, savedTracksByTop20Artists, accessToken),
    ])

    const allChangesList = changesListsSoFar.concat(otherChangesList)

    const changesString = allChangesList
        .map(changes => `For playlist ${changes.name} (${changes.newLength} tracks)\n   Added tracks: ${changes.added.map(x => x.track.name)}\n   Removed tracks: ${changes.removed.map(x => x.track.name)}`)
        .join("\n\n")

    const resultString = changesString + "\n\n\n" + tracksByDecadeToString(tracksByDecade);

    res.statusCode = 200
    res.setHeader('Content-Type', 'text/plain');
    res.end(resultString);
}

/**
 * This will handle the initial callback from the user logging into Spotify. At this point we'll have (in the URL),
 * a code param and a copy of the state value we sent.
 *
 * The `state` value is for protection against CSRF and since we're running locally here more as a user script,
 * we aren't too concerned with it.
 *
 * We will then call Spotify again to exchange that code for access token + refresh token, and then return just the
 * access token since we're a short-lived application.
 */
async function getAccessTokenFromCallback(req, res) {
    const callbackQuerystring = req.url.split('?')[1]
    const callbackParams = querystring.parse(callbackQuerystring)
    const callbackCode = callbackParams['code']
    // We don't check the callback state value because we're just running a local server-side script that calls back to localhost

    const tokenPayload = await getAccessAndRefreshTokens(callbackCode)
    return tokenPayload.access_token
}

/**
 * This will redirect the user over to Spotify to log in and grant permission (or redirect back to
 * our callback page here if permission is already granted).
 */
function makeInitialAuthRequest(res) {
    const stateKey = 'spotify_auth_state'
    const state = crypto.randomBytes(12).toString('base64')
    res.setHeader('Set-Cookie', [`${stateKey}=${state}`]);

    const scope = 'user-read-private user-read-email user-top-read user-read-recently-played playlist-read-private user-library-read playlist-modify-private';
    res.writeHead(302, {
        'Location': `https://${constants.SPOTIFY_ACCOUNTS_HOSTNAME}/authorize?` +
        querystring.stringify({
            response_type: 'code',
            client_id: secrets.client_id,
            scope: scope,
            redirect_uri: secrets.redirect_uri,
            state: state
        })})
    res.end()
}

/**
 * This returns a promise for a POST call to exchange an authorization code for an access token
 */
function getAccessAndRefreshTokens(callbackCode) {
    const formBody = querystring.stringify({
        code: callbackCode,
        redirect_uri: secrets.redirect_uri,
        grant_type: 'authorization_code'
    })

    const authorization = `Basic ${Buffer.from(secrets.client_id + ':' + secrets.client_secret).toString('base64')}`
    const options = {
        hostname: constants.SPOTIFY_ACCOUNTS_HOSTNAME,
        path: '/api/token',
        method: 'POST',
        headers: {
            'Authorization': authorization,
            'Content-Type': 'application/x-www-form-urlencoded',
            'Content-Length': formBody.length
        }
    }

    return new Promise((resolve, reject) => {
        const req = https.request(options, res => {
            var response = ""

            res.on('data', data => {
                response += data
            })

            res.on('end', () => {
                const tokenPayload = JSON.parse(response);
                console.log(`done with post! response was ${response}`);
                resolve(tokenPayload);
            })

            res.on('error', (error) => {
                console.log(`error is ${error}`)
                reject(error);
            });
        })

        // TODO: https://nodejs.dev/learn/making-http-requests-with-nodejs this shows this outside the block here,
        // but other examples have a res.on instead inside the above block?
        req.on('error', error => {
            console.error(error);
            reject(error);
        })

        req.write(formBody);
        req.end();
    })
}