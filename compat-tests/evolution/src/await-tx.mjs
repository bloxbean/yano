// Isolates one known Evolution SDK <-> Yano gap: `lucid.awaitTx()`.
//
// The SDK's Blockfrost provider polls `${url}/txs/{hash}/cbor` to confirm a
// transaction. Yano does not implement that route, and unknown routes under
// /api/v1 fall through to Quarkus's default HTML 404 page, so the SDK's polling
// interval dies on `JSON.parse('<html>...')` - an unhandled SyntaxError rather
// than a catchable API error.
//
// Tracked as a known failure so it cannot mask regressions in the rest of the
// provider walk (see KNOWN-FAILS.md). Exit 0 means awaitTx worked, which would
// mean Yano gained the route - update KNOWN-FAILS.md.
import { Lucid, Blockfrost } from '@evolution-sdk/lucid';

const YANO_URL = process.env.YANO_URL ?? 'http://localhost:7070/api/v1';
const NETWORK = 'Preview';
const TIMEOUT_MS = Number(process.env.AWAIT_TX_TIMEOUT_MS ?? 45_000);

// The failure surfaces inside a setInterval callback, so it arrives as an
// unhandled rejection/exception rather than at our await point. Convert both
// into an ordinary non-zero exit with a one-line diagnosis.
const diagnose = (err) => {
  const msg = String(err?.message ?? err).replace(/\s+/g, ' ').slice(0, 200);
  console.log(`  FAIL  lucid.awaitTx() - ${msg}`);
  if (msg.includes('not valid JSON') || msg.includes("Unexpected token '<'")) {
    console.log('\nDIAGNOSIS: Yano answered an unknown /api/v1 route with an HTML 404.');
    console.log('A Blockfrost-compatible API should return JSON for every 404 on its own');
    console.log('prefix; a catch-all JSON error mapper would turn this hard crash into an');
    console.log('ordinary catchable error for every JS SDK. The missing route is');
    console.log('GET /txs/{hash}/cbor, which is what awaitTx() polls.');
  }
  console.log('\nRESULT: FAIL (expected - see KNOWN-FAILS.md)');
  process.exit(1);
};
process.on('unhandledRejection', diagnose);
process.on('uncaughtException', diagnose);

console.log(`=== Evolution awaitTx() probe against ${YANO_URL} ===\n`);

const lucid = await Lucid(new Blockfrost(YANO_URL, 'dummy-key'), NETWORK);
const seed = 'test test test test test test test test test test test test '
  + 'test test test test test test test test test test test sauce';
lucid.selectWallet.fromSeed(seed);
const address = await lucid.wallet().address();

const fundRes = await fetch(`${YANO_URL}/devnet/fund`, {
  method: 'POST', headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ address, ada: 5000 }),
});
if (!fundRes.ok) {
  console.log(`  FAIL  devnet faucet - ${fundRes.status}`);
  process.exit(2);
}

const receiverLucid = await Lucid(new Blockfrost(YANO_URL, 'dummy-key'), NETWORK);
receiverLucid.selectWallet.fromSeed(seed, { accountIndex: 1 });
const receiver = await receiverLucid.wallet().address();

const tx = await lucid.newTx().pay.ToAddress(receiver, { lovelace: 3_000_000n }).complete();
const txHash = await (await tx.sign.withWallet().complete()).submit();
console.log(`  submitted ${txHash}`);

const timeout = new Promise((_, rej) =>
  setTimeout(() => rej(new Error(`awaitTx did not resolve within ${TIMEOUT_MS} ms`)), TIMEOUT_MS));

try {
  await Promise.race([lucid.awaitTx(txHash, 2000), timeout]);
} catch (e) {
  diagnose(e);
}
console.log('  PASS  lucid.awaitTx() confirmed the transaction');
console.log('\nRESULT: PASS (unexpected - Yano now serves /txs/{hash}/cbor)');
process.exit(0);
