// MeshJS + Yano: datum / redeemer compatibility using the cardano-foundation
// vesting template (Aiken PlutusV3).
//
//   datum  VestingDatum { lock_until: Int, owner: ByteArray, beneficiary: ByteArray }
//   spend  owner signs  OR  (beneficiary signs AND valid_after(lock_until))
//
// Three cases: owner clawback (succeeds), beneficiary after unlock (succeeds),
// beneficiary before unlock (must FAIL). The negative case is what proves the
// datum is really decoded and enforced rather than just accepted.
import {
  BlockfrostProvider, MeshTxBuilder, MeshWallet,
  mConStr0, resolvePaymentKeyHash, serializePlutusScript,
} from '@meshsdk/core';
import { applyParamsToScript } from '@meshsdk/core-csl';
import { readFileSync } from 'node:fs';
import { YANO_URL, fund, sleep, latestBlock } from './yano.mjs';

const NETWORK_ID = 0;
const BLUEPRINT = JSON.parse(readFileSync(
  new URL('../../shared/vesting-plutus.json', import.meta.url), 'utf8'));

const provider = () => new BlockfrostProvider(YANO_URL);
const results = [];

function loadValidator() {
  const compiled = BLUEPRINT.validators[0].compiledCode;
  const script = applyParamsToScript(compiled, [], 'JSON');
  const { address } = serializePlutusScript({ code: script, version: 'V3' }, undefined, NETWORK_ID);
  return { script, scriptAddress: address };
}

function newWallet() {
  const p = provider();
  return new MeshWallet({
    networkId: NETWORK_ID, fetcher: p, submitter: p,
    key: { type: 'mnemonic', words: MeshWallet.brew(false) },
  });
}

async function waitForTxOutput(txHash, index, timeoutSec = 60) {
  for (let i = 0; i < timeoutSec; i++) {
    try {
      const utxos = await provider().fetchUTxOs(txHash);
      const u = utxos.find((x) => x.input.outputIndex === index);
      if (u) return u;
    } catch { /* not yet indexed */ }
    await sleep(500);
  }
  throw new Error(`timed out waiting for ${txHash}#${index}`);
}

async function deposit(owner, ownerVkh, benVkh, lovelace, lockUntilMs) {
  const ownerAddr = await owner.getChangeAddress();
  const { scriptAddress } = loadValidator();
  const utxos = await provider().fetchAddressUTxOs(ownerAddr);
  const tx = new MeshTxBuilder({ fetcher: provider(), submitter: provider() });
  await tx
    .txOut(scriptAddress, [{ unit: 'lovelace', quantity: String(lovelace) }])
    .txOutInlineDatumValue(mConStr0([lockUntilMs, ownerVkh, benVkh]))
    .changeAddress(ownerAddr)
    .selectUtxosFrom(utxos)
    .complete();
  const signed = await owner.signTx(tx.txHex);
  return owner.submitTx(signed);
}

async function withdraw(wallet, utxo, { invalidBeforeSlot } = {}) {
  const myAddr = await wallet.getChangeAddress();
  const myVkh = resolvePaymentKeyHash(myAddr);
  const { script, scriptAddress } = loadValidator();
  const ownUtxos = await provider().fetchAddressUTxOs(myAddr);
  const collateral = await wallet.getCollateral();
  if (!collateral.length) throw new Error('wallet has no collateral utxo');

  const tx = new MeshTxBuilder({ fetcher: provider(), submitter: provider() });
  let b = tx
    .spendingPlutusScriptV3()
    .txIn(utxo.input.txHash, utxo.input.outputIndex, utxo.output.amount, scriptAddress)
    .txInScript(script)
    .txInRedeemerValue('')
    .txInInlineDatumPresent()
    .txInCollateral(
      collateral[0].input.txHash, collateral[0].input.outputIndex,
      collateral[0].output.amount, collateral[0].output.address)
    .requiredSignerHash(myVkh)
    .changeAddress(myAddr)
    .selectUtxosFrom(ownUtxos);
  if (invalidBeforeSlot !== undefined) b = b.invalidBefore(invalidBeforeSlot);
  await b.complete();
  const signed = await wallet.signTx(tx.txHex);
  return wallet.submitTx(signed);
}

