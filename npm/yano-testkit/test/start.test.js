// @ts-check

import { chmod, mkdtemp, readFile, stat, writeFile, rm } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { tmpdir } from "node:os";
import { test } from "node:test";
import assert from "node:assert/strict";
import { startYanoDevnet, YanoHttpError } from "../src/index.js";
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

test("funds an address through the devnet faucet endpoint", async () => {
  const binaryPath = await fakeBinaryPath();
  const yano = await startYanoDevnet({
    binaryPath,
    cwd: here,
    timeoutMs: 10_000
  });

  try {
    const result = await yano.fundAddress("addr_test1qfakeaddress", 12.5);

    assert.deepEqual(result, {
      tx_hash: "fake-fund-tx-1",
      index: 0,
      lovelace: 12_500_000
    });
  } finally {
    await yano.stop();
  }
});

test("validates fundAddress inputs before calling the devnet faucet", async () => {
  const binaryPath = await fakeBinaryPath();
  const yano = await startYanoDevnet({
    binaryPath,
    cwd: here,
    timeoutMs: 10_000
  });

  try {
    await assert.rejects(
      () => yano.fundAddress("", 10),
      /address must not be blank/
    );
    await assert.rejects(
      () => yano.fundAddress("addr_test1qfakeaddress", 0),
      /ada must be positive/
    );
  } finally {
    await yano.stop();
  }
});

test("exposes advanced HTTP-backed devnet helpers", async () => {
  const binaryPath = await fakeBinaryPath();
  const yano = await startYanoDevnet({
    binaryPath,
    cwd: here,
    timeoutMs: 10_000
  });

  try {
    await yano.await.untilReady({ timeoutMs: 1_000 });
    assert.equal(await yano.queries.currentSlot(), 1);
    assert.equal(await yano.queries.currentBlockNumber(), 1);

    const firstFund = await yano.faucet.fundAddress("addr_test1qalice", 2);
    assert.equal(firstFund.lovelace, 2_000_000);
    const secondFund = await yano.faucet.fundAddressLovelace("addr_test1qalice", 1_500_000n);
    assert.equal(secondFund.lovelace, 1_500_000);
    const batchFunds = await yano.faucet.fundAll([
      { address: "addr_test1qbob", ada: 1 },
      { address: "addr_test1qbob", lovelace: "2500000" }
    ]);
    assert.equal(batchFunds.length, 2);

    assert.equal(await yano.assertions.address("addr_test1qalice").balanceLovelace(), 3_500_000n);
    await yano.assertions.address("addr_test1qalice").hasAtLeastAda(3.5);
    assert.equal((await yano.queries.utxo(firstFund.tx_hash, 0)).address, "addr_test1qalice");
    assert.equal((await yano.queries.utxosByAddressAndAsset("addr_test1qalice", "lovelace")).length, 2);
    assert.deepEqual(await yano.queries.utxosByPaymentCredential("credential"), []);
    const currentParams = /** @type {{ min_fee_a: number }} */ (await yano.queries.protocolParameters());
    const epochParams = /** @type {{ epoch: number }} */ (await yano.queries.protocolParameters(0));
    assert.equal(currentParams.min_fee_a, 44);
    assert.equal(epochParams.epoch, 0);
    assert.equal((await yano.queries.latestBlock()).number, 1);
    assert.equal((await yano.queries.block("fake-block")).hash, "fake-block");

    const advanced = await yano.time.advanceSlots(4);
    assert.equal(advanced.new_slot, 5);
    assert.equal(await yano.queries.currentEpoch(), 0);
    assert.equal((await yano.time.advanceSeconds(1)).blocks_produced, 1);
    assert.equal((await yano.time.advanceEpochs(1)).blocks_produced, 10);
    assert.equal((await yano.time.shiftGenesisAndStartProducer(2)).shift_millis, 20_000);
    assert.equal((await yano.time.catchUpToWallClock()).blocks_produced, 3);

    await yano.time.advanceToEpoch(1);
    await yano.await.untilEpochAtLeast(1, { timeoutMs: 1_000 });
    assert.equal(await yano.queries.epochStartSlot(2), 20);

    await yano.time.crossEpochBoundary();
    await yano.assertions.epochAtLeast(2);

    const snapshot = await yano.snapshots.create("before");
    assert.equal(snapshot.name, "before");
    await yano.assertions.snapshotExists("before");
    await yano.time.advanceSlots(2);
    await yano.snapshots.restore("before");
    const withSnapshotResult = await yano.snapshots.withSnapshot("temp", async () => {
      await yano.time.advanceSlots(1);
      return "ok";
    });
    assert.equal(withSnapshotResult, "ok");
    await yano.snapshots.delete("before");
    await yano.assertions.snapshotMissing("before");

    const rollback = await yano.devnet.rollback({ count: 1 });
    assert.equal(typeof rollback.slot, "number");
    assert.equal((await yano.devnet.rollback({ slot: 2 })).slot, 2);
    assert.equal((await yano.devnet.rollback({ blockNumber: 1 })).block_number, 1);
    const genesisZip = await yano.devnet.downloadGenesisZip();
    assert.equal(Buffer.from(genesisZip).toString("utf8"), "fake-zip");

    const txHash = await yano.transactions.submitAndAwait(new Uint8Array([1, 2]), { timeoutMs: 1_000 });
    assert.equal(txHash, "submitted-tx");
    assert.equal(await yano.transactions.submitHex("00"), "submitted-tx");
    assert.equal((await yano.queries.tx(txHash)).hash, "submitted-tx");
    const evaluation = /** @type {any} */ (await yano.transactions.evaluateHex("00"));
    assert.equal(evaluation.result.EvaluationResult["spend:0"].memory, 1);
    const cborEvaluation = /** @type {any} */ (await yano.transactions.evaluateCbor(new Uint8Array([0])));
    assert.equal(cborEvaluation.result.EvaluationResult["spend:0"].steps, 2);

    await yano.assertions.nodeIsRunning();
    await yano.assertions.runtimeNotDegraded();
  } finally {
    await yano.stop();
  }
});

test("HTTP client exposes status and response body on failures", async () => {
  const binaryPath = await fakeBinaryPath();
  const yano = await startYanoDevnet({
    binaryPath,
    cwd: here,
    timeoutMs: 10_000
  });

  try {
    let failure;
    try {
      await yano.client.getJson("missing-route");
    } catch (error) {
      failure = error;
    }

    assert.ok(failure instanceof YanoHttpError);
    assert.equal(failure.status, 404);
    assert.match(failure.bodyText, /not found/);
  } finally {
    await yano.stop();
  }
});

test("await helper times out with an assertion error", async () => {
  const binaryPath = await fakeBinaryPath();
  const yano = await startYanoDevnet({
    binaryPath,
    cwd: here,
    timeoutMs: 10_000
  });

  try {
    await assert.rejects(
      () => yano.await.untilSlotAtLeast(999, { timeoutMs: 20, pollIntervalMs: 5 }),
      (error) => error instanceof Error
        && error.name === "AssertionError"
        && /slot >= 999/.test(error.message)
    );
  } finally {
    await yano.stop();
  }
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
