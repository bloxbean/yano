import type { AppChainStatus, AuthMapCollection, AuthMapEnvelope } from '$lib/api/types';

export const AUTHMAP_BUNDLE_ID = 'com.bloxbean.cardano.yano.appchain.stdlib';
export const AUTHMAP_STATE_MACHINE_ID = 'authenticated-map';
export const AUTHMAP_API_VERSION = 'authenticated-map-domain-v1';
export const AUTHMAP_COMMAND_TOPIC = 'authenticated-map.command.v1';
export const ROLE_COMMAND_TOPIC = 'role-approvals.command.v1';
export const ACTOR_COMMAND_TOPIC = 'actors.command.v1';
export const AUTHENTICATED_RECORD = 'authenticated-record';
export const DERIVED_FROM_PENDING_INDEX = 'DERIVED_FROM_PENDING_INDEX';

export const AUTH_OPEN = 0;
export const AUTH_OWNER = 1;
export const AUTH_MEMBER = 2;
export const AUTH_GOVERNED_ROLE = 3;
export const AUTH_APPROVAL = 4;

export const OP_PUT = 0;
export const OP_PUT_IF_ABSENT = 1;
export const OP_COMPARE_AND_SET = 2;
export const OP_TRANSFER_CONTROLLER = 3;
export const OP_REVOKE = 4;
export const OP_RESTORE = 5;

const MAX_COLLECTION_ID_BYTES = 64;
const MAX_APPLICATION_KEY_BYTES = 128;
const MAX_VALUE_BYTES = 1_048_576;
const MAX_BATCH_ITEMS = 128;
const MAX_BATCH_BYTES = 1_048_576;
const STATE_MACHINE_VERSION = 1;

export function isAuthenticatedMapChain(status: AppChainStatus | null): boolean {
  return status?.capabilityManifest?.components.some(
    (component) => component.id === AUTHMAP_STATE_MACHINE_ID) === true;
}

const AUTHORIZATION_LABELS = ['open', 'owner', 'member', 'governed-role', 'approval'];
export function authorizationLabel(mode: number): string {
  return AUTHORIZATION_LABELS[mode] ?? `unknown(${mode})`;
}

export function valueEncodingLabel(encoding: number): string {
  return encoding === 0 ? 'opaque' : encoding === 1 ? 'canonical-cbor' : `unknown(${encoding})`;
}

const PRESENCE_LABELS = ['ABSENT', 'ACTIVE', 'REVOKED'];
export function presenceLabel(presence: number): string {
  return PRESENCE_LABELS[presence] ?? `unknown(${presence})`;
}

export function entryStatusLabel(status: number | undefined): string {
  return status === 0 ? 'ACTIVE' : status === 1 ? 'REVOKED' : '-';
}

export function receiptStatusLabel(status: number | undefined): string {
  return status === 0 ? 'APPLIED' : status === 1 ? 'REJECTED' : '-';
}

const RECEIPT_ERRORS = [
  'none', 'unknown-collection', 'collection-bounds', 'unauthorized', 'already-exists',
  'absent', 'revoked', 'active', 'precondition', 'restore-forbidden', 'value-encoding',
  'value-schema', 'value-validator', 'authorization-assignment', 'unknown-policy',
  'policy-inactive', 'actor-ineligible', 'actor-signature', 'authorization-deadline',
  'direct-authorization-replay', 'approval-not-approved', 'approval-mismatch',
  'approval-replay', 'capacity-exceeded', 'crypto-work-exceeded',
  'governed-route-unsupported', 'wrong-genesis', 'wrong-revision'
];
export function receiptErrorLabel(code: number | undefined): string {
  if (code === undefined) return '-';
  return RECEIPT_ERRORS[code] ?? `unknown(${code})`;
}

/**
 * Identity gate for every exact-record domain response (ADR-025.2 §10.1).
 * Composed role-workflow routes omit apiVersion/verificationLevel; pass
 * mapRoute=false for them.
 */
