// MeshJS load test against Yano: independent payments plus parent->child chains
// submitted back-to-back without waiting for confirmation.
import { BlockfrostProvider, MeshTxBuilder, MeshWallet } from '@meshsdk/core';
import { YANO_URL, fund, Stats, categorize, sleep, mempoolCount, latestBlock } from './yano.mjs';

const WORKERS = Number(process.env.WORKERS ?? 4);
const UTXOS = Number(process.env.UTXOS_PER_WORKER ?? 12);
const CHAIN_WORKERS = Number(process.env.CHAIN_WORKERS ?? 2);
const CHAIN_DEPTH = Number(process.env.CHAIN_DEPTH ?? 5);
const DURATION_S = Number(process.env.DURATION_SECONDS ?? 60);
const PAY = 2_000_000;
const CHAIN_STEP = 3_000_000;

const provider = new BlockfrostProvider(YANO_URL);
const newWallet = () => new MeshWallet({
  networkId: 0, fetcher: provider, submitter: provider,
  key: { type: 'mnemonic', words: MeshWallet.brew(false) },
});

/** Build+sign+submit a payment consuming exactly the given utxo. */
async function pay(wallet, fromAddr, utxo, toAddr, lovelace) {
  const tx = new MeshTxBuilder({ fetcher: provider, submitter: provider });
  await tx
    .txIn(utxo.input.txHash, utxo.input.outputIndex, utxo.output.amount, utxo.output.address)
    .txOut(toAddr, [{ unit: 'lovelace', quantity: String(lovelace) }])
    .changeAddress(fromAddr)
    .complete();
  const signed = await wallet.signTx(tx.txHex);
  const hash = await wallet.submitTx(signed);
  return { hash, txHex: tx.txHex };
}

function utxoRef(txHash, index, address, lovelace) {
  return {
    input: { txHash, outputIndex: index },
    output: { address, amount: [{ unit: 'lovelace', quantity: String(lovelace) }] },
  };
}

console.log(`=== MeshJS load test against ${YANO_URL} ===`);
console.log(`workers=${WORKERS}x${UTXOS} utxos, chains=${CHAIN_WORKERS} depth=${CHAIN_DEPTH}, ${DURATION_S}s\n`);

// ---- setup ----
const workers = [];
for (let i = 0; i < WORKERS; i++) {
  const w = newWallet();
  const addr = await w.getChangeAddress();
  const sink = await newWallet().getChangeAddress();
  const pool = [];
  for (let k = 0; k < UTXOS; k++) {
    const [h, idx] = (await fund(addr, 200)).split('#');
    pool.push(utxoRef(h, Number(idx), addr, 200_000_000));
  }
  workers.push({ w, addr, sink, pool });
  console.log(`worker ${i} ${addr.slice(0, 26)}... ${pool.length} utxos`);
}
console.log();

const regular = new Stats('regular');
const chained = new Stats('chained');
const chainOutcomes = [];
const deadline = Date.now() + DURATION_S * 1000;

// ---- regular payment workers ----
async function regularLoop(worker) {
  while (Date.now() < deadline) {
    const utxo = worker.pool.pop();
    if (!utxo) {
      for (let k = 0; k < UTXOS; k++) {
        const [h, idx] = (await fund(worker.addr, 200)).split('#');
        worker.pool.push(utxoRef(h, Number(idx), worker.addr, 200_000_000));
      }
      continue;
    }
    const t0 = Date.now();
    try {
      await pay(worker.w, worker.addr, utxo, worker.sink, PAY);
      regular.record(true, 'ACCEPTED', Date.now() - t0);
    } catch (e) {
      regular.record(false, categorize(e), Date.now() - t0);
    }
  }
}

// ---- chain workers: parent -> child, no confirmation wait ----
async function chainLoop(id) {
  while (Date.now() < deadline) {
    // A fresh wallet per link, so each link pays a distinct address and the
    // output to spend next is unambiguous.
    const accts = [];
    for (let d = 0; d <= CHAIN_DEPTH; d++) accts.push(newWallet());
    const addrs = await Promise.all(accts.map((a) => a.getChangeAddress()));
    const [rootHash, rootIdx] = (await fund(addrs[0], 500)).split('#');
    let cur = utxoRef(rootHash, Number(rootIdx), addrs[0], 500_000_000);
    let curLovelace = 500_000_000;
    let depth = 0;
    let stop = 'COMPLETE';
    const t0 = Date.now();
    for (let d = 0; d < CHAIN_DEPTH; d++) {
      const amount = curLovelace - CHAIN_STEP;
      if (amount < 2_000_000) { stop = 'VALUE_EXHAUSTED'; break; }
      const start = Date.now();
      try {
        const { hash } = await pay(accts[d], addrs[d], cur, addrs[d + 1], amount);
        chained.record(true, 'ACCEPTED', Date.now() - start);
        depth += 1;
        cur = utxoRef(hash, 0, addrs[d + 1], amount);  // explicit txOut is index 0
        curLovelace = amount;
      } catch (e) {
        stop = categorize(e);
        chained.record(false, stop, Date.now() - start);
        break;
      }
    }
    chainOutcomes.push({ id, depth, stop, ms: Date.now() - t0 });
  }
}

const progress = setInterval(async () => {
  console.log(`  regular ${regular.accepted}/${regular.submitted}  chained ${chained.accepted}/${chained.submitted}`
    + `  chains=${chainOutcomes.length}  mempool=${await mempoolCount()}`);
}, 15000);

await Promise.all([
  ...workers.map(regularLoop),
  ...Array.from({ length: CHAIN_WORKERS }, (_, i) => chainLoop(i)),
]);
clearInterval(progress);

// ---- report ----
const full = chainOutcomes.filter((c) => c.depth === CHAIN_DEPTH).length;
const byDepth = {};
const byStop = {};
for (const c of chainOutcomes) {
  byDepth[c.depth] = (byDepth[c.depth] ?? 0) + 1;
  byStop[c.stop] = (byStop[c.stop] ?? 0) + 1;
}
const tip = await latestBlock();
const report = {
  sdk: 'meshjs',
  regular: regular.summary(),
  chained: chained.summary(),
  chains: { attempted: chainOutcomes.length, fullDepth: full, byDepth, byStop },
  tipHeight: tip.height,
};
console.log('\n=== result ===');
console.log(JSON.stringify(report, null, 2));
const fs = await import('node:fs');
fs.writeFileSync(process.env.REPORT ?? 'mesh-load-report.json', JSON.stringify(report, null, 2));
await sleep(100);
