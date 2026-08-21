# Future Evolution

The notes here should be IGNORED for any current work, and only represent ideas for future directions to explore.

## Replace Docker Workflow

Remove the secrets file / mounted file fallback stuff in favor of env vars that we added? though how to use that for local mode.

## Update Readme and add Deploy instructions to Portainer with NPM

Self-descriptive

## Self-hosted code hosting + CI

Make it update automatically and deploy on push.

## Fake backend and UI regression tests

This would help dev speed of new frontend stuff

## LastFM Integration, Metadata

Pull personal stats and richer genre information

Make a right-click interface for tagging tracks more dynamically with custom metadata that's available to future recipes.

Figure out a durable store for this.

## Admin Panel
Show who's used it! And possibly expand the "known users only restriction" after all if i ever deploy publicly.

## Linting

Let's add frontend code and CSS linting.

## Styling

Revisit all UX elements. Fix headers, remove un-needed stuff, etc.
 
Figure out some ways to incorporate album art and dominant colors from it in a more dynamic way. Like "key color" slow "chill" "lofi" row background
animations.

Let's add track length column and maybe other stuff to the track sequence preview. Listen count? Personal usage info?


## Perf - Full Library Sync

Parallelize this. Also provide ways to only fetch liked songs, not update remote playlists. But also add "update just
this playlist" button for each playlist + source.

## Feedback

When we know how many paginated POSTs to make to sync playlists, expose this back to the frontend as progress! (websockets?)

## Recipe Builder UI

Let's figure out a slick way to do this

## AI

Recipes from human language and button-guided stuff, like "start with four artists or songs, find others in my library
that are closest." At the song-level that would be per-song not per-artist, even.

## Enhanced TLS Termination Security

The Kotlin service receives the Nginx Proxy Manager request over its HTTP container hop, so it cannot infer that the
original public request used HTTPS. Forwarded headers are therefore trusted only when the request source address is a
configured proxy address.

The default trusted proxy address is `172.17.0.1`, which is the Docker bridge gateway used by this deployment. Override
it with the comma-separated `SPOTIFY_BUTLER_TRUSTED_PROXIES` environment variable or `spotify.trustedProxies` property.
The optional pre-shared token can additionally authorize a proxy request from another source address:

```yaml
environment:
  SPOTIFY_BUTLER_TRUSTED_PROXIES: "172.17.0.1"
  SPOTIFY_BUTLER_TRUSTED_PROXY_TOKEN: "GENERATED_RANDOM_VALUE"
```

Keep public hostnames such as `butler.peaswhome.com` in `SPOTIFY_BUTLER_TRUSTED_HOSTS`; proxy source addresses belong
in `SPOTIFY_BUTLER_TRUSTED_PROXIES`. NPM must overwrite `X-Butler-Proxy-Token` rather than pass through a
client-supplied value. Requests from other source addresses without the matching token ignore forwarded host and scheme
headers, so direct callers cannot spoof HTTPS or the public host.
