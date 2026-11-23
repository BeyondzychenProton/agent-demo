import { createReadStream } from "node:fs";
import { stat } from "node:fs/promises";
import { createServer } from "node:http";
import { extname } from "node:path";
import { createDemoMiddleware } from "./mock.mjs";

const serveDemo = createDemoMiddleware();
const types = {
  ".html": "text/html; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".woff": "font/woff",
  ".woff2": "font/woff2",
  ".ttf": "font/ttf",
  ".wasm": "application/wasm",
  ".svg": "image/svg+xml",
  ".png": "image/png",
};

const server = createServer((request, response) => {
  serveDemo(request, response, () => {
    const path = new URL(request.url ?? "/", "http://localhost").pathname;
    if (path === "/" && !request.url?.includes("?")) {
      response.writeHead(302, { Location: "/__h5-demo" }).end();
      return;
    }
    const asset = path === "/" ? "index.html" : /^\/assets\/[\w.-]+$/.test(path) ? path.slice(1) : null;
    if (!asset || !["GET", "HEAD"].includes(request.method)) {
      response.writeHead(404).end();
      return;
    }
    const file = new URL(asset, new URL("./public/", import.meta.url));
    void stat(file).then(info => {
      if (!info.isFile()) {
        response.writeHead(404).end();
        return;
      }
      response.writeHead(200, {
        "Content-Type": types[extname(asset)] ?? "application/octet-stream",
        "Content-Length": info.size,
        "Cache-Control": asset === "index.html" ? "no-cache" : "public, max-age=31536000, immutable",
      });
      if (request.method === "HEAD") response.end();
      else createReadStream(file).on("error", () => response.destroy()).pipe(response);
    }, () => response.writeHead(404).end());
  });
});

const host = process.env.HOST ?? "127.0.0.1";
const port = Number(process.env.PORT ?? 8080);
server.listen(port, host, () => console.log(`海卓大脑 H5 Mock: http://${host}:${port}/__h5-demo`));
