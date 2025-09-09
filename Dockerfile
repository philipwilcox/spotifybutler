ARG NODE_VERSION=16.14.0

FROM node:${NODE_VERSION}-bullseye

RUN apt-get install libsqlite3-dev

WORKDIR /usr/spotify-butler
COPY package*.json ./
RUN npm install

COPY . .
RUN mv deploy/secrets.docker.ts src/secrets.ts
RUN npx tsc --project .  --removeComments true

EXPOSE 8888

CMD [ "node", "src/server.js" ]
