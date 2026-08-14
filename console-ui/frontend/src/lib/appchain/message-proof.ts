const HEX_32 = /^[0-9a-f]{64}$/;
const MASK_64 = (1n << 64n) - 1n;
const IV = [
  0x6a09e667f3bcc908n, 0xbb67ae8584caa73bn, 0x3c6ef372fe94f82bn, 0xa54ff53a5f1d36f1n,
  0x510e527fade682d1n, 0x9b05688c2b3e6c1fn, 0x1f83d9abfb41bd6bn, 0x5be0cd19137e2179n
];
const SIGMA = [
  [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15],
  [14, 10, 4, 8, 9, 15, 13, 6, 1, 12, 0, 2, 11, 7, 5, 3],
  [11, 8, 12, 0, 5, 2, 15, 13, 10, 14, 3, 6, 7, 1, 9, 4],
  [7, 9, 3, 1, 13, 12, 11, 14, 2, 6, 5, 10, 4, 0, 15, 8],
  [9, 0, 5, 7, 2, 4, 10, 15, 14, 1, 11, 12, 6, 8, 3, 13],
  [2, 12, 6, 10, 0, 11, 8, 3, 4, 13, 7, 5, 15, 14, 1, 9],
  [12, 5, 1, 15, 14, 13, 4, 10, 0, 7, 6, 3, 9, 2, 8, 11],
  [13, 11, 7, 14, 12, 1, 3, 9, 5, 0, 15, 4, 8, 6, 2, 10],
  [6, 15, 14, 9, 11, 3, 0, 8, 12, 2, 13, 7, 1, 4, 10, 5],
  [10, 2, 8, 4, 7, 6, 1, 5, 15, 11, 9, 14, 3, 12, 13, 0],
  [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15],
  [14, 10, 4, 8, 9, 15, 13, 6, 1, 12, 0, 2, 11, 7, 5, 3]
];

export interface BrowserMessageInclusionProof {
  schemaVersion: number;
  treeId: string;
  chainId: string;
  blockHeight: number;
  blockHash: string;
  messagesRoot: string;
  messageId: string;
  messageIndex: number;
  leafCount: number;
  siblings: string[];
}

export interface FinalizedBlockMessageRecord {
  schemaVersion: 1;
  height: number;
  messagesRoot: string;
  messageCount: number;
}

/** Strict canonical CBOR decoder for [1, height, bytes32 messagesRoot, count]. */
export function decodeFinalizedBlockMessageRecord(valueHex: string): FinalizedBlockMessageRecord {
  if (!/^[0-9a-f]+$/.test(valueHex) || (valueHex.length & 1) !== 0 || valueHex.length > 256) {
    throw new Error('Block-message record must be bounded canonical lowercase hex');
  }
  const bytes = fromHex(valueHex);
  let offset = 0;
  if (bytes[offset++] !== 0x84) throw new Error('Block-message record must be a four-field array');
  const version = readUnsigned(bytes, () => offset, (next) => { offset = next; });
  const height = readUnsigned(bytes, () => offset, (next) => { offset = next; });
  if (bytes[offset++] !== 0x58 || bytes[offset++] !== 0x20 || offset + 32 > bytes.length) {
    throw new Error('Block-message root must be a canonical 32-byte string');
  }
  const messagesRoot = toHex(bytes.subarray(offset, offset + 32));
  offset += 32;
  const messageCount = readUnsigned(bytes, () => offset, (next) => { offset = next; });
  if (offset !== bytes.length || version !== 1 || height < 1 || messageCount > 10_000) {
    throw new Error('Invalid finalized block-message record');
  }
  return { schemaVersion: 1, height, messagesRoot, messageCount };
}

export function verifyMessageAgainstBlockRecord(
  proof: BrowserMessageInclusionProof,
  record: FinalizedBlockMessageRecord
): boolean {
  return record.height === proof.blockHeight && record.messagesRoot === proof.messagesRoot
    && record.messageCount === proof.leafCount && verifyMessageInclusionProof(proof);
}

/** Strict release-matched browser verification of the ADR-037 compact path. */
export function verifyMessageInclusionProof(proof: BrowserMessageInclusionProof): boolean {
  if (proof.schemaVersion !== 1 || proof.treeId !== 'binary-merkle-blake2b256-v1'
    || !proof.chainId || proof.chainId.length > 128 || !Number.isSafeInteger(proof.blockHeight)
    || proof.blockHeight < 1 || !HEX_32.test(proof.blockHash) || !HEX_32.test(proof.messagesRoot)
    || !HEX_32.test(proof.messageId) || !Number.isSafeInteger(proof.leafCount)
    || proof.leafCount < 1 || proof.leafCount > 10_000
    || !Number.isSafeInteger(proof.messageIndex) || proof.messageIndex < 0
    || proof.messageIndex >= proof.leafCount || !Array.isArray(proof.siblings)
    || proof.siblings.length !== pathLength(proof.leafCount)
    || proof.siblings.some((sibling) => !HEX_32.test(sibling))) return false;
  let node = fromHex(proof.messageId);
  let index = proof.messageIndex;
  let width = proof.leafCount;
  for (const encodedSibling of proof.siblings) {
    const sibling = fromHex(encodedSibling);
    if ((width & 1) === 1 && index === width - 1 && !equal(node, sibling)) return false;
    node = (index & 1) === 0 ? parent(node, sibling) : parent(sibling, node);
    index = Math.floor(index / 2);
    width = Math.floor((width + 1) / 2);
  }
  return width === 1 && toHex(node) === proof.messagesRoot;
}

