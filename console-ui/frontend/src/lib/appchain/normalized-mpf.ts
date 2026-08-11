import { blake2b256 } from './message-proof';

export interface NormalizedMpfProof {
  stateRoot: string;
  key: string;
  value: string;
  leafSuffix: string;
  folds: Array<{ cursor: number; prefix: string; nibble: number;
    neighbors: string[]; branchValueHash: string }>;
  committedHeight: number;
}

/** Independently verifies the bounded normalized MPF JSON emitted for Cardano. */
export function verifyNormalizedMpf(proof: NormalizedMpfProof): boolean {
  try {
    const root = bytes(proof.stateRoot, 32, 32);
    const key = bytes(proof.key, 1, 256);
    const value = bytes(proof.value, 1, 8 * 1024);
    const encodedSuffix = bytes(proof.leafSuffix, 1, 33);
    if (!Number.isSafeInteger(proof.committedHeight) || proof.committedHeight < 0
      || !Array.isArray(proof.folds) || proof.folds.length > 32) return false;
    const path = nibbles(blake2b256(key));
    const suffix = decodeSuffix(encodedSuffix);
    let cursorEnd = 64 - suffix.length;
    if (!equal(suffix, path.subarray(cursorEnd))) return false;
    let child = hash(encodedSuffix, blake2b256(value));
    for (const fold of proof.folds) {
      const prefix = bytes(fold.prefix, 0, 64);
      const neighbors = fold.neighbors.map((item) => bytes(item, 32, 32));
      const branchValue = bytes(fold.branchValueHash, 0, 32);
      if (!Number.isSafeInteger(fold.cursor) || fold.cursor < 0 || fold.cursor >= 64
        || !Number.isSafeInteger(fold.nibble) || fold.nibble < 0 || fold.nibble > 15
        || neighbors.length !== 4 || (branchValue.length !== 0 && branchValue.length !== 32)
        || fold.cursor + prefix.length + 1 !== cursorEnd
        || !equal(prefix, path.subarray(fold.cursor, fold.cursor + prefix.length))
        || path[fold.cursor + prefix.length] !== fold.nibble) return false;
      let merkle = aggregate(fold.nibble, child, neighbors);
      if (branchValue.length === 32) merkle = hash(merkle, hash(new Uint8Array([255]), branchValue));
      child = hash(prefix, merkle);
      cursorEnd = fold.cursor;
    }
    return cursorEnd === 0 && equal(root, child);
  } catch { return false; }
}

function decodeSuffix(encoded: Uint8Array): Uint8Array {
  if (encoded[0] !== 0 && encoded[0] !== 255) throw new Error('invalid suffix');
  const odd = encoded[0] === 0;
  if (odd && (encoded.length < 2 || encoded[1] > 15)) throw new Error('invalid suffix');
  const result = new Uint8Array(odd ? 1 + (encoded.length - 2) * 2 : (encoded.length - 1) * 2);
  let target = 0;
  if (odd) result[target++] = encoded[1];
  for (let index = odd ? 2 : 1; index < encoded.length; index++) {
    result[target++] = encoded[index] >>> 4;
    result[target++] = encoded[index] & 15;
  }
  return result;
}

function aggregate(nibble: number, me: Uint8Array, n: Uint8Array[]): Uint8Array {
  const [n1, n2, n3, n4] = n;
  switch (nibble) {
    case 0: return hash(hash(hash(hash(me, n4), n3), n2), n1);
    case 1: return hash(hash(hash(hash(n4, me), n3), n2), n1);
    case 2: return hash(hash(hash(n3, hash(me, n4)), n2), n1);
    case 3: return hash(hash(hash(n3, hash(n4, me)), n2), n1);
    case 4: return hash(hash(n2, hash(hash(me, n4), n3)), n1);
    case 5: return hash(hash(n2, hash(hash(n4, me), n3)), n1);
    case 6: return hash(hash(n2, hash(n3, hash(me, n4))), n1);
    case 7: return hash(hash(n2, hash(n3, hash(n4, me))), n1);
    case 8: return hash(n1, hash(hash(hash(me, n4), n3), n2));
    case 9: return hash(n1, hash(hash(hash(n4, me), n3), n2));
    case 10: return hash(n1, hash(hash(n3, hash(me, n4)), n2));
    case 11: return hash(n1, hash(hash(n3, hash(n4, me)), n2));
    case 12: return hash(n1, hash(n2, hash(hash(me, n4), n3)));
    case 13: return hash(n1, hash(n2, hash(hash(n4, me), n3)));
    case 14: return hash(n1, hash(n2, hash(n3, hash(me, n4))));
    case 15: return hash(n1, hash(n2, hash(n3, hash(n4, me))));
    default: throw new Error('invalid nibble');
  }
}

function nibbles(value: Uint8Array): Uint8Array {
  const result = new Uint8Array(value.length * 2);
  value.forEach((item, index) => { result[index * 2] = item >>> 4; result[index * 2 + 1] = item & 15; });
  return result;
}
function hash(...values: Uint8Array[]): Uint8Array {
  const size = values.reduce((total, value) => total + value.length, 0);
  const input = new Uint8Array(size); let offset = 0;
  for (const value of values) { input.set(value, offset); offset += value.length; }
  return blake2b256(input);
}
function bytes(value: string, minimum: number, maximum: number): Uint8Array {
  if (typeof value !== 'string') throw new Error('invalid byte string');
  const decoded = Uint8Array.from(atob(value), (item) => item.charCodeAt(0));
  if (decoded.length < minimum || decoded.length > maximum) throw new Error('invalid byte length');
  return decoded;
}
function equal(left: Uint8Array, right: Uint8Array): boolean {
  return left.length === right.length && left.every((item, index) => item === right[index]);
}
