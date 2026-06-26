// @ts-check

import { spawn } from "node:child_process";
import { createServer } from "node:net";
import { mkdtemp, rm } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { tmpdir } from "node:os";
import { resolveYanoBinary, assertBinaryExists } from "./binary.js";
import { createYanoHttpClient } from "./http.js";
import { createYanoFacades } from "./facades.js";

export { YanoHttpError } from "./http.js";

const DEFAULT_TIMEOUT_MS = 60_000;
const DEFAULT_BLOCK_TIME_MS = 200;
const DEFAULT_NETWORK_MAGIC = 42;
const MAX_LOG_LINES = 500;

/**
 * @typedef {"temp-rocksdb" | "persistent-rocksdb"} YanoStorageMode
 *
 * @typedef StartYanoDevnetOptions
 * @property {string=} binaryPath
 * @property {string=} cwd
 * @property {number=} httpPort
 * @property {number=} n2nPort
 * @property {number=} blockTimeMillis
 * @property {number=} networkMagic
 * @property {YanoStorageMode=} storage
 * @property {string=} storagePath
 * @property {string=} workDir
 * @property {boolean=} preserveWorkDir
 * @property {number=} timeoutMs
 * @property {Record<string, string | undefined>=} env
 * @property {string[]=} extraArgs
 * @property {(line: string) => void=} onStdout
 * @property {(line: string) => void=} onStderr
 *
 * @typedef YanoReadyInfo
 * @property {"ready"} type
 * @property {number} pid
 * @property {string} baseUrl
 * @property {string} apiBaseUrl
 * @property {number} n2nPort
 * @property {number} networkMagic
 * @property {{ mode: YanoStorageMode, path: string }} storage
 * @property {string} workDir
 *
 * @typedef YanoFundResult
 * @property {string} tx_hash
 * @property {number} index
 * @property {number} lovelace
 *
 * @typedef {YanoReadyInfo & import("./facades.js").YanoFacades & {
 *   process: import("node:child_process").ChildProcess,
 *   logs: () => string,
 *   url: (path: string) => URL,
 *   fundAddress: (address: string, ada: number) => Promise<YanoFundResult>,
 *   stop: () => Promise<void>
 * }} YanoDevnet
 */

/**
 * Starts a Yano native binary in devnet mode and waits until the HTTP API is ready.
 *
 * @param {StartYanoDevnetOptions} [options]
 * @returns {Promise<YanoDevnet>}
 */
export async function startYanoDevnet(options = {}) {
  const binaryPath = resolveYanoBinary(options);
  assertBinaryExists(binaryPath);
  const storageMode = options.storage ?? "temp-rocksdb";
  validateStorageMode(storageMode, options);

  const { httpPort, n2nPort } = await resolvePorts(options.httpPort, options.n2nPort);
  const configuredStoragePath = options.storagePath ? resolve(options.storagePath) : undefined;
  const ownsWorkDir = storageMode === "temp-rocksdb" && !options.workDir && !configuredStoragePath;
  const workDir = resolve(
    options.workDir ??
    (configuredStoragePath ? dirname(configuredStoragePath) : await mkdtemp(resolve(tmpdir(), "yano-testkit-")))
  );
  const storagePath = configuredStoragePath ?? resolve(workDir, "chainstate");
  const cwd = resolve(options.cwd ?? inferCwd(binaryPath));
  const timeoutMs = options.timeoutMs ?? readTimeoutFromEnv();
  const baseUrl = `http://127.0.0.1:${httpPort}/`;
  const apiBaseUrl = new URL("api/v1/", baseUrl).toString();
  const logs = createLogBuffer();

  const args = [
    "-Dquarkus.profile=devnet",
    "-Dquarkus.http.host=127.0.0.1",
    `-Dquarkus.http.port=${httpPort}`,
    `-Dyano.server.port=${n2nPort}`,
    `-Dyano.remote.protocol-magic=${options.networkMagic ?? DEFAULT_NETWORK_MAGIC}`,
    "-Dyano.storage.rocksdb=true",
    `-Dyano.storage.path=${storagePath}`,
    `-Dyano.block-producer.block-time-millis=${options.blockTimeMillis ?? DEFAULT_BLOCK_TIME_MS}`,
    "-Dyano.exit-on-epoch-calc-error=false",
    ...(options.extraArgs ?? [])
  ];

  const child = spawn(binaryPath, args, {
    cwd,
    env: {
      ...process.env,
      ...options.env
    },
    shell: process.platform === "win32" && binaryPath.toLowerCase().endsWith(".cmd"),
    stdio: ["ignore", "pipe", "pipe"]
  });

  if (!child.pid) {
    throw new Error("Failed to start Yano process");
  }

  wireOutput(child.stdout, logs, options.onStdout);
  wireOutput(child.stderr, logs, options.onStderr);

  const cleanup = async () => {
    if (ownsWorkDir && !options.preserveWorkDir && storageMode === "temp-rocksdb") {
      await rm(workDir, { recursive: true, force: true });
    }
  };

  try {
    await waitForReady(child, apiBaseUrl, timeoutMs, logs);
  } catch (error) {
    await stopProcess(child);
    await cleanup();
    throw error;
  }

  /** @type {YanoReadyInfo} */
  const ready = {
    type: "ready",
    pid: child.pid,
    baseUrl,
    apiBaseUrl,
    n2nPort,
    networkMagic: options.networkMagic ?? DEFAULT_NETWORK_MAGIC,
    storage: {
      mode: storageMode,
      path: storagePath
    },
    workDir
  };
  const client = createYanoHttpClient(apiBaseUrl);
  const facades = createYanoFacades(client);

  return {
    ...ready,
    ...facades,
    process: child,
    logs: logs.text,
    url: (path) => new URL(path, apiBaseUrl),
    fundAddress: (address, ada) => /** @type {Promise<YanoFundResult>} */ (facades.faucet.fundAddress(address, ada)),
    stop: async () => {
      await stopProcess(child);
      await cleanup();
    }
  };
}

