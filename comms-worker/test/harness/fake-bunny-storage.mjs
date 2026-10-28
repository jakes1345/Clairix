// A Bunny Storage zone on loopback, over the same HTTP API (PUT, GET,
// DELETE, the AccessKey and Checksum headers), for testing the container
// relay's backups and lease (container/backup.mjs) without a Bunny account.
//
// startFakeBunnyStorage({ zone, accessKey }) → { endpoint, files, close() }.
// With { tls: { key, cert }, host, name }, it serves HTTPS on `host` and its
// endpoint names `name`, as a container reaching it by name would.
import { createHash } from "node:crypto";
import { createServer } from "node:http";
import { createServer as createHttpsServer } from "node:https";

export function startFakeBunnyStorage({ zone, accessKey, port = 0, tls = null, host = "127.0.0.1", name = "127.0.0.1" }) {
  const files = new Map();
  const handler = (req, res) => {
    const chunks = [];
    req.on("data", (c) => chunks.push(c));
    req.on("end", () => {
      const path = decodeURIComponent(new URL(req.url, "http://x").pathname);
      if (req.headers.accesskey !== accessKey) return reply(res, 401, "Unauthorized");
      if (!path.startsWith(`/${zone}/`)) return reply(res, 404, "Not Found");
      const name = path.slice(zone.length + 2);
      if (req.method === "PUT") {
        const body = Buffer.concat(chunks);
        const sum = req.headers.checksum;
        if (sum && sum !== createHash("sha256").update(body).digest("hex").toUpperCase()) return reply(res, 400, "Checksum mismatch");
        files.set(name, body);
        return reply(res, 201, "Created");
      }
      if (req.method === "GET") {
        const body = files.get(name);
        if (!body) return reply(res, 404, "Not Found");
        res.writeHead(200, { "content-type": "application/octet-stream", "content-length": body.length });
        return res.end(body);
      }
      if (req.method === "DELETE") {
        if (!files.delete(name)) return reply(res, 404, "Not Found");
        return reply(res, 200, "OK");
      }
      reply(res, 405, "Method Not Allowed");
    });
  };
  const server = tls ? createHttpsServer(tls, handler) : createServer(handler);
  return new Promise((resolve) => {
    server.listen(port, host, () => {
      resolve({
        endpoint: `${tls ? "https" : "http"}://${name}:${server.address().port}`,
        files,
        close: () => new Promise((r) => server.close(r)),
      });
    });
  });
}

function reply(res, status, text) {
  res.writeHead(status, { "content-type": "text/plain" }).end(`${text}\n`);
}

