const http = require('http');

const hostname = '127.0.0.1';
const port = 3000;

// TODO: pull client id and secret from ENV vars https://nodejs.dev/learn/how-to-read-environment-variables-from-nodejs
// TODO: learn about async https://nodejs.dev/learn/modern-asynchronous-javascript-with-async-and-await
// TODO: http server setup https://nodejs.dev/learn/build-an-http-server
// TODO: making http request https://nodejs.dev/learn/making-http-requests-with-nodejs

const server = http.createServer((req, res) => {
    res.statusCode = 200;
    res.setHeader('Content-Type', 'text/plain');
    res.end('Hello World');
});

server.listen(port, hostname, () => {
    console.log(`Server running at http://${hostname}:${port}/`);
});