/**
 * @param {YanoStorageMode} storageMode
 * @param {StartYanoDevnetOptions} options
 */
function validateStorageMode(storageMode, options) {
  if (storageMode !== "temp-rocksdb" && storageMode !== "persistent-rocksdb") {
    throw new Error(`Unsupported Yano storage mode: ${storageMode}`);
  }
  if (storageMode === "persistent-rocksdb" && !options.workDir && !options.storagePath) {
    throw new Error("persistent-rocksdb requires workDir or storagePath");
  }
}

/**
 * @param {string} binaryPath
 */
function inferCwd(binaryPath) {
  const binaryDir = dirname(binaryPath);
  if (binaryDir.endsWith("/bin") || binaryDir.endsWith("\\bin")) {
    return dirname(binaryDir);
  }
  return binaryDir;
}

function readTimeoutFromEnv() {
  const raw = process.env.YANO_TESTKIT_TIMEOUT_MS;
  if (!raw) {
    return DEFAULT_TIMEOUT_MS;
  }
  const parsed = Number.parseInt(raw, 10);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : DEFAULT_TIMEOUT_MS;
}

/**
 * @param {number | undefined} requestedHttpPort
 * @param {number | undefined} requestedN2nPort
 */
async function resolvePorts(requestedHttpPort, requestedN2nPort) {
  const httpPort = normalizePort(requestedHttpPort, "httpPort");
  const n2nPort = normalizePort(requestedN2nPort, "n2nPort");

  if (httpPort && n2nPort) {
    assertDistinctPorts(httpPort, n2nPort);
    return { httpPort, n2nPort };
  }
  if (httpPort) {
    return {
      httpPort,
      n2nPort: await findAvailablePortExcluding(new Set([httpPort]))
    };
  }
  if (n2nPort) {
    return {
      httpPort: await findAvailablePortExcluding(new Set([n2nPort])),
      n2nPort
    };
  }

  const reservations = await reserveAvailablePorts(2);
  try {
    return {
      httpPort: reservations[0].port,
      n2nPort: reservations[1].port
    };
  } finally {
    await Promise.all(reservations.map((reservation) => reservation.close()));
  }
}

/**
 * @param {number | undefined} requested
 * @param {string} name
 */
function normalizePort(requested, name) {
  if (requested === undefined || requested === 0) {
    return undefined;
  }
  if (!Number.isInteger(requested) || requested < 0 || requested > 65535) {
    throw new Error(`${name} must be an integer between 0 and 65535`);
  }
  return requested;
}

/**
 * @param {number} httpPort
 * @param {number} n2nPort
 */
function assertDistinctPorts(httpPort, n2nPort) {
  if (httpPort === n2nPort) {
    throw new Error("httpPort and n2nPort must be different");
  }
}

/**
 * @param {Set<number>} excluded
 */
async function findAvailablePortExcluding(excluded) {
  for (let attempt = 0; attempt < 10; attempt++) {
    const reservation = await reserveAvailablePort();
    try {
      if (!excluded.has(reservation.port)) {
        return reservation.port;
      }
    } finally {
      await reservation.close();
    }
  }
  throw new Error(`Could not allocate a free port outside ${Array.from(excluded).join(", ")}`);
}

