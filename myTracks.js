
const querystring = require('querystring')
const https = require('https');
const constants = require('./constants')

module.exports = {
    /**
     * This returns a promise for a GET call to the endpoint described at
     * https://developer.spotify.com/documentation/web-api/reference/library/get-users-saved-tracks/
     */
    getMySavedTracks: function (accessToken) {
        const params = querystring.stringify({
            limit: 50,
            offset: 0
        })
        const options = {
            hostname: constants.SPOTIFY_API_HOSTNAME,
            path: `/v1/me/tracks?${params}`,
            method: 'GET',
            headers: {
                'Authorization': `Bearer ${accessToken}`
            }
        }
        return new Promise((resolve, reject) => {
            var response = ""
            const req = https.request(options, res => {
                res.on('data', data => {
                    response += data
                })

                res.on('end', () => {
                    // TODO: convert this to a loop to fetch all
                    const payload = JSON.parse(response);
                    console.log(`done with tracks get! response was ${response}`);
                    resolve(response);
                })
            })
            req.on('error', error => {
                reject(error)
            })
            req.end()
        });
    }
};