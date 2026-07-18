# Kotlin Spotify Butler

This service performs Spotify Authorization Code login and caches Spotify library data in a local SQLite database.

1. Copy `secrets.properties.example` to the ignored `secrets.properties` and fill in the Spotify client ID and secret.
2. Configure the Spotify application redirect URI exactly as `http://127.0.0.1:8888/callback`.
3. From the repository root, run `./kt/gradlew -p kt run`.
4. Visit `http://127.0.0.1:8888/start`. After approval, the callback fetches the Spotify collections into SQLite and
   redirects to `/hello`, which returns `hello, <display name>`.

The default database is `kt/spotify.db` when launched from the repository root (or `spotify.db` when launched from
inside `kt/`). The database is loaded after OAuth only when it has no completed sync. Use
`http://127.0.0.1:8888/start?refresh=true` to replace the cached data deliberately.

The loader reads saved tracks, top tracks, top artists, playlists, and the tracks in each playlist. It does not yet run
playlist queries or make any Spotify playlist/library modifications. SQLDelight owns the SQLite schema and its named
cache-load statements.

## Response capture for future tests

Every successful Spotify API response is logged in full at INFO level with the `Spotify API scraped response` marker.
These responses contain personal listening data, so capture and sanitize representative pages before committing them as
fake test fixtures. The disabled cache-load contracts under `src/test/kotlin` name the response families and SQLite
assertions needed once those fixtures exist.

## Linting

The Kotlin service uses ktlint and detekt. Enable the repository pre-commit hook once after cloning:

```sh
./scripts/setup-git-hooks.sh
```

The hook runs `ktlintFormat` first. If formatting changes Kotlin source files, the commit stops so you can review and
stage them. It then runs detekt. The shared 120-character line limit is configured in `.editorconfig` and
`config/detekt/detekt.yml`.

Detekt uses its 2.0 alpha release so linting can run with the project’s JDK/JVM 25 target. Revisit this dependency when
the stable 2.x release becomes available.

Set `SPOTIFY_BUTLER_SECRETS_FILE` to use a properties file at another path.