/**
 * @param {number} count
 */
async function reserveAvailablePorts(count) {
  const reservations = [];
  try {
    for (let i = 0; i < count; i++) {
      reservations.push(await reserveAvailablePort());
    }
    return reservations;
  } catch (error) {
    await Promise.all(reservations.map((reservation) => reservation.close()));
    throw error;
  }
}

async function reserveAvailablePort() {
  return new Promise((resolvePortValue, reject) => {
    const server = createServer();
    server.unref();
    server.on("error", reject);
    server.listen(0, "127.0.0.1", () => {
      const address = server.address();
      if (typeof address === "object" && address) {
        resolvePortValue({
          port: address.port,
          close: () => new Promise((resolveClose) => server.close(resolveClose))
        });
      } else {
        server.close(() => reject(new Error("Could not allocate a free port")));
      }
    });
  });
}

/**
 * @param {import("node:stream").Readable | null} stream
 * @param {{ push(line: string): void }} logs
 * @param {((line: string) => void)=} callback
 */
function wireOutput(stream, logs, callback) {
  if (!stream) {
    return;
  }
  let pending = "";
  stream.setEncoding("utf8");
  stream.on("data", (chunk) => {
    pending += chunk;
    const lines = pending.split(/\r?\n/);
    pending = lines.pop() ?? "";
    for (const line of lines) {
      logs.push(line);
      callback?.(line);
    }
  });
  stream.on("end", () => {
    if (pending) {
      logs.push(pending);
      callback?.(pending);
    }
  });
}

function createLogBuffer() {
  /** @type {string[]} */
  const lines = [];
  return {
    /** @param {string} line */
    push(line) {
      lines.push(line);
      if (lines.length > MAX_LOG_LINES) {
        lines.splice(0, lines.length - MAX_LOG_LINES);
      }
    },
    text() {
      return lines.join("\n");
    }
  };
}

/**
 * @param {import("node:child_process").ChildProcess} child
 * @param {string} apiBaseUrl
 * @param {number} timeoutMs
 * @param {{ text(): string }} logs
 */
async function waitForReady(child, apiBaseUrl, timeoutMs, logs) {
  const deadline = Date.now() + timeoutMs;
  let exitCode = null;
  child.once("exit", (code, signal) => {
    exitCode = { code, signal };
  });

  while (Date.now() < deadline) {
    if (exitCode) {
      throw new Error(`Yano exited before readiness (${formatExit(exitCode)}).\n${logs.text()}`);
    }
    try {
      const health = await fetch(new URL("../../q/health/ready", apiBaseUrl), {
        signal: AbortSignal.timeout(2_000)
      });
      if (health.ok) {
        const tip = await fetch(new URL("node/tip", apiBaseUrl), {
          signal: AbortSignal.timeout(2_000)
        });
        if (tip.ok) {
          return;
        }
      }
    } catch {
      // Retry until timeout; startup can take a few seconds.
    }
    await delay(250);
  }

  throw new Error(`Timed out waiting ${timeoutMs}ms for Yano readiness.\n${logs.text()}`);
}

/**
 * @param {{ code: number | null, signal: NodeJS.Signals | null }} exit
 */
function formatExit(exit) {
  return `code=${exit.code ?? "null"}, signal=${exit.signal ?? "null"}`;
}

/**
 * @param {number} ms
 */
function delay(ms) {
  return new Promise((resolveDelay) => setTimeout(resolveDelay, ms));
}

/**
 * @param {import("node:child_process").ChildProcess} child
 */
async function stopProcess(child) {
  if (child.exitCode !== null || child.signalCode !== null) {
    return;
  }

  if (process.platform === "win32" && child.pid) {
    spawn("taskkill", ["/pid", String(child.pid), "/T", "/F"], { stdio: "ignore" });
  } else {
    child.kill("SIGTERM");
  }

  const exited = await waitForExit(child, 10_000);
  if (!exited && child.exitCode === null && child.signalCode === null) {
    child.kill("SIGKILL");
    await waitForExit(child, 5_000);
  }
}

/**
 * @param {import("node:child_process").ChildProcess} child
 * @param {number} timeoutMs
 */
function waitForExit(child, timeoutMs) {
  if (child.exitCode !== null || child.signalCode !== null) {
    return Promise.resolve(true);
  }

  return new Promise((resolveExit) => {
    const timeout = setTimeout(() => resolveExit(false), timeoutMs);
    child.once("exit", () => {
      clearTimeout(timeout);
      resolveExit(true);
    });
  });
}
