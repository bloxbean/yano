import fs from 'node:fs';
import vm from 'node:vm';

vm.runInThisContext(fs.readFileSync(process.argv[2], 'utf8'), { filename: 'mpf-verifier.js' });
const api = globalThis.CardanoHistoryMpf;
const textHex = value => Buffer.from(value, 'ascii').toString('hex');
const fail = message => { throw new Error(message); };

const path = Buffer.from(api.blake2b256(Uint8Array.from([1, 2, 3]))).toString('hex');
if (path !== '11c0e79b71c3976ccd0c02d1310e2516c08edc9d8b6f57ccd680d63a4d8e72da') {
  fail('browser Blake2b-256 disagrees with the Java/on-chain vector');
}

const root = '6f998ef5eb2e02fbef005e75a583f0496c11570db2c01512a32ddc38cff8d41e';
const branch = { chainId: 'history', committedHeight: 42, stateRoot: root,
  profile: 'mpf-blake2b256-v1', presence: 'PRESENT', key: textHex('claim-key-18'),
  valueHex: textHex('claim-value-18'),
  proofWireHex: '81d87982005880ce334a65142e8f1659f6154c4cb5a949441bd17422afd08293dcf03245fe606cdbcb1473dba748198a02c579b70b95d2219e7e45e1ba398617265d92e30f0e88b41ea223022b6c39b6211352ff9d7415de173f3a7b44c3994325be0ea9ae94b24ec8733e7b12e711b957ad2594de817bde643ff7cb0f61ab8bccefe4c5556ac8' };
if (!api.verifyProof(branch)) fail('real Java MPF branch vector was rejected');
if (api.verifyProof({ ...branch, valueHex: textHex('wrong') })) fail('wrong branch value accepted');

const absent = { chainId: 'history', committedHeight: 42, stateRoot: root,
  profile: 'mpf-blake2b256-v1', presence: 'ABSENT', key: textHex('claim-key-240'),
  proofWireHex: '82d87982005880ce334a65142e8f1659f6154c4cb5a949441bd17422afd08293dcf03245fe606cc7c4105aceabcfe5f460b45d8468ce4dd0ea8f303a97bf1154ac037639a8745d19813fb7e64fe6a4e2928c86f45f6d7103b8ddc44167e019bd53c4b14fe1f9e6f475ade75f97a0efa50285d08aa72324b795fa180f6f252a8dea9f3f61faff0ed87982005880c147da10eae6ae19ecddb4c2593e13d0bf250775821bef806a44e6a107213e34789586f26046c504d0a6a3d7715ea385929e54edd49621afbdb6071029c4cba6bc0ffeeb918767330d68b9a62bb5dfe79b45588b93724f9e58fc7712a69b56450000000000000000000000000000000000000000000000000000000000000000' };
if (!api.verifyProof(absent)) fail('real Java MPF absence vector was rejected');
if (api.verifyProof({ ...absent, stateRoot: '00'.repeat(32) })) fail('wrong absence root accepted');

const typedKey = '79616e6f2d636f6d706f736974652d73746174652d763100126c312d65706f63682d706172616d732d76310009706172616d732f3234';
const typedFact = { chainId: 'history', committedHeight: 9,
  stateRoot: '9e0340accebd39a08017d8128b252dc97532f52db6e772f8c2e951d7609d936f',
  profile: 'mpf-blake2b256-v1', presence: 'PRESENT', key: typedKey,
  valueHex: '0102', proofWireHex: '80' };
const bundle = { schema: 'cardano-history-browser-proof-v1', kind: 'primary',
  chainId: 'history', committedHeight: 9, stateRoot: typedFact.stateRoot,
  subject: { kind: 'parameters', epoch: 24 }, predicate: { kind: 'fact' },
  history: { dataset: 'protocol-parameters', canonicalValueHex: '0102' }, fact: typedFact };
if (!api.verifyBundle(bundle).valid) fail('canonical typed browser bundle was rejected');
if (api.verifyBundle({ ...bundle, subject: { kind: 'parameters', epoch: 25 } }).valid) {
  fail('self-asserted relabelled epoch was accepted');
}
if (api.verifyBundle({ ...bundle, stateRoot: '00'.repeat(32) }).valid) fail('wrong selected root accepted');
try { api.verifyProof({ ...branch, proofWireHex: branch.proofWireHex + '00' }); fail('trailing CBOR accepted'); }
catch (error) { if (error.message === 'trailing CBOR accepted') throw error; }
console.log('Cardano History browser MPF golden vectors: PASS');
