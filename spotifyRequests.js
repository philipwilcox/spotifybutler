import querystring from 'querystring'
import https from 'https';
import constants from './constants.js'
import fetch from 'node-fetch';

/**
 * This is a library for interacting with the often-paginated spotify library in a synchronous way that hides pagination details
 * from the user.
 */
export default {
    /**
     * This returns a set of parsed json response items from a spotify API such as:
     * https://developer.spotify.com/documentation/web-api/reference/library/get-users-saved-tracks/
     *
     * Note that we discard the page-level reponse metadata and just accumulate the `items` from each page.
     *
     * Note that this assumes host is always constants.SPOTIFY_API_HOSTNAME
     */
    getAllResults: async function (path, accessToken) {
        const pages = await getAllPages(constants.SPOTIFY_API_HOSTNAME, path, accessToken)
        return pages.map(x => x.items).flat();
    },

    /**
     * This returns the current user's internal spotify user ID as described at:
     * https://developer.spotify.com/documentation/web-api/reference/users-profile/get-current-users-profile/
     */
    getUserId: async function (accessToken) {
        const result = await makeGetRequest(constants.SPOTIFY_API_HOSTNAME, "/v1/me", accessToken)
        return result.id
    },

    /**
     * POSTs a JSON payload to a Spotify API endpoint.
     */
    postData: async function (endpoint, data, accessToken) {
        return makeRequestWithJsonBody('POST', endpoint, data, accessToken)
    },

    /**
     * DELETEs a JSON payload to a Spotify API endpoint.
     */
    deleteData: async function (endpoint, data, accessToken) {
        // NOTE: I was trying to avoid external dependencies, but `fetch` is needed here since the built in
        // https request stuff in Node wasn't letting me send a postbody in a DELETE, which is nonstandard but
        // required by the Spotify API...
        const response = fetch(
            'https://' + constants.SPOTIFY_API_HOSTNAME + endpoint,
            {
                method: 'DELETE',
                headers: {
                    'Content-Type': 'application/json',
                    'Authorization': `Bearer ${accessToken}`
                },
                body: JSON.stringify(data)
            }
        )
        return (await response).json()
    }
};

/**
 * This fetches all responses from a GET endpoint in the spotify api as a list of response objects.
 * */
const getAllPages = async function(host, path, accessToken) {
    let response = await getSinglePageOfResults(host, path, accessToken, 50, 0)
    let accumulatedResponses = [response]
    while (response.next) {
        // console.log(`fetching next page from ${response.next}`)
        const nextArgs = querystring.parse(response.next.split('?')[1])

        // if we have a limit set, break here if we exceed it
        if (constants.PAGED_ITEM_FETCH_LIMIT && nextArgs.offset > constants.PAGED_ITEM_FETCH_LIMIT) break;

        response = await getSinglePageOfResults(host, path, accessToken, nextArgs.limit, nextArgs.offset)
        accumulatedResponses.push(response)
    }
    return accumulatedResponses
}

const getSinglePageOfResults = function(host, path, accessToken, limit, offset) {
    const params = querystring.stringify({
        limit: limit,
        offset: offset
    })
    return makeGetRequest(host, `${path}?${params}`, accessToken)
}

/**
 * Make a get request to the specified endpoint, returning a promise for the results.
 * @param path the API endpoint path, including querystring params
 * @param accessToken the user access token
 */
const makeGetRequest = function (host, path, accessToken) {
    const options = {
        hostname: host,
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

/**
 * Make a request to the specified endpoint with a given method and a JSON body, returning a promise for the results.
 * @param method the HTTP method, like `POST`
 * @param path the API endpoint path
 * @param data the JSON object to POST
 * @param accessToken the user access token
 */
const makeRequestWithJsonBody = function(method, path, data, accessToken) {
    const options = {
        hostname: constants.SPOTIFY_API_HOSTNAME,
        path: path,
        method: method.toUpperCase(),
        headers: {
            'Authorization': `Bearer ${accessToken}`,
            'Content-Type': 'application/json'
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
        req.write(JSON.stringify(data))
        req.end()
    });
}