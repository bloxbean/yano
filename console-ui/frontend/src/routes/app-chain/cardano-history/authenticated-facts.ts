type JsonRecord = Record<string, unknown>;

const PARAMETER_TYPES = [
  'unsigned-integer', 'signed-integer', 'lovelace', 'rational', 'bytes',
  'protocol-version', 'structured'
];

/** Decode the value carried by the verified proof; never trust the editable JSON summary. */
export function authenticatedStake(history: JsonRecord, valueHex: string | null): JsonRecord {
  if (!valueHex) return { ...history, found: false, coin: null, poolHash: null };
  const value = decodeHex(valueHex);
  if (!Array.isArray(value) || value.length !== 2 || typeof value[0] !== 'bigint'
    || typeof value[1] !== 'string' || !/^[0-9a-f]{56}$/.test(value[1])) {
    throw new Error('Verified proof contains an invalid stake value');
  }
  return { ...history, found: true, coin: value[0].toString(), poolHash: value[1] };
}

/** Decode one [version, epoch, field-id, type, value] parameter leaf from the verified proof. */
export function authenticatedParameter(history: JsonRecord, valueHex: string | null): JsonRecord {
  if (!valueHex) return { ...history, found: false, value: null };
  const leaf = decodeHex(valueHex);
  if (!Array.isArray(leaf) || leaf.length !== 5 || leaf[0] !== 1n
    || typeof leaf[1] !== 'bigint' || typeof leaf[2] !== 'string'
    || typeof leaf[3] !== 'bigint') {
    throw new Error('Verified proof contains an invalid protocol-parameter leaf');
  }
  const type = PARAMETER_TYPES[Number(leaf[3])];
  const requestedEpoch = history.datasetEpoch ?? history.epoch;
  if (!type || Number(requestedEpoch) !== Number(leaf[1])) {
    throw new Error('Verified parameter leaf does not match the requested epoch');
  }
  return { ...history, found: true, fieldId: leaf[2], type, value: jsonValue(leaf[4]) };
}

function decodeHex(hex: string): unknown {
  if (!/^(?:[0-9a-fA-F]{2})+$/.test(hex)) throw new Error('Invalid canonical value hex');
  const bytes = Uint8Array.from(hex.match(/../g)!, (pair) => Number.parseInt(pair, 16));
  const cursor = { offset: 0 };
  const value = decode(bytes, cursor);
  if (cursor.offset !== bytes.length) throw new Error('Trailing CBOR data');
  return value;
}

function decode(bytes: Uint8Array, cursor: { offset: number }): unknown {
  if (cursor.offset >= bytes.length) throw new Error('Truncated CBOR data');
  const head = bytes[cursor.offset++];
  const major = head >>> 5;
  const size = length(bytes, cursor, head & 31);
  if (major === 0) return size;
  if (major === 1) return -1n - size;
  if (major === 2) return toHex(take(bytes, cursor, safeSize(size)));
  if (major === 3) return new TextDecoder('utf-8', { fatal: true })
    .decode(take(bytes, cursor, safeSize(size)));
  if (major === 4) return Array.from({ length: safeSize(size) }, () => decode(bytes, cursor));
  if (major === 5) {
    const result: JsonRecord = {};
    for (let index = 0; index < safeSize(size); index++) {
      const key = decode(bytes, cursor);
      if (typeof key !== 'string') throw new Error('Unsupported CBOR map key');
      result[key] = decode(bytes, cursor);
    }
    return result;
  }
  if (major === 6) {
    const tagged = decode(bytes, cursor);
    if ((size === 2n || size === 3n) && typeof tagged === 'string'
      && /^(?:[0-9a-f]{2})+$/.test(tagged) && !tagged.startsWith('00')) {
      const magnitude = BigInt(`0x${tagged}`);
      return size === 2n ? magnitude : -1n - magnitude;
    }
    throw new Error('Unsupported canonical CBOR tag');
  }
  if (major === 7 && (head & 31) === 20) return false;
  if (major === 7 && (head & 31) === 21) return true;
  if (major === 7 && (head & 31) === 22) return null;
  throw new Error('Unsupported canonical CBOR value');
}

function length(bytes: Uint8Array, cursor: { offset: number }, additional: number): bigint {
  if (additional < 24) return BigInt(additional);
  const width = additional === 24 ? 1 : additional === 25 ? 2
    : additional === 26 ? 4 : additional === 27 ? 8 : 0;
  if (!width || cursor.offset + width > bytes.length) throw new Error('Invalid CBOR length');
  let value = 0n;
  for (let index = 0; index < width; index++) value = value * 256n + BigInt(bytes[cursor.offset++]);
  return value;
}

function safeSize(value: bigint): number {
  if (value > BigInt(Number.MAX_SAFE_INTEGER)) throw new Error('CBOR value is too large');
  return Number(value);
}

function take(bytes: Uint8Array, cursor: { offset: number }, count: number): Uint8Array {
  if (cursor.offset + count > bytes.length) throw new Error('Truncated CBOR data');
  const value = bytes.slice(cursor.offset, cursor.offset + count);
  cursor.offset += count;
  return value;
}

function toHex(bytes: Uint8Array): string {
  return Array.from(bytes, (value) => value.toString(16).padStart(2, '0')).join('');
}

function jsonValue(value: unknown): unknown {
  if (typeof value === 'bigint') return value.toString();
  if (Array.isArray(value)) return value.map(jsonValue);
  if (value && typeof value === 'object') {
    return Object.fromEntries(Object.entries(value as JsonRecord)
      .map(([key, item]) => [key, jsonValue(item)]));
  }
  return value;
}