const check = async (name, expectSuccess, fn) => {
  try {
    const out = await fn();
    const ok = expectSuccess;
    results.push({ name, ok, detail: String(out).slice(0, 24) });
    console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${name} -> accepted ${String(out).slice(0, 16)}...`
      + (expectSuccess ? '' : '  (EXPECTED REJECTION!)'));
  } catch (e) {
    const msg = String(e?.message ?? e).replace(/\s+/g, ' ').slice(0, 130);
    const ok = !expectSuccess;
    results.push({ name, ok, detail: msg });
    console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${name} -> rejected: ${msg}`);
  }
};

console.log(`=== MeshJS vesting contract (datum/redeemer) against ${YANO_URL} ===`);
const { scriptAddress } = loadValidator();
console.log(`script address : ${scriptAddress}`);
console.log(`validator      : ${BLUEPRINT.validators[0].title} (PlutusV3)\n`);

const owner = newWallet();
const beneficiary = newWallet();
const ownerAddr = await owner.getChangeAddress();
const benAddr = await beneficiary.getChangeAddress();
const ownerVkh = resolvePaymentKeyHash(ownerAddr);
const benVkh = resolvePaymentKeyHash(benAddr);

// Several distinct UTXOs each, so collateral selection and change always have room.
for (let i = 0; i < 4; i++) { await fund(ownerAddr, 1000); await fund(benAddr, 1000); }
await sleep(500);
console.log(`owner        ${ownerAddr.slice(0, 28)}...  vkh=${ownerVkh.slice(0, 12)}...`);
console.log(`beneficiary  ${benAddr.slice(0, 28)}...  vkh=${benVkh.slice(0, 12)}...\n`);

const tip = await latestBlock();
console.log(`tip slot=${tip.slot} time=${tip.time} => derived systemStart=${tip.time - tip.slot}\n`);

const FAR_FUTURE = Date.now() + 60 * 60 * 1000;
const WELL_PAST = Date.now() - 60 * 60 * 1000;

// 1. owner clawback: datum decoded, owner signature satisfies the first branch
console.log('--- case 1: owner clawback (lock still in the future) ---');
const dep1 = await deposit(owner, ownerVkh, benVkh, 5_000_000, FAR_FUTURE);
console.log(`  deposit tx ${dep1.slice(0, 16)}... lock_until=${FAR_FUTURE}`);
const utxo1 = await waitForTxOutput(dep1, 0);
await check('owner withdraws before unlock time (clawback branch)', true,
  () => withdraw(owner, utxo1));

// 2. beneficiary after unlock: needs signature AND validity range
console.log('\n--- case 2: beneficiary withdraw after unlock time ---');
const dep2 = await deposit(owner, ownerVkh, benVkh, 5_000_000, WELL_PAST);
console.log(`  deposit tx ${dep2.slice(0, 16)}... lock_until=${WELL_PAST} (already past)`);
const utxo2 = await waitForTxOutput(dep2, 0);
const tip2 = await latestBlock();
await check('beneficiary withdraws with invalidBefore after lock_until', true,
  () => withdraw(beneficiary, utxo2, { invalidBeforeSlot: tip2.slot }));

// 3. negative control: beneficiary before unlock must be rejected
console.log('\n--- case 3: beneficiary withdraw BEFORE unlock (must be rejected) ---');
const dep3 = await deposit(owner, ownerVkh, benVkh, 5_000_000, FAR_FUTURE);
console.log(`  deposit tx ${dep3.slice(0, 16)}... lock_until=${FAR_FUTURE}`);
const utxo3 = await waitForTxOutput(dep3, 0);
const tip3 = await latestBlock();
await check('beneficiary withdraw before unlock is rejected by the validator', false,
  () => withdraw(beneficiary, utxo3, { invalidBeforeSlot: tip3.slot }));

console.log('\n=== summary ===');
const passed = results.filter((r) => r.ok).length;
console.log(`${passed}/${results.length} vesting checks behaved as expected`);
for (const r of results.filter((x) => !x.ok)) console.log(`  UNEXPECTED: ${r.name} :: ${r.detail}`);
process.exit(passed === results.length ? 0 : 1);
