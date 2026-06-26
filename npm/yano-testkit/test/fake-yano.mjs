#!/usr/bin/env node

import { createServer } from "node:http";
import { writeFile } from "node:fs/promises";

const args = process.argv.slice(2);
const portArg = args.find((arg) => arg.startsWith("-Dquarkus.http.port="));
const port = Number.parseInt(portArg?.split("=")[1] ?? "0", 10);
const state = {
  slot: 1,
  blockNumber: 1,
  snapshots: new Map(),
  funded: new Map(),
  submittedTxs: new Set()
};

if (process.env.FAKE_YANO_ARGS_FILE) {
  await writeFile(process.env.FAKE_YANO_ARGS_FILE, JSON.stringify(args, null, 2));
}

if (process.env.FAKE_YANO_EXIT_BEFORE_READY === "true") {
  console.error("fake yano startup failed");
  process.exit(7);
}

const server = createServer((request, response) => {
  const url = new URL(request.url ?? "/", `http://${request.headers.host ?? "127.0.0.1"}`);
  const path = url.pathname;

  if (path === "/q/health/ready") {
    response.writeHead(200, { "content-type": "application/json" });
    response.end(JSON.stringify({ status: "UP" }));
    return;
  }

  if (path === "/api/v1/node/status") {
    json(response, {
      running: true,
      runtimeDegraded: false,
      localTipSlot: state.slot,
      localTipBlockNumber: state.blockNumber
    });
    return;
  }

  if (path === "/api/v1/node/tip") {
    json(response, tip());
    return;
  }

  if (path === "/api/v1/node/config") {
    json(response, {
      protocolMagic: 42,
      devMode: true,
      useRocksDB: true,
      epochLength: 10,
      byronSlotsPerEpoch: 5,
      firstNonByronSlot: 0
    });
    return;
  }

  if (path === "/api/v1/epochs/latest/parameters") {
    json(response, { epoch: 0, min_fee_a: 44, min_fee_b: 155381 });
    return;
  }

  if (path === "/api/v1/genesis") {
    json(response, { network_magic: 42, epoch_length: 10 });
    return;
  }

  if (path === "/api/v1/epochs/0/parameters") {
    json(response, { epoch: 0, min_fee_a: 44 });
    return;
  }

  if (path === "/api/v1/blocks/latest") {
    json(response, {
      number: state.blockNumber,
      slot: state.slot,
      hash: `fake-block-${state.blockNumber}`
    });
    return;
  }

  if (path.startsWith("/api/v1/blocks/")) {
    const hashOrNumber = path.split("/").pop();
    json(response, {
      number: Number.parseInt(hashOrNumber ?? "0", 10) || state.blockNumber,
      slot: state.slot,
      hash: hashOrNumber
    });
    return;
  }

  if (request.method === "POST" && path === "/api/v1/devnet/fund") {
    readBody(request).then((body) => {
      const requestBody = JSON.parse(body);
      const address = String(requestBody.address);
      const lovelace = adaToLovelace(requestBody.ada);
      const txHash = `fake-fund-tx-${state.funded.size + 1}`;
      const utxo = {
        tx_hash: txHash,
        output_index: 0,
        address,
        amount: [{ unit: "lovelace", quantity: String(lovelace) }],
        block: String(state.blockNumber)
      };
      state.funded.set(address, [...(state.funded.get(address) ?? []), utxo]);
      json(response, {
        tx_hash: txHash,
        index: 0,
        lovelace: Number(lovelace)
      });
    }).catch((error) => {
      json(response, { error: error.message }, 400);
    });
    return;
  }

  if (request.method === "POST" && path === "/api/v1/devnet/time/advance") {
    readBody(request).then((body) => {
      const requestBody = JSON.parse(body || "{}");
      const produced = Number(requestBody.slots ?? requestBody.seconds ?? ((requestBody.epochs ?? 0) * 10));
      state.slot += produced;
      state.blockNumber += produced;
      json(response, {
        message: `Advanced ${produced} blocks`,
        new_slot: state.slot,
        new_block_number: state.blockNumber,
        blocks_produced: produced
      });
    }).catch((error) => {
      json(response, { error: error.message }, 400);
    });
    return;
  }

  if (request.method === "POST" && path === "/api/v1/devnet/epochs/shift") {
    readBody(request).then((body) => {
      const requestBody = JSON.parse(body || "{}");
      json(response, {
        message: `Shifted genesis back by ${requestBody.epochs} epochs and started block producer`,
        shift_millis: Number(requestBody.epochs) * 10_000,
        new_system_start: "2026-01-01T00:00:00Z",
        genesis_slot: 0
      });
    });
    return;
  }

  if (request.method === "POST" && path === "/api/v1/devnet/epochs/catch-up") {
    state.slot += 3;
    state.blockNumber += 3;
    json(response, {
      message: "Caught up to wall-clock: 3 blocks produced",
      new_slot: state.slot,
      new_block_number: state.blockNumber,
      blocks_produced: 3
    });
    return;
  }

  if (request.method === "POST" && path === "/api/v1/devnet/snapshot") {
    readBody(request).then((body) => {
      const requestBody = JSON.parse(body);
      const snapshot = {
        name: requestBody.name,
        slot: state.slot,
        block_number: state.blockNumber,
        created_at: Date.now()
      };
      state.snapshots.set(requestBody.name, snapshot);
      json(response, snapshot);
    });
    return;
  }

  if (request.method === "POST" && path.startsWith("/api/v1/devnet/restore/")) {
    const name = decodeURIComponent(path.slice("/api/v1/devnet/restore/".length));
    const snapshot = state.snapshots.get(name);
    if (!snapshot) {
      json(response, { error: "snapshot not found" }, 404);
      return;
    }
    state.slot = snapshot.slot;
    state.blockNumber = snapshot.block_number;
    json(response, {
      message: `Restored snapshot '${name}'`,
      slot: state.slot,
      block_number: state.blockNumber
    });
    return;
  }

  if (request.method === "GET" && path === "/api/v1/devnet/snapshots") {
    json(response, Array.from(state.snapshots.values()));
    return;
  }

  if (request.method === "DELETE" && path.startsWith("/api/v1/devnet/snapshot/")) {
    const name = decodeURIComponent(path.slice("/api/v1/devnet/snapshot/".length));
    state.snapshots.delete(name);
    json(response, { message: `Snapshot '${name}' deleted` });
    return;
  }

  if (request.method === "POST" && path === "/api/v1/devnet/rollback") {
    readBody(request).then((body) => {
      const requestBody = JSON.parse(body || "{}");
      if (requestBody.count !== undefined) {
        const count = Number(requestBody.count);
        state.slot = Math.max(0, state.slot - count);
        state.blockNumber = Math.max(0, state.blockNumber - count);
      } else if (requestBody.slot !== undefined) {
        state.slot = Number(requestBody.slot);
        state.blockNumber = Math.min(state.blockNumber, state.slot);
      } else if (requestBody.block_number !== undefined) {
        state.blockNumber = Number(requestBody.block_number);
        state.slot = Math.min(state.slot, state.blockNumber);
      }
      json(response, {
        message: `Rolled back to slot ${state.slot}, block ${state.blockNumber}`,
        slot: state.slot,
        block_number: state.blockNumber
      });
    });
    return;
  }

  if (request.method === "GET" && path === "/api/v1/devnet/genesis/download") {
    response.writeHead(200, { "content-type": "application/zip" });
    response.end(Buffer.from("fake-zip"));
    return;
  }

  if (request.method === "GET" && path.startsWith("/api/v1/addresses/") && path.includes("/utxos/")) {
    const start = "/api/v1/addresses/".length;
    const separator = path.indexOf("/utxos/", start);
    const address = decodeURIComponent(path.slice(start, separator));
    const asset = decodeURIComponent(path.slice(separator + "/utxos/".length));
    const utxos = state.funded.get(address) ?? [];
    json(response, asset === "lovelace"
      ? utxos
      : utxos.filter((utxo) => utxo.amount.some((amount) => amount.unit === asset)));
    return;
  }

  if (request.method === "GET" && path.startsWith("/api/v1/addresses/") && path.endsWith("/utxos")) {
    const address = decodeURIComponent(path.slice("/api/v1/addresses/".length, -"/utxos".length));
    json(response, state.funded.get(address) ?? []);
    return;
  }

  if (request.method === "GET" && path.startsWith("/api/v1/credentials/") && path.endsWith("/utxos")) {
    json(response, []);
    return;
  }

  if (request.method === "GET" && path.startsWith("/api/v1/utxos/")) {
    const parts = path.split("/");
    const txHash = parts[4];
    const index = parts[5];
    const utxo = Array.from(state.funded.values()).flat()
      .find((candidate) => candidate.tx_hash === txHash && candidate.output_index === Number(index));
    if (!utxo) {
      json(response, { error: "not found" }, 404);
      return;
    }
    json(response, utxo);
    return;
  }

  if (request.method === "POST" && path === "/api/v1/tx/submit") {
    readBody(request).then(() => {
      state.submittedTxs.add("submitted-tx");
      json(response, "submitted-tx");
    });
    return;
  }

  if (request.method === "GET" && path === "/api/v1/txs/submitted-tx") {
    json(response, { hash: "submitted-tx", block: String(state.blockNumber) });
    return;
  }

  if (request.method === "GET" && path === "/api/v1/txs/submitted-tx/utxos") {
    if (!state.submittedTxs.has("submitted-tx")) {
      json(response, { error: "not found" }, 404);
      return;
    }
    json(response, {
      hash: "submitted-tx",
      inputs: [],
      outputs: [{
        tx_hash: "submitted-tx",
        output_index: 0,
        address: "addr_test1submitted",
        amount: [{ unit: "lovelace", quantity: "1" }]
      }]
    });
    return;
  }

  if (request.method === "POST" && path === "/api/v1/utils/txs/evaluate") {
    response.writeHead(200, { "content-type": "application/json" });
    response.end(JSON.stringify({
      type: "jsonwsp/response",
      result: {
        EvaluationResult: {
          "spend:0": { memory: 1, steps: 2 }
        }
      }
    }));
    return;
  }

  json(response, { error: "not found" }, 404);
});

server.listen(port, "127.0.0.1", () => {
  console.log(`fake yano ready on ${port}`);
});

const stop = () => {
  server.close(() => process.exit(0));
};

process.on("SIGTERM", stop);
process.on("SIGINT", stop);

function readBody(request) {
  return new Promise((resolve, reject) => {
    let body = "";
    request.setEncoding("utf8");
    request.on("data", (chunk) => {
      body += chunk;
    });
    request.on("end", () => resolve(body));
    request.on("error", reject);
  });
}

function tip() {
  return {
    slot: state.slot,
    blockNumber: state.blockNumber,
    blockHash: `fake-block-${state.blockNumber}`
  };
}

function json(response, body, status = 200) {
  response.writeHead(status, { "content-type": "application/json" });
  response.end(JSON.stringify(body));
}

function adaToLovelace(ada) {
  const text = String(ada);
  const [whole, fraction = ""] = text.split(".");
  return BigInt(whole) * 1_000_000n + BigInt(fraction.padEnd(6, "0").slice(0, 6) || "0");
}
