import fetch from "node-fetch";
import https from "https";
import {Deserialize} from "cerialize";
import {LibraryTrack} from "../models/spotify/library-track.js";
import {Track} from "../models/spotify/track.js";
import {Artist} from "../models/spotify/artist.js";
import {Playlist} from "../models/spotify/playlist.js";
import {PlaylistTrack} from "../models/spotify/playlist-track.js";
import utils from "../../utils.js";

export default class Library {
    private requestBackend: RequestBackend

    constructor(spotify_api_host: string, paged_item_fetch_limit: null | number, accessToken: string) {
        this.requestBackend = new RequestBackend(spotify_api_host, paged_item_fetch_limit, accessToken)
    }

    async getMySavedTracks(): Promise<LibraryTrack[]> {
        // TODO: move the type awareness into a templated generic requestBackend method?
        return this.requestBackend.getAllResults('/v1/me/tracks').then(
            items => Deserialize(items, LibraryTrack)
        )
    }

    async getMyTopTracks(): Promise<Track[]> {
        return this.requestBackend.getAllResults('/v1/me/top/tracks').then(
            items => Deserialize(items, Track)
        )
    }

    async getMyTopArtists(): Promise<Artist[]> {
        return this.requestBackend.getAllResults('/v1/me/top/artists').then(
            items => Deserialize(items, Artist)
        )
    }

    async getMyPlaylists(): Promise<Playlist[]> {
        return this.requestBackend.getAllResults('/v1/me/playlists').then(
            items => Deserialize(items, Playlist)
        )
    }

    async getTracksForPlaylist(tracks_href: string): Promise<PlaylistTrack[]> {
        // hydrate tracks data - only needed if existing playlist; new playlist we can assume is empty
        const tracksUrl = new URL(tracks_href)
        return this.requestBackend.getAllResults(tracksUrl.pathname).then(
            items => Deserialize(items, PlaylistTrack)
        )
    }

    async createPlaylistWithName(playlistName): Promise<Playlist> {
        const userId = await this.requestBackend.getUserId()
        const endpoint = `/v1/users/${userId}/playlists`
        const data = {
            name: playlistName,
            public: false,
            collaborative: false,
            description: "Automatically generated playlist from Spotify Butler app"
        }
        return this.requestBackend.postData(endpoint, data)
    }

    async addTracksToPlaylist(playlistId: string, trackList: Track[]) {
        // NOTE: can't do more than 100 items at a time
        const endpoint = `/v1/playlists/${playlistId}/tracks`
        const chunkedTrackList = utils.chunkedList(trackList, 100)
        for (const chunk of chunkedTrackList) {
            const data = {
                uris: chunk.map(x => x.uri)
            }
            await this.requestBackend.postData(endpoint, data)
        }
    }

    // TODO: could I go further and type playlist IDs to be "special" strings?
    async removeTracksFromPlaylist(playlistId: string, trackList: Track[]) {
        // NOTE: can't do more than 100 items at a time
        const endpoint = `/v1/playlists/${playlistId}/tracks`
        const chunkedTrackList = utils.chunkedList(trackList, 100)
        for (const chunk of chunkedTrackList) {
            const data = {
                tracks: chunk.map(x => ({
                    uri: x.uri
                }))
            }
            await this.requestBackend.deleteData(endpoint, data)
        }
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
    private apiHost: string
    private pagedItemFetchLimit: null | number
    private accessToken: string

    constructor(spotifyApiHost: string, pagedItemFetchLimit: null | number, accessToken: string) {
        this.apiHost = spotifyApiHost
        this.pagedItemFetchLimit = pagedItemFetchLimit
        this.accessToken = accessToken
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
    async getAllResults(path) {
        const pages = await this.getAllPages(this.apiHost, path)
        // @ts-ignore
        return pages.map(x => x.items).flat();
    }

    /**
     * This returns the current user's internal spotify user ID as described at:
     * https://developer.spotify.com/documentation/web-api/reference/users-profile/get-current-users-profile/
     */
    // TODO: do I need?
    async getUserId() {
        const result = await makeGetRequest(this.apiHost, "/v1/me", this.accessToken)
        // @ts-ignore
        return result.id
    }

    /**
     * POSTs a JSON payload to a Spotify API endpoint.
     */
    async postData<Type>(endpoint, data): Promise<Type> {
        return makeRequestWithJsonBody('POST', this.apiHost, endpoint, data, this.accessToken)
    }

    /**
     * DELETEs a JSON payload to a Spotify API endpoint.
     */
    async deleteData(endpoint, data) {
        // NOTE: I was trying to avoid external dependencies, but `fetch` is needed here since the built in
        // https request stuff in Node wasn't letting me send a postbody in a DELETE, which is nonstandard but
        // required by the Spotify API...
        const response = fetch(
            'https://' + this.apiHost + endpoint,
            {
                method: 'DELETE',
                headers: {
                    'Content-Type': 'application/json',
                    'Authorization': `Bearer ${this.accessToken}`
                },
                body: JSON.stringify(data)
            }
        )
        return (await response).json()
    }

    /**
     * This fetches all responses from a GET endpoint in the spotify api as a list of response objects.
     * */
    async getAllPages(host, path) {
        // TODO: do I need to build limit/offset myself each time?
        // TODO: configure limit somewhere? especially for top artists/track stuff if they ever support more?
        let response = await getSinglePageOfResults(host, path, this.accessToken, 50, 0)
        let accumulatedResponses = [response]
        // @ts-ignore
        while (response.next) {
            // console.log(`fetching next page from ${response.next}`)
            // @ts-ignore
            const nextArgs = new URLSearchParams(response.next.split('?')[1])

            // if we have a limit set, break here if we exceed it
            if (this.pagedItemFetchLimit && parseInt(nextArgs.get("offset")) > this.pagedItemFetchLimit) break;

            response = await getSinglePageOfResults(host, path, this.accessToken, nextArgs.get("limit"), nextArgs.get("offset"))

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
            if (res.statusCode !== 200) {
                const errorString = `Saw a non-200 status code ${res.statusCode} - ${res.statusMessage} for response that so far is ${response} from ${host} ${path}`
                throw new Error(errorString)
            }
            res.on('data', data => {
                response += data
            })

            res.on('end', () => {
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
const makeRequestWithJsonBody = function <Type>(method, hostname, path, data, accessToken): Promise<Type> {
    // TODO: note that this currently returns unschema'd json objects
    const options = {
        hostname: hostname,
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