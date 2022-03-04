import crypto from 'crypto'
import {ServerResponse} from "http";
import https from "https";

export default class SpotifyAuth {
    private readonly spotify_accounts_hostname: string;
    private readonly client_id: string;
    private readonly client_secret: string;
    private readonly redirect_uri: string;

    constructor(spotify_accounts_hostname: string, client_id: string, client_secret: string, redirect_uri: string) {
        this.spotify_accounts_hostname = spotify_accounts_hostname;
        this.client_id = client_id;
        this.client_secret = client_secret;
        this.redirect_uri = redirect_uri;
    }

    /**
     * This will redirect the user over to Spotify to log in and grant permission (or redirect back to
     * our callback page here if permission is already granted).
     */
    initialAuthRequest(res: ServerResponse) {
        const stateKey = 'spotify_auth_state'
        const state = crypto.randomBytes(12).toString('base64')
        res.setHeader('Set-Cookie', [`${stateKey}=${state}`]);

        const scope = 'user-read-private user-read-email user-top-read user-read-recently-played playlist-read-private user-library-read playlist-modify-private';
        res.writeHead(302, {
            'Location': `https://${this.spotify_accounts_hostname}/authorize?` +
                new URLSearchParams({
                    response_type: 'code',
                    client_id: this.client_id,
                    scope: scope,
                    redirect_uri: this.redirect_uri,
                    state: state
                }).toString()
        })
        res.end()
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
    async getAccessTokenFromCallback(req, res) {
        const callbackQuerystring = req.url.split('?')[1]
        const callbackParams = new URLSearchParams(callbackQuerystring)
        // TODO: how to put typing on this internal stuff?
        const callbackCode = callbackParams.get("code")
        // We don't check the callback state value because we're just running a local server-side script that calls back to localhost

        const tokenPayload = await this.getAccessAndRefreshTokens(callbackCode)
        // TODO: need to know type of return JSON for this...
        // @ts-ignore
        return tokenPayload.access_token
    }


    // TODO: can I make the below one private?

    /**
     * This returns a promise for a POST call to exchange an authorization code for an access token
     */
    getAccessAndRefreshTokens(callbackCode: string) {
        const formBody = new URLSearchParams({
            code: callbackCode,
            redirect_uri: this.redirect_uri,
            grant_type: 'authorization_code'
        }).toString()

        const authorization = `Basic ${Buffer.from(this.client_id + ':' + this.client_secret).toString('base64')}`
        const options = {
            hostname: this.spotify_accounts_hostname,
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
}



