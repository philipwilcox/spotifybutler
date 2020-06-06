
const querystring = require('querystring')
const https = require('https');
const constants = require('./constants')

module.exports = {
    /**
     * This returns a promise for a GET call to the endpoint described at
     * https://developer.spotify.com/documentation/web-api/reference/library/get-users-saved-tracks/
     */
    getMySavedTracks: async function (accessToken) {
        let response = await getPageOfTracks(accessToken, 50, 0)
        let accumulatedResponses = [response]
        while (response.next) {
            console.log(`fetching next page from ${response.next}`)
            const nextArgs = querystring.parse(response.next.split('?')[1])

            // if we have a limit set, break here if we exceed it
            if (constants.TRACK_FETCH_LIMIT && nextArgs.offset > constants.TRACK_FETCH_LIMIT) break;

            response = await getPageOfTracks(accessToken, nextArgs.limit, nextArgs.offset)
            accumulatedResponses.push(response)
        }

        const allItems = accumulatedResponses.map(x => x.items).flat()
        return allItems
    }
};

const getPageOfTracks = function(accessToken, limit, offset) {
    const params = querystring.stringify({
        limit: limit,
        offset: offset
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
                const payload = JSON.parse(response);
                //console.log(`done with tracks get! response was ${response}`);
                resolve(payload);
            })
        })
        req.on('error', error => {
            reject(error)
        })
        req.end()
    });
}