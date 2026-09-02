// Evolution SDK + Yano: datum / redeemer compatibility using the
// cardano-foundation vesting template (Aiken PlutusV3).
//
// Uses inline datums and polls Yano's /utxos endpoint directly, avoiding
// awaitTx (/txs/{hash}/cbor) and /scripts/datum/{hash}, neither of which Yano
// implements.
import {
  Lucid, Blockfrost, Constr, Data, validatorToAddress, getAddressDetails,
} from '@evolution-sdk/lucid';
import { SLOT_CONFIG_NETWORK } from '@evolution-sdk/plutus';
import { readFileSync } from 'node:fs';

const YANO_URL = process.env.YANO_URL ?? 'http://localhost:7070/api/v1';
const NETWORK = 'Preview';
const BLUEPRINT = JSON.parse(readFileSync(
  new URL('../../shared/vesting-plutus.json', import.meta.url), 'utf8'));

const results = [];
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function fund(address, ada) {
  const res = await fetch(`${YANO_URL}/devnet/fund`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ address, ada }),
  });
  if (!res.ok) throw new Error(`fund ${res.status}`);
  const j = await res.json();
  return { txHash: j.tx_hash, index: j.index };
}

async function tip() {
  return (await fetch(`${YANO_URL}/blocks/latest`)).json();
}

/** Poll canonical UTXO state instead of Lucid's awaitTx. */
async function waitOutpoint(txHash, index, timeoutMs = 90_000) {
  const t0 = Date.now();
  while (Date.now() - t0 < timeoutMs) {
    const r = await fetch(`${YANO_URL}/utxos/${txHash}/${index}`);
    if (r.ok) return Date.now() - t0;
    await sleep(400);
  }
  throw new Error(`timed out waiting for ${txHash}#${index}`);
}

// Align Lucid's slot<->POSIX view with this devnet's genesis, otherwise
// validFrom() maps to a slot the validator reads as a different wall time.
async function alignSlotConfig() {
  const b = await tip();
  const zeroTime = (b.time - b.slot) * 1000;
  SLOT_CONFIG_NETWORK[NETWORK].zeroTime = zeroTime;
  SLOT_CONFIG_NETWORK[NETWORK].zeroSlot = 0;
  SLOT_CONFIG_NETWORK[NETWORK].slotLength = 1000;
  return { zeroTime, tipSlot: b.slot };
}

const validator = {
  type: 'PlutusV3',
  script: BLUEPRINT.validators[0].compiledCode,
};
const scriptAddress = validatorToAddress(NETWORK, validator);

async function lucidFor(seedAccountIndex) {
  const l = await Lucid(new Blockfrost(YANO_URL, 'dummy-key'), NETWORK);
  const seed = 'test test test test test test test test test test test test '
    + 'test test test test test test test test test test test sauce';
  l.selectWallet.fromSeed(seed, { accountIndex: seedAccountIndex });
  return l;
}

const check = async (name, expectSuccess, fn) => {
  try {
    const out = await fn();
    const ok = expectSuccess;
    results.push({ name, ok });
    console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${name} -> accepted ${String(out).slice(0, 16)}...`
      + (expectSuccess ? '' : '  (EXPECTED REJECTION!)'));
  } catch (e) {
    const msg = String(e?.message ?? e).replace(/\s+/g, ' ').slice(0, 130);
    const ok = !expectSuccess;
    results.push({ name, ok });
    console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${name} -> rejected: ${msg}`);
  }
};

console.log(`=== Evolution SDK vesting contract (datum/redeemer) against ${YANO_URL} ===`);
const align = await alignSlotConfig();
console.log(`script address : ${scriptAddress}`);
console.log(`slot config    : zeroTime=${align.zeroTime} tipSlot=${align.tipSlot}\n`);

const owner = await lucidFor(0);
const beneficiary = await lucidFor(1);
const ownerAddr = await owner.wallet().address();
const benAddr = await beneficiary.wallet().address();
const ownerVkh = getAddressDetails(ownerAddr).paymentCredential.hash;
const benVkh = getAddressDetails(benAddr).paymentCredential.hash;

for (let i = 0; i < 3; i++) { await fund(ownerAddr, 1000); await fund(benAddr, 1000); }
await sleep(400);
console.log(`owner        ${ownerAddr.slice(0, 28)}...  vkh=${ownerVkh.slice(0, 12)}...`);
console.log(`beneficiary  ${benAddr.slice(0, 28)}...  vkh=${benVkh.slice(0, 12)}...\n`);

async function deposit(lockUntilMs) {
  const datum = Data.to(new Constr(0, [BigInt(lockUntilMs), ownerVkh, benVkh]));
  const tx = await owner.newTx()
    .pay.ToContract(scriptAddress, { kind: 'inline', value: datum }, { lovelace: 5_000_000n })
    .complete();
  const signed = await tx.sign.withWallet().complete();
  const hash = await signed.submit();
  await waitOutpoint(hash, 0);
  return hash;
}

async function withdraw(l, addr, depositHash, { validFromMs } = {}) {
  const [utxo] = await l.utxosByOutRef([{ txHash: depositHash, outputIndex: 0 }]);
  if (!utxo) throw new Error('deposit utxo not found');
  let b = l.newTx()
    .collectFrom([utxo], Data.to(new Constr(0, [])))
    .attach.SpendingValidator(validator)
    .addSigner(addr);
  if (validFromMs !== undefined) b = b.validFrom(validFromMs);
  const tx = await b.complete();
  const signed = await tx.sign.withWallet().complete();
  return signed.submit();
}

const FAR_FUTURE = Date.now() + 60 * 60 * 1000;
const WELL_PAST = Date.now() - 60 * 60 * 1000;

console.log('--- case 1: owner clawback (lock still in the future) ---');
const d1 = await deposit(FAR_FUTURE);
console.log(`  deposit ${d1.slice(0, 16)}... lock_until=${FAR_FUTURE}`);
await check('owner withdraws before unlock time (clawback branch)', true,
  () => withdraw(owner, ownerAddr, d1));

console.log('\n--- case 2: beneficiary withdraw after unlock time ---');
const d2 = await deposit(WELL_PAST);
console.log(`  deposit ${d2.slice(0, 16)}... lock_until=${WELL_PAST} (already past)`);
await check('beneficiary withdraws with validFrom after lock_until', true,
  () => withdraw(beneficiary, benAddr, d2, { validFromMs: Date.now() - 60_000 }));

console.log('\n--- case 3: beneficiary withdraw BEFORE unlock (must be rejected) ---');
const d3 = await deposit(FAR_FUTURE);
console.log(`  deposit ${d3.slice(0, 16)}... lock_until=${FAR_FUTURE}`);
await check('beneficiary withdraw before unlock is rejected by the validator', false,
  () => withdraw(beneficiary, benAddr, d3, { validFromMs: Date.now() - 60_000 }));

console.log('\n=== summary ===');
const passed = results.filter((r) => r.ok).length;
console.log(`${passed}/${results.length} vesting checks behaved as expected`);
process.exit(passed === results.length ? 0 : 1);
