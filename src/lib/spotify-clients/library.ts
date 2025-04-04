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
            items => {
                return Deserialize(items, Playlist)
            }
        )
    }

    async getPlaylistInfo(playlistId: string): Promise<Playlist> {
        return this.requestBackend.getOneResult('/v1/playlists/' + playlistId).then(
            x => Deserialize(x, Playlist)
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

    async replaceTracksInPlaylist(playlistId: string, trackList: Track[]) {
        // NOTE: can't do more than 100 items at a time
        // But because PUT is an overwrite we can't use the PUT method every time...
        // Instead we can PUT the first and the POST the rest.
        const endpoint = `/v1/playlists/${playlistId}/tracks`
        const chunkedTrackList = utils.chunkedList(trackList, 100)
        let first = true
        for (const chunk of chunkedTrackList) {
            const data = {
                uris: chunk.map(x => x.uri)
            }
            if (first) {
                await this.requestBackend.putData(endpoint, data)
                first = false
            } else {
                await this.requestBackend.postData(endpoint, data)
            }
        }
    }

    async reorderTracksInPlaylist(playlistId: string, rangeStart: number, rangeLength: number, insertBefore: number, snapshotId: null | string = null) {
        const endpoint = `/v1/playlists/${playlistId}/tracks`
        const data = {
            range_start: rangeStart,
            range_length: rangeLength,
            insert_before: insertBefore
        }
        if (snapshotId != null) {
            data['snapshot_id'] = snapshotId
        }
        await this.requestBackend.putData(endpoint, data)
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

    async removeFromMySavedTracks(idList: string[]) {
        // NOTE: can't do more than 50 items at a time
        // https://developer.spotify.com/documentation/web-api/reference/#/operations/remove-tracks-user
        // This takes spotify ID's like "7ouMYWpwJ422jRcDASZB7P,4VqPOruhp5EdPBeR92t6lQ,2takcwOaAZWiXQijPHIx7B"
        const endpoint = `/v1/me/tracks`
        const chunkedIdList = utils.chunkedList(idList, 50)
        for (const chunk of chunkedIdList) {
            let response = await this.requestBackend.deleteData(endpoint, chunk)
            console.log(`deleting ${chunk} for ${endpoint} produced response ${response}`)
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
    async getOneResult(path) {
        return await getSingleItemOfResults(this.apiHost, path, this.accessToken)
    }

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
     * PUTs a JSON payload to a Spotify API endpoint.
     */
    async putData<Type>(endpoint, data): Promise<Type> {
        return makeRequestWithJsonBody('PUT', this.apiHost, endpoint, data, this.accessToken)
    }

    /**
     * DELETEs a JSON payload to a Spotify API endpoint.
     */
    async deleteData(endpoint, data) {
        console.log(`will make delete request ${endpoint} - data ${data}`)
        // NOTE: I was trying to avoid external dependencies, but `fetch` is needed here since the built in
        // https request stuff in Node wasn't letting me send a postbody in a DELETE, which is nonstandard but
        // required by the Spotify API...
        // TODO pmw: looks like this no longer needs a POSTBODY vs url params? https://developer.spotify.com/documentation/web-api/reference/remove-tracks-user
        const headers = {
            'Content-Type': 'application/json',
            'Authorization': `Bearer ${this.accessToken}`
        }
        const response = await fetch(
            'https://' + this.apiHost + endpoint + `?ids=${data}`,
            {
                method: 'DELETE',
                headers: headers,
                body: JSON.stringify(data)
            }
        )
        if (response.status != 200) {
            const errorString = `Saw a non-200 status code ${response.status} - ${await response.text()} for response from DELETE ${this.apiHost} ${endpoint} ${data}`
            throw new Error(errorString)
        }
        return response.json()
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

const getSingleItemOfResults = function (host, path, accessToken) {
    return makeGetRequest(host, `${path}`, accessToken)
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