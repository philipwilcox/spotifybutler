const querystring = require('querystring')
const https = require('https');
const constants = require('./constants')

/**
 * This is a library for interacting with the often-paginated spotify library in a synchronous way that hides pagination details
 * from the user.
 */
module.exports = {
    /**
     * This returns a set of parsed json response items from a spotify API such as:
     * https://developer.spotify.com/documentation/web-api/reference/library/get-users-saved-tracks/
     *
     * Note that we discard the page-level reponse metadata and just accumulate the `items` from each page.
     */
    getAllResults: async function (endpoint, accessToken) {
        const pages = await getAllPages(endpoint, accessToken)
        return pages.map(x => x.items).flat();
    },

    /**
     * This returns the current user's internal spotify user ID as described at:
     * https://developer.spotify.com/documentation/web-api/reference/users-profile/get-current-users-profile/
     */
    getUserId: async function (accessToken) {
        const result = await makeGetRequest("/v1/me", accessToken)
        return result.id
    }
};

/**
 * This fetches all responses from a GET endpoint in the spotify api as a list of response objects.
 */
const getAllPages = async function(endpoint, accessToken) {
    let response = await getSinglePageOfResults(endpoint, accessToken, 50, 0)
    let accumulatedResponses = [response]
    while (response.next) {
        console.log(`fetching next page from ${response.next}`)
        const nextArgs = querystring.parse(response.next.split('?')[1])

        // if we have a limit set, break here if we exceed it
        if (constants.PAGED_ITEM_FETCH_LIMIT && nextArgs.offset > constants.PAGED_ITEM_FETCH_LIMIT) break;

        response = await getSinglePageOfResults(endpoint, accessToken, nextArgs.limit, nextArgs.offset)
        accumulatedResponses.push(response)
    }
    return accumulatedResponses
}

const getSinglePageOfResults = function(endpoint, accessToken, limit, offset) {
    const params = querystring.stringify({
        limit: limit,
        offset: offset
    })
    return makeGetRequest(`${endpoint}?${params}`, accessToken)
}

/**
 * Make a get request to the specified endpoint, returning a promise for the results.
 * @param path the API endpoint path, including querystring params
 * @param accessToken the user access token
 */
const makeGetRequest = function(path, accessToken) {
    const options = {
        hostname: constants.SPOTIFY_API_HOSTNAME,
        path: path,
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
                // console.log(`${options.path} data section: ${data}`);
            })

            res.on('end', () => {
                // console.log(`${options.path} finished`);
                const payload = JSON.parse(response);
                resolve(payload);
            })
        })
        req.on('error', error => {
            reject(error)
        })
        req.end()
    });
}