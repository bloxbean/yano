import { access } from "node:fs/promises";
import { constants } from "node:fs";
import { basename, dirname, resolve } from "node:path";

import xhr2 from "xhr2";
global.XMLHttpRequest = xhr2;

import { BlockfrostProvider, MeshTxBuilder, MeshWallet } from "@meshsdk/core";
import { startYanoDevnet } from "@bloxbean/yano-testkit";

const TEST_MNEMONIC = (
  process.env.TEST_MNEMONIC ||
  "wrist approve ethics forest knife treat noise great three simple prize happy toe dynamic number hunt trigger install wrong change decorate vendor glow erosion"
).split(" ");

const blockTimeMillis = numberFromEnv("YANO_BLOCK_TIME_MILLIS", 200);
const timeoutMs = numberFromEnv("YANO_TESTKIT_TIMEOUT_MS", 120_000);
const fundingAda = numberFromEnv("YANO_FUNDING_ADA", 20);
const transferAda = numberFromEnv("YANO_TRANSFER_ADA", 2);
const verboseLogs = process.env.YANO_TESTKIT_VERBOSE === "true";

let yano;

try {
  yano = await startYanoDevnet({
    binaryPath: await defaultBinaryPath(),
    cwd: await defaultCwd(),
    blockTimeMillis,
    timeoutMs,
    onStdout: verboseLogs ? line => console.log(`[yano] ${line}`) : undefined,
    onStderr: verboseLogs ? line => console.error(`[yano] ${line}`) : undefined
  });

  console.log("Yano devnet started");
  console.log(`  apiBaseUrl: ${yano.apiBaseUrl}`);
  console.log(`  storage: ${yano.storage.path}`);

  await yano.await.untilReady({ timeoutMs: 10_000 });
  await yano.assertions.nodeIsRunning();
  await yano.assertions.runtimeNotDegraded();

  const provider = new BlockfrostProvider(yano.apiBaseUrl);
  const wallet = new MeshWallet({
    networkId: 0,
    fetcher: provider,
    submitter: provider,
    key: {
      type: "mnemonic",
      words: TEST_MNEMONIC
    }
  });
  await wallet.init();

  const walletAddress = await wallet.getChangeAddress();
  assert(walletAddress?.startsWith("addr_test"), `Expected testnet address, got ${walletAddress}`);
  console.log(`MeshJS wallet: ${walletAddress}`);

  const params = await provider.fetchProtocolParameters();
  assert(params?.minFeeA !== undefined, "MeshJS protocol params should include minFeeA");
  assert(params?.minFeeB !== undefined, "MeshJS protocol params should include minFeeB");
  console.log(`Protocol params: minFeeA=${params.minFeeA}, minFeeB=${params.minFeeB}`);

  const fundResult = await yano.faucet.fundAddress(walletAddress, fundingAda);
  console.log(`Funded wallet: ${fundResult.tx_hash}#${fundResult.index}, lovelace=${fundResult.lovelace}`);
  await yano.await.untilTxVisible(fundResult.tx_hash, { timeoutMs: 10_000, pollIntervalMs: 200 });
  await yano.assertions.address(walletAddress).hasAtLeastAda(fundingAda);

  const fundedUtxos = await waitForMeshUtxos(provider, walletAddress);
  const fundedBalance = balanceLovelace(fundedUtxos);
  assert(fundedBalance >= adaToLovelace(fundingAda), `Expected funded balance >= ${fundingAda} ADA`);
  console.log(`MeshJS sees ${fundedUtxos.length} funded UTXO(s), balance=${fundedBalance} lovelace`);

  const transferLovelace = adaToLovelace(transferAda);
  const unsignedTx = await new MeshTxBuilder({ fetcher: provider })
    .txOut(walletAddress, [{ unit: "lovelace", quantity: transferLovelace.toString() }])
    .changeAddress(walletAddress)
    .selectUtxosFrom(fundedUtxos)
    .complete();
  assert(unsignedTx, "MeshJS should build an unsigned transaction");

  const signedTx = await wallet.signTx(unsignedTx);
  assert(signedTx, "MeshJS wallet should sign the transaction");

  const txHash = await wallet.submitTx(signedTx);
  assert(txHash, "MeshJS submitTx should return a transaction hash");
  console.log(`Submitted MeshJS self-transfer: ${txHash}`);

  await yano.await.untilTxVisible(txHash, { timeoutMs: 20_000, pollIntervalMs: 200 });
  const yanoTx = await yano.queries.tx(txHash);
  assert(yanoTx, "Yano query should return submitted transaction info");
  const txUtxos = await yano.queries.txUtxos(txHash);
  assert(hasOutputToAddress(txUtxos, walletAddress), "Submitted tx should have an output to the wallet address");

  const meshTx = await provider.fetchTxInfo(txHash);
  assert(meshTx, "MeshJS should fetch submitted transaction info");
  const postTransferUtxos = await waitForMeshUtxos(provider, walletAddress);
  assert(postTransferUtxos.length > 0, "Wallet should still have UTXOs after self-transfer");

  await yano.assertions.address(walletAddress).hasAtLeastAda(transferAda);
  console.log("MeshJS transfer test passed");
} catch (error) {
  console.error("MeshJS transfer test failed");
  console.error(error instanceof Error ? error.stack : error);
  if (yano) {
    console.error("\nYano log tail:");
    console.error(yano.logs());
  }
  process.exitCode = 1;
} finally {
  if (yano) {
    await yano.stop();
    console.log("Yano devnet stopped");
  }
}

