const http = require('http');
const https = require('https');
const fs = require('fs');
const crypto = require('crypto')
const querystring = require('querystring')

const hostname = '127.0.0.1';
const port = 8888;

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
const server = http.createServer((req, res) => {
    const path = req.url.split('?')[0]
    switch (path) {
        case '/callback':
            processCallback(req, res);
            break;
        case '/start':
            makeInitialAuthRequest(res);
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

/**
 * This will handle the initial callback from the user logging into Spotify. At this point we'll have (in the URL),
 * a code param and a copy of the state value we sent.
 *
 * The `state` value is for protection against CSRF and since we're running locally here more as a user script,
 * we aren't too concerned with it.
 *
 * We will then call Spotify again to exchange that code for access token + refresh token.
 */
function processCallback(req, res) {
    const callbackQuerystring = req.url.split('?')[1]
    const callbackParams = querystring.parse(callbackQuerystring)
    const callbackCode = callbackParams['code']
    const callbackState = callbackParams['state']

    res.statusCode = 200
    res.setHeader('Content-Type', 'text/plain');
    res.end(`Hi this is the callback code: ${callbackCode} with state: ${callbackState}`);

    const formBody = querystring.stringify({
        code: callbackCode,
        redirect_uri: secrets.redirect_uri,
        grant_type: 'authorization_code'
    })

    const authorization = `Basic ${Buffer.from(secrets.client_id + ':' + secrets.client_secret).toString('base64')}`
    console.log(`authorization: ${authorization}`)
    const options = {
        hostname: 'accounts.spotify.com',
        //port: 443,
        path: '/api/token',
        method: 'POST',
        headers: {
            'Authorization': authorization,
            'Content-Type': 'application/x-www-form-urlencoded',
            'Content-Length': formBody.length
        }
    }


    let postRequestPromise = new Promise((resolve, reject) => {
        const req = https.request(options, res => {
            var response = ""

            res.on('data', data => {
                response += data
            })

            res.on('end', () => {
                resolve(response);
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

        req.write(formBody)
        req.end()
    })
    postRequestPromise.then(value => {
        console.log(`done with post! value is ${value}`)
    })
}

/**
 * This will redirect the user over to Spotify to log in and grant permission (or redirect back to
 * our callback page here if permission is already granted).
 */
function makeInitialAuthRequest(res) {
    const stateKey = 'spotify_auth_state'
    const state = crypto.randomBytes(12).toString('base64')
    res.setHeader('Set-Cookie', [`${stateKey}=${state}`]);
    //res.cookie(stateKey, state)

    const scope = 'user-read-private user-read-email';
    res.writeHead(302, {
        'Location': 'https://accounts.spotify.com/authorize?' +
        querystring.stringify({
            response_type: 'code',
            client_id: secrets.client_id,
            scope: scope,
            redirect_uri: secrets.redirect_uri,
            state: state
        })})
    res.end()
}