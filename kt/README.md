# Kotlin Spotify Butler

This first slice provides Spotify Authorization Code login and an account greeting.

1. Copy `secrets.properties.example` to the ignored `secrets.properties` and fill in the Spotify client ID and secret.
2. Configure the Spotify application redirect URI exactly as `http://127.0.0.1:8888/callback`.
3. From the repository root, run `./kt/gradlew -p kt run`.
4. Visit `http://127.0.0.1:8888/start`. After approval Spotify redirects to `/hello`, which returns `hello, <display name>`.

## Linting

The Kotlin service uses ktlint and detekt. Enable the repository pre-commit hook once
after cloning:

```sh
./scripts/setup-git-hooks.sh
```

The hook runs `ktlintFormat` first. If formatting changes Kotlin source files, the commit
stops so you can review and stage them. It then runs detekt. The shared 120-character line
limit is configured in `.editorconfig` and `config/detekt/detekt.yml`.

Detekt uses its 2.0 alpha release so linting can run with the project’s JDK/JVM 25 target.
Revisit this dependency when the stable 2.x release becomes available.

Set `SPOTIFY_BUTLER_SECRETS_FILE` to use a properties file at another path.
