import fetch from "node-fetch";
import https from "https";
import {Deserialize} from "cerialize";
import {LibraryTrack} from "../models/spotify/library-track.js";
import {Track} from "../models/spotify/track";
import {Artist} from "../models/spotify/artist";

export default class Library {
    private requestBackend: RequestBackend

    constructor(spotify_api_host: string, paged_item_fetch_limit: null | number) {
        this.requestBackend = new RequestBackend(spotify_api_host, paged_item_fetch_limit)
    }

    async getMySavedTracks(accessToken) {
        // TODO: move the type awareness into a templated generic requestBackend method?
        return this.requestBackend.getAllResults('/v1/me/tracks', accessToken).then(
            items => Deserialize(items, LibraryTrack)
        )
    }

    async getMyTopTracks(accessToken) {
        return this.requestBackend.getAllResults('/v1/me/top/tracks', accessToken).then(
            items => Deserialize(items, Track)
        )
    }

    async getMyTopArtists(accessToken) {
        return this.requestBackend.getAllResults('/v1/me/top/artists', accessToken).then(
            items => Deserialize(items, Artist)
        )
    }
}


/**
 * TODO: is the stuff below this effectively private?
 */


/**
 * This is a library for interacting with the often-paginated spotify library in a synchronous way that hides pagination details
 * from the user.
 */
class RequestBackend {
    private spotify_api_host: string
    private paged_item_fetch_limit: null | number

    constructor(spotify_api_host: string, paged_item_fetch_limit: null | number) {
        this.spotify_api_host = spotify_api_host
        this.paged_item_fetch_limit = paged_item_fetch_limit
    }

    // TODO: make this generic on type of return value, for e.g. tracks vs playlists
    /**
     * This returns a set of parsed json response items from a spotify API such as:
     * https://developer.spotify.com/documentation/web-api/reference/library/get-users-saved-tracks/
     *
     * Note that we discard the page-level reponse metadata and just accumulate the `items` from each page.
     *
     * Note that this assumes host is always this.spotify_api_host
     */
    async getAllResults(path, accessToken) {
        const pages = await this.getAllPages(this.spotify_api_host, path, accessToken)
        // @ts-ignore
        return pages.map(x => x.items).flat();
    }

    /**
     * This returns the current user's internal spotify user ID as described at:
     * https://developer.spotify.com/documentation/web-api/reference/users-profile/get-current-users-profile/
     */
    async getUserId(accessToken) {
        const result = await makeGetRequest(this.spotify_api_host, "/v1/me", accessToken)
        // @ts-ignore
        return result.id
    }

    /**
     * POSTs a JSON payload to a Spotify API endpoint.
     */
    async postData(endpoint, data, accessToken) {
        return makeRequestWithJsonBody('POST', endpoint, data, accessToken)
    }

    /**
     * DELETEs a JSON payload to a Spotify API endpoint.
     */
    async deleteData(endpoint, data, accessToken) {
        // NOTE: I was trying to avoid external dependencies, but `fetch` is needed here since the built in
        // https request stuff in Node wasn't letting me send a postbody in a DELETE, which is nonstandard but
        // required by the Spotify API...
        const response = fetch(
            'https://' + this.spotify_api_host + endpoint,
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

    /**
     * This fetches all responses from a GET endpoint in the spotify api as a list of response objects.
     * */
    async getAllPages(host, path, accessToken) {
        let response = await getSinglePageOfResults(host, path, accessToken, 50, 0)
        let accumulatedResponses = [response]
        // @ts-ignore
        while (response.next) {
            // console.log(`fetching next page from ${response.next}`)
            // @ts-ignore
            const nextArgs = new URLSearchParams(response.next.split('?')[1])

            // if we have a limit set, break here if we exceed it
            if (this.paged_item_fetch_limit && parseInt(nextArgs.get("offset")) > this.paged_item_fetch_limit) break;

            response = await getSinglePageOfResults(host, path, accessToken, nextArgs.get("limit"), nextArgs.get("offset"))
            accumulatedResponses.push(response)
        }
        return accumulatedResponses
    }
}


const getSinglePageOfResults = function (host, path, accessToken, limit, offset) {
    const params = new URLSearchParams({
        limit: limit,
        offset: offset
    }).toString()
    return makeGetRequest(host, `${path}?${params}`, accessToken)
}

/**
 * Make a get request to the specified endpoint, returning a promise for the results that will resolve to a parsed
 * JSON object.
 */
const makeGetRequest = function (host: string, path: string, accessToken: string) {
    // TODO: make this take a destination type and return it
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
const makeRequestWithJsonBody = function (method, path, data, accessToken) {
    // TODO: note that this currently returns unschema'd json objects
    const options = {
        hostname: this.spotify_api_host,
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