# Spotify Butler

Spotify Butler is a Kotlin service with a Vue frontend. The container image builds both applications and serves the
frontend and API from port `8888`.

## Run the current version in Docker

Register the callback URL used by your deployment in the Spotify Developer Dashboard. For an HTTPS deployment it is
typically something like `https://butler.example.com/callback`. This must be an exact match to what's registered.

To run, you can point the backend to a secrets file or pass them in env vars.

## Secrets

### File-Based Secrets

```sh
cp kt/secrets.properties.example /path/to/spotify-butler.properties
chmod 600 /path/to/spotify-butler.properties
```

At minimum, set these properties:

```properties
spotify.clientId=your-spotify-client-id
spotify.clientSecret=your-spotify-client-secret
spotify.redirectUri=https://butler.example.com/callback
spotify.allowedUserId=your-spotify-user-id
```

### Env-Var Based Secrets

For deployments that use a container secret manager, `SPOTIFY_BUTLER_CLIENT_ID`, `SPOTIFY_BUTLER_CLIENT_SECRET`, and
`SPOTIFY_BUTLER_REDIRECT_URI` are required. They take precedence over the matching properties in a secrets file, so
they can also be mixed with file-based configuration. `spotify.allowedUserId` is optional and has no
environment-variable equivalent; set it in the secrets properties file when a single-user allowlist is needed.

## Build and Deploy

Build the image with the public hostname used by the browser. This configures the default trusted origin and trusted
host in the image:
a
```sh
docker build \
  --build-arg BUTLER_PUBLIC_HOST=butler.example.com \
  --build-arg BUTLER_BUILD_TIMESTAMP="$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  --tag spotify-butler:latest \
  .
```

`BUTLER_BUILD_TIMESTAMP` is passed into the frontend build so Docker cache reuse cannot leave an old build stamp in a
new image. The value is stored as UTC and displayed in each browser's local timezone; hover over the stamp to see the
original UTC value.

Create a persistent data volume and start the container. The secrets file is mounted read-only and the SQLite database
is stored in the volume:

```sh
docker volume create spotify-butler-data
docker run --detach \
  --name spotify-butler \
  --restart unless-stopped \
  --publish 8888:8888 \
  --mount type=volume,src=spotify-butler-data,dst=/data \
  --mount type=bind,src=/path/to/spotify-butler.properties,dst=/run/secrets/spotify-butler.properties,readonly \
  spotify-butler:latest
```

The Spotify API requires HTTPS for non-127.0.0.1 use. So performing (or letting a proxy perform) TLS termination
is necessary to host somewhere, even if on just a local network.

### Environment variables

Environment variables override matching properties-file values. The container already sets the paths and secure-cookie
settings shown below; override them only when the deployment needs different locations or trust boundaries.

| Environment variable | Property fallback | Default or purpose |
| --- | --- | --- |
| `SPOTIFY_BUTLER_SECRETS_FILE` | — | `/run/secrets/spotify-butler.properties` in Docker |
| `SPOTIFY_BUTLER_CLIENT_ID` | `spotify.clientId` | Spotify application client ID |
| `SPOTIFY_BUTLER_CLIENT_SECRET` | `spotify.clientSecret` | Spotify application client secret |
| `SPOTIFY_BUTLER_REDIRECT_URI` | `spotify.redirectUri` | Exact Spotify OAuth callback URI |
| `SPOTIFY_BUTLER_CONFIG_FILE` | — | Optional application properties file |
| `SPOTIFY_BUTLER_DATABASE_PATH` | `spotify.databasePath` | `kt/spotify.db` locally; `/data/spotify.db` in Docker |
| `SPOTIFY_BUTLER_FRONTEND_DIRECTORY` | `spotify.frontendDirectory` | Built Vue directory; `/app/vue-dist` in Docker |
| `SPOTIFY_BUTLER_HOST` | `spotify.host` | `0.0.0.0` |
| `SPOTIFY_BUTLER_TRUSTED_ORIGINS` | `spotify.trustedOrigins` | Localhost origins; Docker derives the public HTTPS origin |
| `SPOTIFY_BUTLER_TRUSTED_HOSTS` | `spotify.trustedHosts` | Localhost hosts; Docker derives the public host |
| `SPOTIFY_BUTLER_TRUSTED_PROXIES` | `spotify.trustedProxies` | Optional trusted proxy addresses |
| `SPOTIFY_BUTLER_TRUSTED_PROXY_TOKEN` | `spotify.trustedProxyToken` | Optional proxy authentication token |
| `SPOTIFY_BUTLER_SECURE_COOKIES` | `spotify.secureCookies` | `false` locally; `true` in Docker |
| `SPOTIFY_BUTLER_REQUIRE_HTTPS_CALLBACK` | `spotify.requireHttpsCallback` | `false` locally; `true` in Docker |
| `SPOTIFY_BUTLER_SPOTIFY_RETRY_MAX_RETRIES` | `spotify.spotifyRetryMaxRetries` | `4`; integer `0..10` |
| `SPOTIFY_BUTLER_SPOTIFY_RETRY_INITIAL_DELAY_SECONDS` | `spotify.spotifyRetryInitialDelaySeconds` | `1`; positive integer |
| `SPOTIFY_BUTLER_SPOTIFY_RETRY_BACKOFF_MULTIPLIER` | `spotify.spotifyRetryBackoffMultiplier` | `2`; number at least `1.0` |

The retry settings apply to idempotent Spotify GET requests returning HTTP 429. Spotify's `Retry-After` response is
honored, and quota-exceeded responses are not retried. Invalid configured values fail startup with an actionable error.

For local development, see [`kt/README.md`](kt/README.md). The documented entrypoint is:

```
./kt/gradlew -p kt run
```

## Appendix: previous prototype

The following is the original project description retained for historical context:

> This is something to create dynamic playlists to do things like "here are songs you like that
> aren't in your top songs" or "here are songs you liked that aren't in the last 50 you played."
>
> To set up you need to add a secrets.ts file in the `src` directory that exports your client ID, etc.
>
> I'd love to do fancier queries than those two, like iTunes smart playlists, but the Spotify API doesn't support getting
> much more than just that. I wish it would give me "last play time" and "total play count" for a user/track, but don't see
> that in their API, and the feature requests are old and stale.
>
> Currently developed against node v16.14.0 LTS.
