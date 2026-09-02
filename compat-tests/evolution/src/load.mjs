// Evolution SDK load test against Yano: independent payments plus
// parent->child chains submitted without waiting for confirmation.
import { Lucid, Blockfrost } from '@evolution-sdk/lucid';

const YANO_URL = process.env.YANO_URL ?? 'http://localhost:7070/api/v1';
const NETWORK = 'Preview';
const WORKERS = Number(process.env.WORKERS ?? 4);
const UTXOS = Number(process.env.UTXOS_PER_WORKER ?? 12);
const CHAIN_WORKERS = Number(process.env.CHAIN_WORKERS ?? 2);
const CHAIN_DEPTH = Number(process.env.CHAIN_DEPTH ?? 5);
const DURATION_S = Number(process.env.DURATION_SECONDS ?? 60);
const PAY = 2_000_000n;
const CHAIN_STEP = 3_000_000n;

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const SEED = 'test test test test test test test test test test test test '
  + 'test test test test test test test test test test test sauce';

async function fund(address, ada) {
  const res = await fetch(`${YANO_URL}/devnet/fund`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ address, ada }),
  });
  if (!res.ok) throw new Error(`fund ${res.status}`);
  const j = await res.json();
  return { txHash: j.tx_hash, outputIndex: j.index };
}

async function mempoolCount() {
  try {
    const root = YANO_URL.replace(/\/api\/v\d+$/, '');
    const t = await (await fetch(`${root}/q/metrics`)).text();
    const l = t.split('\n').find((x) => x.startsWith('yano_node_mempool_transactions'));
    return l ? Number(l.split(' ').pop()) : -1;
  } catch { return -1; }
}

function categorize(err) {
  const m = String(err?.message ?? err);
  for (const s of ['TRANSACTION_CAPACITY', 'BYTE_CAPACITY', 'INDEX_CAPACITY', 'CONFLICT',
    'DUPLICATE', 'MALFORMED', 'UtxoNotFound', 'ValueNotConserved', 'BadInputs',
    'FeeTooSmall', 'MaxTxSize', 'Resource not found']) {
    if (m.includes(s)) return s;
  }
  return m.replace(/\s+/g, ' ').slice(0, 60);
}

class Stats {
  constructor(n) { this.n = n; this.sub = 0; this.acc = 0; this.cat = new Map(); this.lat = []; this.t0 = Date.now(); }
  rec(ok, c, ms) {
    this.sub++; if (ok) this.acc++;
    this.cat.set(c, (this.cat.get(c) ?? 0) + 1);
    if (ms != null) this.lat.push(ms);
  }
  pct(p) { if (!this.lat.length) return 0; const s = [...this.lat].sort((a, b) => a - b); return s[Math.min(s.length - 1, Math.ceil(p / 100 * s.length) - 1)]; }
  sum() {
    const secs = Math.max(0.001, (Date.now() - this.t0) / 1000);
    return {
      name: this.n, submitted: this.sub, accepted: this.acc,
      acceptRate: this.sub ? +(100 * this.acc / this.sub).toFixed(1) : 0,
      tps: +(this.acc / secs).toFixed(1),
      p50: this.pct(50), p95: this.pct(95), p99: this.pct(99),
      outcomes: Object.fromEntries([...this.cat.entries()].sort()),
    };
  }
}

const utxoOf = (txHash, outputIndex, address, lovelace) => ({
  txHash, outputIndex, address, assets: { lovelace }, datumHash: null, datum: null, scriptRef: null,
});

async function lucidAt(i) {
  const l = await Lucid(new Blockfrost(YANO_URL, 'dummy-key'), NETWORK);
  l.selectWallet.fromSeed(SEED, { accountIndex: i });
  return l;
}

console.log(`=== Evolution SDK load test against ${YANO_URL} ===`);
console.log(`workers=${WORKERS}x${UTXOS}, chains=${CHAIN_WORKERS} depth=${CHAIN_DEPTH}, ${DURATION_S}s\n`);

