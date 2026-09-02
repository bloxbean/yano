// MeshJS <-> Yano compatibility probe.
//
// Walks the provider surface an ordinary Mesh dApp uses and reports, step by
// step, whether Yano satisfies it. Run behind shared/proxy.mjs to also capture
// the exact endpoint list.
import { BlockfrostProvider, MeshWallet, MeshTxBuilder } from '@meshsdk/core';
import { YANO_URL, fund, awaitCanonical, sleep } from './yano.mjs';

const results = [];
const step = async (name, fn) => {
  const t0 = Date.now();
  try {
    const detail = await fn();
    results.push({ name, ok: true, ms: Date.now() - t0, detail: detail ?? '' });
    console.log(`  PASS  ${name}${detail ? ' - ' + detail : ''}`);
    return detail;
  } catch (e) {
    const msg = String(e?.message ?? e).replace(/\s+/g, ' ').slice(0, 150);
    results.push({ name, ok: false, ms: Date.now() - t0, detail: msg });
    console.log(`  FAIL  ${name} - ${msg}`);
    return null;
  }
};

console.log(`=== MeshJS compatibility probe against ${YANO_URL} ===\n`);

const provider = new BlockfrostProvider(YANO_URL);

await step('provider.fetchProtocolParameters()', async () => {
  const pp = await provider.fetchProtocolParameters();
  if (!pp) throw new Error('empty protocol params');
  const missing = ['minFeeA', 'minFeeB', 'maxTxSize', 'coinsPerUtxoSize', 'priceMem', 'priceStep']
    .filter((k) => pp[k] === undefined || pp[k] === null);
  if (missing.length) throw new Error(`missing fields: ${missing.join(',')}`);
  return `minFeeA=${pp.minFeeA} maxTxSize=${pp.maxTxSize} coinsPerUtxoSize=${pp.coinsPerUtxoSize}`;
});

const wallet = new MeshWallet({
  networkId: 0,
  fetcher: provider,
  submitter: provider,
  key: { type: 'mnemonic', words: MeshWallet.brew(false) },
});
const address = await wallet.getChangeAddress();
console.log(`\n  wallet address: ${address.slice(0, 32)}...\n`);

await step('devnet faucet funds the Mesh wallet', async () => {
  const outpoint = await fund(address, 5000);
  return outpoint;
});

await step('provider.fetchAddressUTxOs()', async () => {
  const utxos = await provider.fetchAddressUTxOs(address);
  if (!utxos.length) throw new Error('no utxos returned for a funded address');
  const u = utxos[0];
  if (!u.input?.txHash || u.output?.amount == null) throw new Error('utxo shape mismatch');
  return `${utxos.length} utxo(s), first=${u.input.txHash.slice(0, 12)}...#${u.input.outputIndex}`;
});

const receiver = await new MeshWallet({
  networkId: 0, fetcher: provider, submitter: provider,
  key: { type: 'mnemonic', words: MeshWallet.brew(false) },
}).getChangeAddress();

const txHash = await step('build + sign + submit a simple payment', async () => {
  const utxos = await wallet.getUtxos();
  const txBuilder = new MeshTxBuilder({ fetcher: provider, submitter: provider, verbose: false });
  const unsigned = await txBuilder
    .txOut(receiver, [{ unit: 'lovelace', quantity: '3000000' }])
    .changeAddress(address)
    .selectUtxosFrom(utxos)
    .complete();
  const signed = await wallet.signTx(unsigned);
  return await wallet.submitTx(signed);
});

if (txHash) {
  await step('submitted payment reaches canonical state', async () => {
    const ms = await awaitCanonical(txHash, 0, 90_000);
    if (ms < 0) throw new Error('not confirmed within 90s');
    return `confirmed in ${ms} ms`;
  });

  await step('provider.fetchTxInfo() on the confirmed transaction', async () => {
    const info = await provider.fetchTxInfo(txHash);
    if (!info?.hash) throw new Error('no tx info');
    return `block=${info.block ?? '?'} fees=${info.fees ?? '?'}`;
  });
}

await step('provider.fetchUTxOs() by tx hash', async () => {
  if (!txHash) throw new Error('skipped, no submitted tx');
  const utxos = await provider.fetchUTxOs(txHash);
  if (!utxos?.length) throw new Error('no utxos for tx');
  return `${utxos.length} output(s)`;
});

console.log('\n=== summary ===');
const passed = results.filter((r) => r.ok).length;
console.log(`${passed}/${results.length} checks passed`);
for (const r of results.filter((x) => !x.ok)) console.log(`  FAILED: ${r.name} :: ${r.detail}`);

await sleep(200);
process.exit(results.every((r) => r.ok) ? 0 : 1);
