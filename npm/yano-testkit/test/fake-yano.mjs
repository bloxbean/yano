#!/usr/bin/env node

import { createServer } from "node:http";
import { writeFile } from "node:fs/promises";

const args = process.argv.slice(2);
const portArg = args.find((arg) => arg.startsWith("-Dquarkus.http.port="));
const port = Number.parseInt(portArg?.split("=")[1] ?? "0", 10);

if (process.env.FAKE_YANO_ARGS_FILE) {
  await writeFile(process.env.FAKE_YANO_ARGS_FILE, JSON.stringify(args, null, 2));
}

if (process.env.FAKE_YANO_EXIT_BEFORE_READY === "true") {
  console.error("fake yano startup failed");
  process.exit(7);
}

const server = createServer((request, response) => {
  if (request.url === "/q/health/ready") {
    response.writeHead(200, { "content-type": "application/json" });
    response.end(JSON.stringify({ status: "UP" }));
    return;
  }

  if (request.url === "/api/v1/node/tip") {
    response.writeHead(200, { "content-type": "application/json" });
    response.end(JSON.stringify({ slot: 1, blockNumber: 1 }));
    return;
  }

  response.writeHead(404, { "content-type": "application/json" });
  response.end(JSON.stringify({ error: "not found" }));
});

server.listen(port, "127.0.0.1", () => {
  console.log(`fake yano ready on ${port}`);
});

const stop = () => {
  server.close(() => process.exit(0));
};

process.on("SIGTERM", stop);
process.on("SIGINT", stop);