export function assertAuthMapEnvelope(
  envelope: AuthMapEnvelope<unknown>, chainId: string, mapRoute = true
): void {
  const identity = envelope.chainId === chainId
    && envelope.stateMachineId === AUTHMAP_STATE_MACHINE_ID
    && Number.isSafeInteger(envelope.committedHeight) && envelope.committedHeight >= 0
    && isHex(envelope.stateRoot);
  const level = mapRoute
    ? envelope.apiVersion === AUTHMAP_API_VERSION
      && envelope.verificationLevel === AUTHENTICATED_RECORD
    : envelope.apiVersion === undefined;
  if (!identity || !level) {
    throw new Error('Authenticated-map response identity does not match the selected chain');
  }
}

/** Identity gate for bounded pending pages, which are derived projections. */
export function assertPendingEnvelope(
  envelope: AuthMapEnvelope<unknown>, chainId: string
): void {
  if (envelope.chainId !== chainId
    || envelope.stateMachineId !== AUTHMAP_STATE_MACHINE_ID
    || envelope.apiVersion !== AUTHMAP_API_VERSION
    || envelope.verificationLevel !== DERIVED_FROM_PENDING_INDEX
    || !envelope.sourceIndexProofKey
    || envelope.proofKey !== undefined || envelope.recordValue !== undefined) {
    throw new Error('Pending-page response identity does not match the selected chain');
  }
}

export function isHex(value: unknown): value is string {
  return typeof value === 'string' && value.length % 2 === 0 && /^[0-9a-f]*$/.test(value);
}

export function hexToBytes(value: string): Uint8Array {
  if (!isHex(value)) throw new Error('Value must be canonical lowercase hex');
  const bytes = new Uint8Array(value.length / 2);
  for (let i = 0; i < bytes.length; i++) bytes[i] = parseInt(value.slice(i * 2, i * 2 + 2), 16);
  return bytes;
}

export function bytesToHex(bytes: Uint8Array): string {
  let out = '';
  for (const byte of bytes) out += byte.toString(16).padStart(2, '0');
  return out;
}

export function utf8ToHex(value: string): string {
  return bytesToHex(new TextEncoder().encode(value));
}

// --- Canonical CBOR (definite lengths, minimal-length arguments) -----------

function head(major: number, argument: number): Uint8Array {
  if (!Number.isSafeInteger(argument) || argument < 0) throw new Error('CBOR argument out of range');
  if (argument < 24) return Uint8Array.of((major << 5) | argument);
  if (argument < 0x100) return Uint8Array.of((major << 5) | 24, argument);
  if (argument < 0x10000) return Uint8Array.of((major << 5) | 25, argument >> 8, argument & 0xff);
  if (argument < 0x1_0000_0000) {
    return Uint8Array.of((major << 5) | 26,
      (argument >>> 24) & 0xff, (argument >>> 16) & 0xff, (argument >>> 8) & 0xff, argument & 0xff);
  }
  const high = Math.floor(argument / 0x1_0000_0000);
  const low = argument % 0x1_0000_0000;
  return Uint8Array.of((major << 5) | 27,
    (high >>> 24) & 0xff, (high >>> 16) & 0xff, (high >>> 8) & 0xff, high & 0xff,
    (low >>> 24) & 0xff, (low >>> 16) & 0xff, (low >>> 8) & 0xff, low & 0xff);
}

function concat(parts: Uint8Array[]): Uint8Array {
  const out = new Uint8Array(parts.reduce((size, part) => size + part.length, 0));
  let offset = 0;
  for (const part of parts) { out.set(part, offset); offset += part.length; }
  return out;
}

export function cborUint(value: number): Uint8Array { return head(0, value); }
export function cborBytes(value: Uint8Array): Uint8Array {
  return concat([head(2, value.length), value]);
}
export function cborText(value: string): Uint8Array {
  const encoded = new TextEncoder().encode(value);
  return concat([head(3, encoded.length), encoded]);
}
export function cborArray(items: Uint8Array[]): Uint8Array {
  return concat([head(4, items.length), ...items]);
}