async function defaultBinaryPath() {
  if (process.env.YANO_TESTKIT_BINARY) {
    return undefined;
  }
  const candidate = process.platform === "win32"
    ? resolve("../../app/build/yano.exe")
    : resolve("../../app/build/yano");
  try {
    await access(candidate, constants.X_OK);
    return candidate;
  } catch {
    return undefined;
  }
}

async function defaultCwd() {
  if (process.env.YANO_TESTKIT_CWD) {
    return resolve(process.env.YANO_TESTKIT_CWD);
  }
  const envBinary = process.env.YANO_TESTKIT_BINARY;
  if (envBinary) {
    const binaryDir = dirname(resolve(envBinary));
    if (basename(binaryDir) === "build" && basename(dirname(binaryDir)) === "app") {
      return dirname(binaryDir);
    }
    return undefined;
  }
  const candidate = resolve("../../app");
  try {
    await access(resolve(candidate, "config"), constants.R_OK);
    return candidate;
  } catch {
    return undefined;
  }
}

async function waitForMeshUtxos(provider, address) {
  let last = [];
  const deadline = Date.now() + 10_000;
  while (Date.now() <= deadline) {
    last = await provider.fetchAddressUTxOs(address);
    if (last.length > 0) {
      return last;
    }
    await sleep(200);
  }
  throw new Error(`Timed out waiting for MeshJS UTXOs at ${address}; last=${JSON.stringify(last)}`);
}

function balanceLovelace(utxos) {
  return utxos.reduce((sum, utxo) => {
    const lovelace = utxo.output.amount.find(amount => amount.unit === "lovelace");
    return sum + BigInt(lovelace?.quantity ?? "0");
  }, 0n);
}

function hasOutputToAddress(txUtxos, address) {
  if (!txUtxos || typeof txUtxos !== "object" || !Array.isArray(txUtxos.outputs)) {
    return false;
  }
  return txUtxos.outputs.some(output => output.address === address);
}

function adaToLovelace(ada) {
  return BigInt(Math.trunc(ada * 1_000_000));
}

function numberFromEnv(name, fallback) {
  const raw = process.env[name];
  if (!raw) {
    return fallback;
  }
  const parsed = Number.parseInt(raw, 10);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : fallback;
}

function sleep(ms) {
  return new Promise(resolve => setTimeout(resolve, ms));
}

function assert(condition, message) {
  if (!condition) {
    throw new Error(message);
  }
}
