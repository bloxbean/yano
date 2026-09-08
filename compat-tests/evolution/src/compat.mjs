// Evolution SDK (Lucid) <-> Yano compatibility probe.
import { Lucid, Blockfrost } from '@evolution-sdk/lucid';

const YANO_URL = process.env.YANO_URL ?? 'http://localhost:7070/api/v1';
const NETWORK = 'Preview';

const results = [];
const step = async (name, fn) => {
  const t0 = Date.now();
  try {
    const detail = await fn();
    results.push({ name, ok: true, detail: detail ?? '' });
    console.log(`  PASS  ${name}${detail ? ' - ' + detail : ''}`);
    return detail;
  } catch (e) {
    const msg = String(e?.message ?? e).replace(/\s+/g, ' ').slice(0, 160);
    results.push({ name, ok: false, detail: msg });
    console.log(`  FAIL  ${name} - ${msg}`);
    return null;
  }
};

async function fund(address, ada) {
  const res = await fetch(`${YANO_URL}/devnet/fund`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ address, ada }),
  });
  if (!res.ok) throw new Error(`fund ${res.status}`);
  const j = await res.json();
  return `${j.tx_hash}#${j.index}`;
}

console.log(`=== Evolution SDK compatibility probe against ${YANO_URL} ===\n`);

const lucid = await step('Lucid(Blockfrost(yano), network) initialises', async () => {
  const l = await Lucid(new Blockfrost(YANO_URL, 'dummy-key'), NETWORK);
  return 'ok';
}).then(async () => Lucid(new Blockfrost(YANO_URL, 'dummy-key'), NETWORK));

await step('protocol parameters parse (incl. cost models)', async () => {
  const pp = lucid.config().protocolParameters;
  if (!pp) throw new Error('no protocol parameters on config');
  const cm = pp.costModels;
  const langs = cm ? Object.keys(cm) : [];
  if (!langs.length) throw new Error('no cost models parsed');
  return `minFeeA=${pp.minFeeA} maxTxSize=${pp.maxTxSize} costModels=[${langs.join(',')}]`;
});

const seed = 'test test test test test test test test test test test test '
  + 'test test test test test test test test test test test sauce';
lucid.selectWallet.fromSeed(seed);
const address = await lucid.wallet().address();
console.log(`\n  wallet address: ${address.slice(0, 32)}...\n`);

await step('devnet faucet funds the Lucid wallet', () => fund(address, 5000));

await step('lucid.utxosAt(address)', async () => {
  const utxos = await lucid.utxosAt(address);
  if (!utxos.length) throw new Error('no utxos for a funded address');
  return `${utxos.length} utxo(s), first=${utxos[0].txHash.slice(0, 12)}...#${utxos[0].outputIndex}`;
});

const receiverLucid = await Lucid(new Blockfrost(YANO_URL, 'dummy-key'), NETWORK);
receiverLucid.selectWallet.fromSeed(seed, { accountIndex: 1 });
const receiver = await receiverLucid.wallet().address();

const txHash = await step('build + sign + submit a simple payment', async () => {
  const tx = await lucid.newTx().pay.ToAddress(receiver, { lovelace: 3_000_000n }).complete();
  const signed = await tx.sign.withWallet().complete();
  return signed.submit();
});

if (txHash) {
  // NOT lucid.awaitTx(): that polls /txs/{hash}/cbor, which Yano does not
  // implement, and the HTML 404 hard-crashes the SDK's polling interval - see
  // the `evolution:awaitTx` row in KNOWN-FAILS.md, which tracks that gap on its
  // own so it cannot mask a regression in any of the steps above. What matters
  // for compatibility here is that the payment really reached the chain, so poll
  // canonical state directly.
  await step('submitted payment reaches canonical state', async () => {
    const deadline = Date.now() + 90_000;
    while (Date.now() < deadline) {
      const r = await fetch(`${YANO_URL}/utxos/${txHash}/0`);
      if (r.ok) return `confirmed in ${90_000 - (deadline - Date.now())} ms`;
      await new Promise((res) => setTimeout(res, 300));
    }
    throw new Error('not confirmed within 90s');
  });
}

console.log('\n=== summary ===');
console.log(`${results.filter((r) => r.ok).length}/${results.length} checks passed`);
for (const r of results.filter((x) => !x.ok)) console.log(`  FAILED: ${r.name} :: ${r.detail}`);
process.exit(results.every((r) => r.ok) ? 0 : 1);