// --- Bounded CBOR decoding (arrays/uints/bytes/text only) ------------------

export type CborValue = number | string | Uint8Array | CborValue[];

const MAX_DECODE_BYTES = 2_097_152;
const MAX_DECODE_DEPTH = 8;
const MAX_DECODE_ITEMS = 4_096;

export function decodeCbor(bytes: Uint8Array): CborValue {
  if (bytes.length === 0 || bytes.length > MAX_DECODE_BYTES) {
    throw new Error('CBOR payload is empty or exceeds the bounded decode size');
  }
  const state = { offset: 0, items: 0 };
  const value = decodeItem(bytes, state, 0);
  if (state.offset !== bytes.length) throw new Error('CBOR payload has trailing bytes');
  return value;
}

function decodeItem(bytes: Uint8Array, state: { offset: number; items: number }, depth: number): CborValue {
  if (depth > MAX_DECODE_DEPTH || ++state.items > MAX_DECODE_ITEMS) {
    throw new Error('CBOR payload exceeds bounded depth or item count');
  }
  if (state.offset >= bytes.length) throw new Error('CBOR payload is truncated');
  const initial = bytes[state.offset++];
  const major = initial >> 5;
  const info = initial & 0x1f;
  let argument: number;
  if (info < 24) argument = info;
  else if (info <= 27) {
    const width = 1 << (info - 24);
    if (state.offset + width > bytes.length) throw new Error('CBOR payload is truncated');
    argument = 0;
    for (let i = 0; i < width; i++) argument = argument * 256 + bytes[state.offset++];
    if (!Number.isSafeInteger(argument)) throw new Error('CBOR argument exceeds safe integer range');
  } else throw new Error('CBOR payload uses an unsupported encoding');
  switch (major) {
    case 0:
      return argument;
    case 2: case 3: {
      if (state.offset + argument > bytes.length) throw new Error('CBOR payload is truncated');
      const slice = bytes.slice(state.offset, state.offset + argument);
      state.offset += argument;
      return major === 2 ? slice : new TextDecoder('utf-8', { fatal: true }).decode(slice);
    }
    case 4: {
      const items: CborValue[] = [];
      for (let i = 0; i < argument; i++) items.push(decodeItem(bytes, state, depth + 1));
      return items;
    }
    default:
      throw new Error('CBOR payload uses an unsupported major type');
  }
}

export interface DecodedEntry {
  status: number;
  revision: number;
  controllerHex: string;
  valueHex: string;
  logicalValueHashHex: string;
  createdHeight: number;
  lastMutationHeight: number;
}

/** Decodes the canonical entry recordValue: [1, status, revision, controller, value, hash, created, last]. */
export function decodeEntryRecordValue(hex: string): DecodedEntry {
  const value = decodeCbor(hexToBytes(hex));
  if (!Array.isArray(value) || value.length !== 8 || value[0] !== STATE_MACHINE_VERSION) {
    throw new Error('Entry record value is not the canonical authenticated-map entry encoding');
  }
  const [, status, revision, controller, entryValue, hash, created, last] = value;
  if (typeof status !== 'number' || typeof revision !== 'number'
    || !(controller instanceof Uint8Array) || !(entryValue instanceof Uint8Array)
    || !(hash instanceof Uint8Array) || typeof created !== 'number' || typeof last !== 'number') {
    throw new Error('Entry record value is not the canonical authenticated-map entry encoding');
  }
  return {
    status, revision,
    controllerHex: bytesToHex(controller),
    valueHex: bytesToHex(entryValue),
    logicalValueHashHex: bytesToHex(hash),
    createdHeight: created,
    lastMutationHeight: last
  };
}

// --- Command construction and local preflight ------------------------------

export interface MutationSpec {
  operation: number;
  collectionId: string;
  keyHex: string;
  valueHex: string;
  expectedRevision: number;
  expectedValueHashHex: string;
  newControllerHex: string;
}

