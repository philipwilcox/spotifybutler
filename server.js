const http = require('http');
const fs = require('fs');
const crypto = require('crypto')
const querystring = require('querystring')

const hostname = '127.0.0.1';
const port = 8888;

// Expects a file with a json body with three keys: 'client_id', 'client_secret', 'redirect_uri'
const secrets = JSON.parse(fs.readFileSync('secrets.json'))

const server = http.createServer((req, res) => {
    const path = req.url.split('?')[0]
    switch (path) {
        case '/callback':
            processCallback(req, res);
            break;
        case '/start':
            makeAuthRequest(res);
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

function processCallback(req, res) {
    const callbackQuerystring = req.url.split('?')[1]
    const callbackParams = querystring.parse(callbackQuerystring)
    const callbackCode = callbackParams['code']
    const callbackState = callbackParams['state']

    res.statusCode = 200
    res.setHeader('Content-Type', 'text/plain');
    res.end(`Hi this is the callback code: ${callbackCode} with state: ${callbackState}`);
}

function makeAuthRequest(res) {
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