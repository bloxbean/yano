// Shared helpers: Yano devnet faucet + timing/stats, used by every Mesh scenario.

export const YANO_URL = process.env.YANO_URL ?? 'http://localhost:7070/api/v1';

/** Inject a UTXO directly into the node's store. Returns "txHash#index". */
export async function fund(address, ada) {
  const res = await fetch(`${YANO_URL}/devnet/fund`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ address, ada }),
  });
  if (!res.ok) throw new Error(`fund failed ${res.status}: ${await res.text()}`);
  const j = await res.json();
  return `${j.tx_hash}#${j.index}`;
}

export async function protocolParams() {
  const res = await fetch(`${YANO_URL}/epochs/latest/parameters`);
  if (!res.ok) throw new Error(`params ${res.status}`);
  return res.json();
}

export async function latestBlock() {
  const res = await fetch(`${YANO_URL}/blocks/latest`);
  if (!res.ok) throw new Error(`tip ${res.status}`);
  return res.json();
}

export async function mempoolCount() {
  try {
    const root = YANO_URL.replace(/\/api\/v\d+$/, '');
    const text = await (await fetch(`${root}/q/metrics`)).text();
    const line = text.split('\n').find((l) => l.startsWith('yano_node_mempool_transactions'));
    return line ? Number(line.split(' ').pop()) : -1;
  } catch {
    return -1;
  }
}

/** Wait until an outpoint is visible in canonical state. */
export async function awaitCanonical(txHash, index = 0, timeoutMs = 90_000) {
  const started = Date.now();
  while (Date.now() - started < timeoutMs) {
    const r = await fetch(`${YANO_URL}/utxos/${txHash}/${index}`);
    if (r.ok) return Date.now() - started;
    await sleep(300);
  }
  return -1;
}

export const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** Minimal latency/outcome recorder mirroring the Java harness. */
export class Stats {
  constructor(name) {
    this.name = name;
    this.submitted = 0;
    this.accepted = 0;
    this.categories = new Map();
    this.latencies = [];
    this.startedAt = Date.now();
  }

  record(ok, category, ms) {
    this.submitted += 1;
    if (ok) this.accepted += 1;
    this.categories.set(category, (this.categories.get(category) ?? 0) + 1);
    if (ms != null) this.latencies.push(ms);
  }

  pct(p) {
    if (!this.latencies.length) return 0;
    const s = [...this.latencies].sort((a, b) => a - b);
    return s[Math.min(s.length - 1, Math.ceil((p / 100) * s.length) - 1)];
  }

  summary() {
    const secs = Math.max(0.001, (Date.now() - this.startedAt) / 1000);
    return {
      name: this.name,
      submitted: this.submitted,
      accepted: this.accepted,
      acceptRate: this.submitted ? +(100 * this.accepted / this.submitted).toFixed(1) : 0,
      tps: +(this.accepted / secs).toFixed(1),
      p50: this.pct(50),
      p95: this.pct(95),
      p99: this.pct(99),
      outcomes: Object.fromEntries([...this.categories.entries()].sort()),
    };
  }
}

/** Classify a submit failure into a stable bucket. */
export function categorize(err) {
  const m = String(err?.message ?? err);
  for (const s of ['TRANSACTION_CAPACITY', 'BYTE_CAPACITY', 'INDEX_CAPACITY',
    'CONFLICT', 'DUPLICATE', 'MALFORMED', 'UtxoNotFound', 'InvalidScriptDataHash',
    'ValueNotConserved', 'BadInputs', 'FeeTooSmall', 'MaxTxSize']) {
    if (m.includes(s)) return s;
  }
  return m.slice(0, 60);
}