export const OPERATION_LABELS: Record<number, string> = {
  [OP_PUT]: 'PUT',
  [OP_PUT_IF_ABSENT]: 'PUT_IF_ABSENT',
  [OP_COMPARE_AND_SET]: 'COMPARE_AND_SET',
  [OP_TRANSFER_CONTROLLER]: 'TRANSFER_CONTROLLER',
  [OP_REVOKE]: 'REVOKE',
  [OP_RESTORE]: 'RESTORE'
};

/**
 * Local preflight mirroring AuthenticatedMapContract.Mutation validation plus
 * the collection descriptor bounds. Throws with an operator-readable reason.
 */
export function preflightMutation(spec: MutationSpec, collection?: AuthMapCollection): void {
  const id = new TextEncoder().encode(spec.collectionId);
  if (id.length === 0 || id.length > MAX_COLLECTION_ID_BYTES) {
    throw new Error('Collection ID must be 1-64 bytes');
  }
  const key = hexToBytes(spec.keyHex);
  if (key.length === 0 || key.length > MAX_APPLICATION_KEY_BYTES) {
    throw new Error('Application key must be 1-128 bytes of canonical hex');
  }
  const value = hexToBytes(spec.valueHex);
  if (value.length > MAX_VALUE_BYTES) throw new Error('Value exceeds 1 MiB');
  const expectedHash = hexToBytes(spec.expectedValueHashHex);
  const controller = hexToBytes(spec.newControllerHex);
  if (expectedHash.length !== 0 && expectedHash.length !== 32) {
    throw new Error('Expected value hash must be exactly 32 bytes');
  }
  if (controller.length !== 0 && controller.length !== 32) {
    throw new Error('Controller must be exactly 32 bytes');
  }
  if (!Number.isSafeInteger(spec.expectedRevision) || spec.expectedRevision < 0) {
    throw new Error('Expected revision must be nonnegative');
  }
  switch (spec.operation) {
    case OP_PUT: case OP_PUT_IF_ABSENT: case OP_RESTORE:
      if (spec.expectedRevision !== 0 || expectedHash.length !== 0 || controller.length !== 0) {
        throw new Error('This operation does not accept precondition fields');
      }
      break;
    case OP_COMPARE_AND_SET:
      if ((spec.expectedRevision === 0 && expectedHash.length === 0) || controller.length !== 0) {
        throw new Error('Compare-and-set requires a revision or value hash and no controller');
      }
      break;
    case OP_TRANSFER_CONTROLLER:
      if (value.length !== 0 || controller.length !== 32) {
        throw new Error('Controller transfer requires an empty value and one 32-byte controller');
      }
      break;
    case OP_REVOKE:
      if (value.length !== 0 || controller.length !== 0) {
        throw new Error('Revoke cannot contain a value or new controller');
      }
      break;
    default:
      throw new Error('Unsupported authenticated-map operation');
  }
  if (collection) {
    if (key.length > collection.maxKeyBytes) {
      throw new Error(`Key exceeds the collection bound of ${collection.maxKeyBytes} bytes`);
    }
    if (value.length > collection.maxValueBytes) {
      throw new Error(`Value exceeds the collection bound of ${collection.maxValueBytes} bytes`);
    }
    if (spec.operation === OP_RESTORE && !collection.restoreAllowed) {
      throw new Error('This collection forbids restore');
    }
  }
}

function encodeMutation(spec: MutationSpec): Uint8Array {
  return cborArray([
    cborUint(spec.operation),
    cborText(spec.collectionId),
    cborBytes(hexToBytes(spec.keyHex)),
    cborBytes(hexToBytes(spec.valueHex)),
    cborUint(spec.expectedRevision),
    cborBytes(hexToBytes(spec.expectedValueHashHex)),
    cborBytes(hexToBytes(spec.newControllerHex))
  ]);
}