function parent(left: Uint8Array, right: Uint8Array): Uint8Array {
  const input = new Uint8Array(64);
  input.set(left);
  input.set(right, 32);
  return blake2b256(input);
}

/** Small dependency-free BLAKE2b-256 used only for bounded proof paths. */
export function blake2b256(input: Uint8Array): Uint8Array {
  const h = IV.slice();
  h[0] ^= 0x01010020n;
  let offset = 0;
  let counter = 0n;
  do {
    const remaining = input.length - offset;
    const length = Math.min(128, Math.max(0, remaining));
    const block = new Uint8Array(128);
    block.set(input.subarray(offset, offset + length));
    counter += BigInt(length);
    offset += length;
    compress(h, block, counter, offset >= input.length);
  } while (offset < input.length);
  const output = new Uint8Array(32);
  for (let word = 0; word < 4; word++) write64(output, word * 8, h[word]);
  return output;
}

function compress(h: bigint[], block: Uint8Array, counter: bigint, last: boolean): void {
  const m = Array.from({ length: 16 }, (_, index) => read64(block, index * 8));
  const v = [...h, ...IV];
  v[12] ^= counter & MASK_64;
  v[13] ^= counter >> 64n;
  if (last) v[14] ^= MASK_64;
  for (let round = 0; round < 12; round++) {
    const s = SIGMA[round];
    mix(v, 0, 4, 8, 12, m[s[0]], m[s[1]]);
    mix(v, 1, 5, 9, 13, m[s[2]], m[s[3]]);
    mix(v, 2, 6, 10, 14, m[s[4]], m[s[5]]);
    mix(v, 3, 7, 11, 15, m[s[6]], m[s[7]]);
    mix(v, 0, 5, 10, 15, m[s[8]], m[s[9]]);
    mix(v, 1, 6, 11, 12, m[s[10]], m[s[11]]);
    mix(v, 2, 7, 8, 13, m[s[12]], m[s[13]]);
    mix(v, 3, 4, 9, 14, m[s[14]], m[s[15]]);
  }
  for (let index = 0; index < 8; index++) h[index] = h[index] ^ v[index] ^ v[index + 8];
}

function mix(v: bigint[], a: number, b: number, c: number, d: number, x: bigint, y: bigint): void {
  v[a] = (v[a] + v[b] + x) & MASK_64;
  v[d] = rotate(v[d] ^ v[a], 32n);
  v[c] = (v[c] + v[d]) & MASK_64;
  v[b] = rotate(v[b] ^ v[c], 24n);
  v[a] = (v[a] + v[b] + y) & MASK_64;
  v[d] = rotate(v[d] ^ v[a], 16n);
  v[c] = (v[c] + v[d]) & MASK_64;
  v[b] = rotate(v[b] ^ v[c], 63n);
}

function rotate(value: bigint, bits: bigint): bigint {
  return ((value >> bits) | (value << (64n - bits))) & MASK_64;
}

function read64(value: Uint8Array, offset: number): bigint {
  let result = 0n;
  for (let index = 7; index >= 0; index--) result = (result << 8n) | BigInt(value[offset + index]);
  return result;
}

function write64(target: Uint8Array, offset: number, value: bigint): void {
  for (let index = 0; index < 8; index++) {
    target[offset + index] = Number(value & 0xffn);
    value >>= 8n;
  }
}

function pathLength(leafCount: number): number {
  let result = 0;
  for (let width = leafCount; width > 1; width = Math.floor((width + 1) / 2)) result++;
  return result;
}

function readUnsigned(
  bytes: Uint8Array,
  getOffset: () => number,
  setOffset: (offset: number) => void
): number {
  let offset = getOffset();
  if (offset >= bytes.length) throw new Error('Truncated block-message record');
  const head = bytes[offset++];
  if ((head >> 5) !== 0) throw new Error('Block-message integer has the wrong CBOR type');
  const additional = head & 31;
  let value: bigint;
  if (additional < 24) value = BigInt(additional);
  else {
    const width = additional === 24 ? 1 : additional === 25 ? 2
      : additional === 26 ? 4 : additional === 27 ? 8 : 0;
    if (width === 0 || offset + width > bytes.length) throw new Error('Invalid block-message integer');
    value = 0n;
    for (let index = 0; index < width; index++) value = (value << 8n) | BigInt(bytes[offset++]);
    const minimum = width === 1 ? 24n : 1n << BigInt((width / 2) * 8);
    if (value < minimum) throw new Error('Non-canonical block-message integer');
  }
  if (value > BigInt(Number.MAX_SAFE_INTEGER)) throw new Error('Block-message integer exceeds browser bounds');
  setOffset(offset);
  return Number(value);
}

function fromHex(value: string): Uint8Array {
  return Uint8Array.from({ length: value.length / 2 },
    (_, index) => Number.parseInt(value.slice(index * 2, index * 2 + 2), 16));
}

function toHex(value: Uint8Array): string {
  return Array.from(value, (item) => item.toString(16).padStart(2, '0')).join('');
}

function equal(left: Uint8Array, right: Uint8Array): boolean {
  return left.length === right.length && left.every((value, index) => value === right[index]);
}
