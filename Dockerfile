FROM node:24.12.0-bookworm-slim AS frontend-build

WORKDIR /workspace
COPY vue/package.json vue/package-lock.json ./vue/
WORKDIR /workspace/vue
RUN npm ci

WORKDIR /workspace
COPY vue ./vue
ARG BUTLER_BUILD_TIMESTAMP
RUN BUTLER_BUILD_TIMESTAMP="$BUTLER_BUILD_TIMESTAMP" npm run build --prefix vue

FROM eclipse-temurin:25-jdk AS kotlin-build

WORKDIR /workspace
COPY kt ./kt
WORKDIR /workspace/kt
RUN ./gradlew installDist --no-daemon

FROM eclipse-temurin:25-jre AS runtime

ARG BUTLER_PUBLIC_HOST=butler.example.invalid

RUN groupadd --system --gid 10001 spotifybutler \
    && useradd --system --uid 10001 --gid 10001 --no-create-home spotifybutler \
    && mkdir --parents /data \
    && chown spotifybutler:spotifybutler /data

COPY --from=kotlin-build /workspace/kt/build/install/kt /app/kt
COPY --from=frontend-build /workspace/vue/dist /app/vue-dist
RUN chmod -R a+rX /app/kt /app/vue-dist

ENV SPOTIFY_BUTLER_SECRETS_FILE=/run/secrets/spotify-butler.properties \
    SPOTIFY_BUTLER_FRONTEND_DIRECTORY=/app/vue-dist \
    SPOTIFY_BUTLER_DATABASE_PATH=/data/spotify.db \
    SPOTIFY_BUTLER_TRUSTED_ORIGINS=https://${BUTLER_PUBLIC_HOST} \
    SPOTIFY_BUTLER_TRUSTED_HOSTS=${BUTLER_PUBLIC_HOST} \
    SPOTIFY_BUTLER_SECURE_COOKIES=true \
    SPOTIFY_BUTLER_REQUIRE_HTTPS_CALLBACK=true

WORKDIR /app
USER spotifybutler
EXPOSE 8888
ENTRYPOINT ["/app/kt/bin/kt"]
