import { startYanoDevnet } from "@bloxbean/yano-testkit";

const blockTimeMillis = numberFromEnv("YANO_BLOCK_TIME_MILLIS", 200);
const timeoutMs = numberFromEnv("YANO_TESTKIT_TIMEOUT_MS", 90_000);
const verboseLogs = process.env.YANO_TESTKIT_VERBOSE === "true";
const testAddress = process.env.YANO_TEST_ADDRESS;
const testAda = numberFromEnv("YANO_TEST_ADA", 1000);

let yano;

try {
  yano = await startYanoDevnet({
    blockTimeMillis,
    timeoutMs,
    onStdout: verboseLogs ? line => console.log(`[yano] ${line}`) : undefined,
    onStderr: verboseLogs ? line => console.error(`[yano] ${line}`) : undefined
  });

  console.log("Yano devnet started");
  console.log(`  pid: ${yano.pid}`);
  console.log(`  baseUrl: ${yano.baseUrl}`);
  console.log(`  apiBaseUrl: ${yano.apiBaseUrl}`);
  console.log(`  n2nPort: ${yano.n2nPort}`);
  console.log(`  storage: ${yano.storage.path}`);

  const health = await getJson(new URL("q/health/ready", yano.baseUrl));
  assert(health.status === "UP", `Expected health status UP, got ${JSON.stringify(health)}`);
  console.log(`Ready health: ${health.status}`);

  const firstTip = await getJson(yano.url("node/tip"));
  console.log(`Initial tip: ${formatTip(firstTip)}`);

  if (testAddress) {
    assert(typeof yano.fundAddress === "function", "Installed @bloxbean/yano-testkit does not expose fundAddress()");
    const fundResult = await yano.fundAddress(testAddress, testAda);
    console.log(`Funded ${testAda} ADA: tx=${fundResult.tx_hash}#${fundResult.index}, lovelace=${fundResult.lovelace}`);
  } else {
    console.log("Set YANO_TEST_ADDRESS to also exercise the devnet fundAddress helper");
  }

  await sleep(Math.max(blockTimeMillis * 3, 1_000));

  const secondTip = await getJson(yano.url("node/tip"));
  console.log(`Later tip:   ${formatTip(secondTip)}`);

  assertTipShape(secondTip);
  console.log("Smoke test passed");
} catch (error) {
  console.error("Smoke test failed");
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

async function getJson(url) {
  const response = await fetch(url);
  const body = await response.text();
  if (!response.ok) {
    throw new Error(`GET ${url} failed with ${response.status}: ${body}`);
  }
  return body ? JSON.parse(body) : null;
}

function assertTipShape(tip) {
  assert(tip && typeof tip === "object", "Tip response should be an object");
  const hasSlot = Object.prototype.hasOwnProperty.call(tip, "slot");
  const hasBlock = Object.prototype.hasOwnProperty.call(tip, "block")
    || Object.prototype.hasOwnProperty.call(tip, "blockNumber")
    || Object.prototype.hasOwnProperty.call(tip, "block_number");
  assert(hasSlot || hasBlock, `Tip response should include slot or block information: ${JSON.stringify(tip)}`);
}

function formatTip(tip) {
  if (!tip || typeof tip !== "object") {
    return JSON.stringify(tip);
  }
  const slot = tip.slot ?? tip.slotNo ?? tip.slot_no ?? "unknown";
  const block = tip.block ?? tip.blockNumber ?? tip.block_number ?? "unknown";
  const hash = tip.hash ?? tip.blockHash ?? tip.block_hash ?? "";
  return `slot=${slot}, block=${block}${hash ? `, hash=${String(hash).slice(0, 16)}...` : ""}`;
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