const workers = [];
for (let i = 0; i < WORKERS; i++) {
  const l = await lucidAt(i);
  const addr = await l.wallet().address();
  const sinkL = await lucidAt(100 + i);
  const sink = await sinkL.wallet().address();
  const pool = [];
  for (let k = 0; k < UTXOS; k++) {
    const { txHash, outputIndex } = await fund(addr, 200);
    pool.push(utxoOf(txHash, outputIndex, addr, 200_000_000n));
  }
  workers.push({ l, addr, sink, pool });
  console.log(`worker ${i} ${addr.slice(0, 26)}... ${pool.length} utxos`);
}
console.log();

const regular = new Stats('regular');
const chained = new Stats('chained');
const chainOutcomes = [];
const deadline = Date.now() + DURATION_S * 1000;

async function payFrom(l, utxo, toAddr, lovelace) {
  const tx = await l.newTx().collectFrom([utxo]).pay.ToAddress(toAddr, { lovelace }).complete();
  const signed = await tx.sign.withWallet().complete();
  return signed.submit();
}

async function regularLoop(w) {
  while (Date.now() < deadline) {
    const u = w.pool.pop();
    if (!u) {
      for (let k = 0; k < UTXOS; k++) {
        const { txHash, outputIndex } = await fund(w.addr, 200);
        w.pool.push(utxoOf(txHash, outputIndex, w.addr, 200_000_000n));
      }
      continue;
    }
    const t0 = Date.now();
    try { await payFrom(w.l, u, w.sink, PAY); regular.rec(true, 'ACCEPTED', Date.now() - t0); }
    catch (e) { regular.rec(false, categorize(e), Date.now() - t0); }
  }
}

async function chainLoop(id) {
  // Dedicated account range per chain worker so links never collide.
  const base = 200 + id * (CHAIN_DEPTH + 1);
  const ls = [];
  const addrs = [];
  for (let d = 0; d <= CHAIN_DEPTH; d++) {
    const l = await lucidAt(base + d);
    ls.push(l);
    addrs.push(await l.wallet().address());
  }
  while (Date.now() < deadline) {
    const { txHash, outputIndex } = await fund(addrs[0], 500);
    let cur = utxoOf(txHash, outputIndex, addrs[0], 500_000_000n);
    let curLovelace = 500_000_000n;
    let depth = 0;
    let stop = 'COMPLETE';
    const t0 = Date.now();
    for (let d = 0; d < CHAIN_DEPTH; d++) {
      const amount = curLovelace - CHAIN_STEP;
      if (amount < 2_000_000n) { stop = 'VALUE_EXHAUSTED'; break; }
      const s = Date.now();
      try {
        const h = await payFrom(ls[d], cur, addrs[d + 1], amount);
        chained.rec(true, 'ACCEPTED', Date.now() - s);
        depth++;
        cur = utxoOf(h, 0, addrs[d + 1], amount);
        curLovelace = amount;
      } catch (e) {
        stop = categorize(e);
        chained.rec(false, stop, Date.now() - s);
        break;
      }
    }
    chainOutcomes.push({ id, depth, stop, ms: Date.now() - t0 });
  }
}

const progress = setInterval(async () => {
  console.log(`  regular ${regular.acc}/${regular.sub}  chained ${chained.acc}/${chained.sub}`
    + `  chains=${chainOutcomes.length}  mempool=${await mempoolCount()}`);
}, 15000);

await Promise.all([
  ...workers.map(regularLoop),
  ...Array.from({ length: CHAIN_WORKERS }, (_, i) => chainLoop(i)),
]);
clearInterval(progress);

const full = chainOutcomes.filter((c) => c.depth === CHAIN_DEPTH).length;
const byDepth = {}; const byStop = {};
for (const c of chainOutcomes) {
  byDepth[c.depth] = (byDepth[c.depth] ?? 0) + 1;
  byStop[c.stop] = (byStop[c.stop] ?? 0) + 1;
}
const report = {
  sdk: 'evolution-sdk',
  regular: regular.sum(), chained: chained.sum(),
  chains: { attempted: chainOutcomes.length, fullDepth: full, byDepth, byStop },
};
console.log('\n=== result ===');
console.log(JSON.stringify(report, null, 2));
const fs = await import('node:fs');
fs.writeFileSync(process.env.REPORT ?? 'evolution-load-report.json', JSON.stringify(report, null, 2));
await sleep(100);
