export default {
    SPOTIFY: {
        SPOTIFY_ACCOUNTS_HOSTNAME: 'accounts.spotify.com',
        SPOTIFY_API_HOSTNAME: 'api.spotify.com',
        TRACK_FETCH_SIZE: 50,
        // page limit is intended for dev only, to avoid slowdowns from waiting for all calls
        PAGED_ITEM_FETCH_LIMIT: null
    },
    APP: {
        MIN_YEAR_FOR_DISCOVER_WEEKLY: 2018
    },
    SERVER: {
        PORT: 8888,
        HOSTNAME: "127.0.0.1"
    },
    SQLITE: {
        DB_FILE: ":memory:"
    }
}