/**
 * Legacy plain command: [1, single|batch, [mutation...]]. Mirrors
 * AuthenticatedMapContract.encodeCommand. The composite chain does NOT admit
 * this on the wire; it is the `--command-hex` input the offline signing CLI
 * consumes when assembling a governed action (§10.3).
 */
export function encodeCommandHex(mutations: MutationSpec[], collections?: AuthMapCollection[]): string {
  if (mutations.length === 0 || mutations.length > MAX_BATCH_ITEMS) {
    throw new Error('A command requires 1-128 mutations');
  }
  const seen = new Set<string>();
  for (const spec of mutations) {
    preflightMutation(spec, collections?.find((entry) => entry.id === spec.collectionId));
    const identity = `${spec.collectionId}:${spec.keyHex}`;
    if (seen.has(identity)) throw new Error('Batch contains a duplicate collection/key');
    seen.add(identity);
  }
  const encoded = cborArray([
    cborUint(STATE_MACHINE_VERSION),
    cborUint(mutations.length === 1 ? 0 : 1),
    cborArray(mutations.map(encodeMutation))
  ]);
  if (encoded.length > MAX_BATCH_BYTES) throw new Error('Command exceeds the 1 MiB bound');
  return bytesToHex(encoded);
}

/**
 * Final v1 submit envelope for basic (open/owner/member) collections:
 * [1, bytes([1, batch, [mutation...], [assignment...]]), []] with one
 * evidence-free assignment per mutation. This is the only encoding the
 * composite authenticated-map chain admits on
 * authenticated-map.command.v1; governed/approval collections instead
 * import an externally signed envelope.
 */
export function encodeBasicEnvelopeHex(
  mutations: MutationSpec[], collections: AuthMapCollection[]
): string {
  if (mutations.length === 0 || mutations.length > MAX_BATCH_ITEMS) {
    throw new Error('A command requires 1-128 mutations');
  }
  const seen = new Set<string>();
  const assignments: Uint8Array[] = [];
  mutations.forEach((spec, index) => {
    const collection = collections.find((entry) => entry.id === spec.collectionId);
    preflightMutation(spec, collection);
    if (!directSubmitAllowed(collection)) {
      throw new Error('Governed and approval collections require externally signed evidence');
    }
    const identity = `${spec.collectionId}:${spec.keyHex}`;
    if (seen.has(identity)) throw new Error('Batch contains a duplicate collection/key');
    seen.add(identity);
    assignments.push(cborArray([
      cborUint(index), cborUint(collection!.authorization), cborText(''), cborUint(0)
    ]));
  });
  const action = cborArray([
    cborUint(STATE_MACHINE_VERSION),
    cborUint(mutations.length === 1 ? 0 : 1),
    cborArray(mutations.map(encodeMutation)),
    cborArray(assignments)
  ]);
  const envelope = cborArray([
    cborUint(STATE_MACHINE_VERSION), cborBytes(action), cborArray([])
  ]);
  if (envelope.length > MAX_BATCH_BYTES) throw new Error('Command exceeds the 1 MiB bound');
  return bytesToHex(envelope);
}

/** Authorization modes the console may submit directly (unsigned commands). */
export function directSubmitAllowed(collection: AuthMapCollection | undefined): boolean {
  return collection !== undefined
    && [AUTH_OPEN, AUTH_OWNER, AUTH_MEMBER].includes(collection.authorization);
}

/**
 * The offline CLI invocation that wraps the console-built canonical command
 * into a governed action for external signing (no browser-held private keys,
 * §10.3). The signer completes it with direct-preimage/direct-complete or an
 * approval reference, then `command` produces the submit-ready envelope.
 */
export function governedCliHint(commandHex: string, collection: AuthMapCollection): string {
  const mode = authorizationLabel(collection.authorization);
  const assignment = `0:${mode}:${collection.authorizationPolicy}:1`;
  return './yano.sh appchain authenticated-map action'
    + ` --command-hex ${commandHex} --assignments ${assignment}`;
}
