// @ts-check

import { chmod, mkdtemp, readFile, stat, writeFile, rm } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { tmpdir } from "node:os";
import { test } from "node:test";
import assert from "node:assert/strict";
import { startYanoDevnet } from "../src/index.js";
import { platformPackage, supportedPlatformKeys } from "../src/platform.js";

const here = dirname(fileURLToPath(import.meta.url));

test("platform mapping includes supported npm binary packages", () => {
  assert.equal(platformPackage("linux", "x64")?.packageName, "@bloxbean/yano-testkit-linux-x64");
  assert.equal(platformPackage("linux", "arm64")?.packageName, "@bloxbean/yano-testkit-linux-arm64");
  assert.equal(platformPackage("darwin", "arm64")?.packageName, "@bloxbean/yano-testkit-macos-arm64");
  assert.equal(platformPackage("win32", "x64")?.packageName, "@bloxbean/yano-testkit-windows-x64");
  assert.deepEqual(supportedPlatformKeys().sort(), [
    "darwin-arm64",
    "linux-arm64",
    "linux-x64",
    "win32-x64"
  ]);
});

test("starts devnet, returns readiness details, and cleans temp storage", async () => {
  const binaryPath = await fakeBinaryPath();
  const argsFile = resolve(await mkdtemp(resolve(tmpdir(), "fake-yano-args-")), "args.json");
  const yano = await startYanoDevnet({
    binaryPath,
    cwd: here,
    blockTimeMillis: 123,
    timeoutMs: 10_000,
    env: {
      FAKE_YANO_ARGS_FILE: argsFile
    }
  });

  assert.equal(yano.type, "ready");
  assert.match(yano.apiBaseUrl, /^http:\/\/127\.0\.0\.1:\d+\/api\/v1\/$/);
  assert.equal(yano.storage.mode, "temp-rocksdb");
  assert.equal(yano.networkMagic, 42);

  const response = await fetch(new URL("node/tip", yano.apiBaseUrl));
  assert.equal(response.status, 200);

  const workDir = yano.workDir;
  await yano.stop();

  const args = /** @type {string[]} */ (JSON.parse(await readFile(argsFile, "utf8")));
  assert.ok(args.includes("-Dquarkus.profile=devnet"));
  assert.ok(args.includes("-Dquarkus.http.host=127.0.0.1"));
  assert.ok(args.includes("-Dyano.storage.rocksdb=true"));
  assert.ok(args.includes("-Dyano.exit-on-epoch-calc-error=false"));
  assert.ok(args.includes("-Dyano.block-producer.block-time-millis=123"));
  const httpPort = Number.parseInt(args.find((arg) => arg.startsWith("-Dquarkus.http.port="))?.split("=")[1] ?? "0", 10);
  const n2nPort = Number.parseInt(args.find((arg) => arg.startsWith("-Dyano.server.port="))?.split("=")[1] ?? "0", 10);
  assert.equal(httpPort > 0, true);
  assert.equal(n2nPort > 0, true);
  assert.notEqual(httpPort, n2nPort);
  assert.ok(args.includes(`-Dyano.storage.path=${yano.storage.path}`));
  await rm(dirname(argsFile), { recursive: true, force: true });

  await assert.rejects(() => stat(workDir));
});

test("keeps persistent storage work directory", async () => {
  const binaryPath = await fakeBinaryPath();
  const workDir = await mkdtemp(resolve(tmpdir(), "yano-testkit-persistent-"));
  const yano = await startYanoDevnet({
    binaryPath,
    cwd: here,
    workDir,
    storage: "persistent-rocksdb",
    timeoutMs: 10_000
  });

  await yano.stop();

  const info = await stat(workDir);
  assert.equal(info.isDirectory(), true);
  await rm(workDir, { recursive: true, force: true });
});

test("rejects persistent storage without caller-owned path", async () => {
  const binaryPath = await fakeBinaryPath();

  await assert.rejects(
    () => startYanoDevnet({
      binaryPath,
      cwd: here,
      storage: "persistent-rocksdb"
    }),
    /persistent-rocksdb requires workDir or storagePath/
  );
});

test("rejects equal configured ports", async () => {
  const binaryPath = await fakeBinaryPath();

  await assert.rejects(
    () => startYanoDevnet({
      binaryPath,
      cwd: here,
      httpPort: 45454,
      n2nPort: 45454
    }),
    /httpPort and n2nPort must be different/
  );
});

test("startup failure includes retained process logs", async () => {
  const binaryPath = await fakeBinaryPath();

  await assert.rejects(
    () => startYanoDevnet({
      binaryPath,
      cwd: here,
      timeoutMs: 5_000,
      env: {
        FAKE_YANO_EXIT_BEFORE_READY: "true"
      }
    }),
    /fake yano startup failed/
  );
});

test("rejects unsupported storage mode before startup", async () => {
  const binaryPath = await fakeBinaryPath();

  await assert.rejects(
    () => startYanoDevnet({
      binaryPath,
      cwd: here,
      storage: /** @type {import("../src/index.js").YanoStorageMode} */ ("memory")
    }),
    /Unsupported Yano storage mode: memory/
  );
});

async function fakeBinaryPath() {
  if (process.platform === "win32") {
    const dir = await mkdtemp(resolve(tmpdir(), "fake-yano-bin-"));
    const cmd = resolve(dir, "fake-yano.cmd");
    const script = resolve(here, "fake-yano.mjs");
    await writeFile(cmd, `@echo off\r\n"${process.execPath}" "${script}" %*\r\n`);
    return cmd;
  }

  const script = resolve(here, "fake-yano.mjs");
  await chmod(script, 0o755);
  return script;
}
