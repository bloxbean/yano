import fs from 'node:fs';
import vm from 'node:vm';

const source = fs.readFileSync(process.argv[2], 'utf8');
vm.runInThisContext(source, { filename: 'mpf-verifier.js' });
const api = globalThis.CardanoHistoryMpf;
const toHex = bytes => Buffer.from(bytes).toString('hex');
const fail = message => { throw new Error(message); };

const path = toHex(api.blake2b256(Uint8Array.from([1, 2, 3])));
if (path !== '11c0e79b71c3976ccd0c02d1310e2516c08edc9d8b6f57ccd680d63a4d8e72da') {
  fail('browser Blake2b-256 disagrees with the Java/on-chain vector');
}
const fact = {
  chainId: 'history', committedHeight: 9, stateRoot:
    'f9975d86941bdad5438d71795de9383e9030db2e6c4ec8401167eba2a80c7fe8',
  profile: 'mpf-blake2b256-v1', presence: 'PRESENT', key: '010203',
  valueHex: '040506', proofWireHex: '80'
};
if (!api.verifyInclusion(fact)) fail('valid single-leaf MPF proof was rejected');
const bundle = { schema: 'cardano-history-browser-proof-v1', chainId: 'history',
  committedHeight: 9, stateRoot: fact.stateRoot, factKey: fact.key,
  requiresCompleteness: false, history: { canonicalValueHex: fact.valueHex }, fact };
if (!api.verifyBundle(bundle).valid) fail('valid typed browser bundle was rejected');
if (api.verifyBundle({ ...bundle, stateRoot: '00'.repeat(32) }).valid) {
  fail('wrong selected root was accepted');
}
if (api.verifyInclusion({ ...fact, valueHex: '040507' })) fail('wrong value was accepted');
const semanticBundle = { fact: { valueHex: '850002020102' }, requiresCompleteness: true,
  completeness: { valueHex: '86011818005820' + '00'.repeat(32) + '0001' },
  history: { dataset: 'proposal-history', datasetEpoch: 24, complete: true,
    canonicalValueHex: '850002020102', actionType: 'PARAMETER_CHANGE', status: 'ENACTED',
    reason: 'ENACTED', proposedEpoch: 1, expiresAfterEpoch: 2 } };
if (!api.verifyHistorySemantics(semanticBundle)) fail('valid proposal/completeness semantics rejected');
if (api.verifyHistorySemantics({ ...semanticBundle,
  history: { ...semanticBundle.history, status: 'ACTIVE' } })) fail('wrong proposal status accepted');
console.log('Cardano History browser MPF golden vectors: PASS');
