import type { StateProofEnvelope } from '$lib/api/types';
import { STATE_KEY } from './verification';

const SHA256 = /^[0-9a-f]{64}$/;
const CANONICAL_HEX = /^(?:[0-9a-f]{2})*$/;
const MAX_ENVELOPE_TEXT = 2 * 1024 * 1024 + 4096;
const MAX_PROOF_HEX = 2 * 1024 * 1024;
const MAX_VALUE_HEX = 2 * 1024 * 1024;

export interface ProofBindingAssessment {
  rootMatches: boolean;
  heightMatches?: boolean;
}

export function parseProofEnvelope(text: string): StateProofEnvelope {
  if (!text || text.length > MAX_ENVELOPE_TEXT) {
    throw new Error('Proof envelope is empty or exceeds the console size limit.');
  }
  const parsed = JSON.parse(text) as Record<string, unknown>;
  const key = stringField(parsed, 'key');
  const chainId = stringField(parsed, 'chainId');
  const stateRoot = stringField(parsed, 'stateRoot');
  const proofWireHex = stringField(parsed, 'proofWireHex');
  const committedHeight = numberField(parsed, 'committedHeight');
  const valueHex = optionalStringField(parsed, 'valueHex');
  const finalizedAtHeight = optionalNumberField(parsed, 'finalizedAtHeight');
  const profile = optionalStringField(parsed, 'profile');
  const backend = optionalStringField(parsed, 'backend');
  const commitmentFormatId = optionalStringField(parsed, 'commitmentFormatId');
  const formatFingerprint = optionalStringField(parsed, 'formatFingerprint');
  const genesisId = optionalStringField(parsed, 'genesisId');
  const proofEncodingId = optionalStringField(parsed, 'proofEncodingId');
  const presence = optionalStringField(parsed, 'presence');

  if (!STATE_KEY.test(key)) throw new Error('Proof key must be 1–256 bytes of canonical lowercase hex.');
  if (!chainId || chainId.length > 128) throw new Error('Proof chain id is missing or too long.');
  if (!SHA256.test(stateRoot)) throw new Error('Proof state root must be 32-byte canonical lowercase hex.');
  if (!proofWireHex || proofWireHex.length > MAX_PROOF_HEX || !CANONICAL_HEX.test(proofWireHex)) {
    throw new Error('Proof wire must be bounded canonical lowercase hex.');
  }
  if (valueHex !== undefined
    && (valueHex.length > MAX_VALUE_HEX || !CANONICAL_HEX.test(valueHex))) {
    throw new Error('Proof value must be bounded canonical lowercase hex.');
  }
  if (!Number.isSafeInteger(committedHeight) || committedHeight <= 0) {
    throw new Error('Proof committed height must be a positive safe integer.');
  }
  if (finalizedAtHeight !== undefined
    && (!Number.isSafeInteger(finalizedAtHeight) || finalizedAtHeight <= 0)) {
    throw new Error('Proof finalized height must be a positive safe integer.');
  }
  if (profile !== undefined && !/^[a-z0-9][a-z0-9._-]{0,127}$/.test(profile)) {
    throw new Error('Proof commitment profile is invalid.');
  }
  if (presence !== undefined && !['PRESENT', 'ABSENT', 'TOMBSTONED'].includes(presence)) {
    throw new Error('Proof presence is invalid.');
  }
  if (presence !== undefined && (presence === 'ABSENT') !== (valueHex === undefined)) {
    throw new Error('Proof presence and value differ.');
  }
  if (formatFingerprint !== undefined && !SHA256.test(formatFingerprint)) {
    throw new Error('Proof format fingerprint is invalid.');
  }
  if (genesisId !== undefined && genesisId !== '' && !SHA256.test(genesisId)) {
    throw new Error('Proof genesis identity is invalid.');
  }
  return {
    key, chainId, committedHeight, stateRoot, proofWireHex, valueHex, finalizedAtHeight,
    proofSchemaVersion: optionalNumberField(parsed, 'proofSchemaVersion'),
    profile, backend, commitmentFormatId, formatFingerprint, genesisId,
    legacy: typeof parsed.legacy === 'boolean' ? parsed.legacy : undefined,
    proofEncodingId,
    presence: presence as StateProofEnvelope['presence'],
    version: optionalNumberField(parsed, 'version'),
    blockHash: optionalStringField(parsed, 'blockHash'),
    block: objectField(parsed, 'block'),
    finalityCertificate: objectField(parsed, 'finalityCertificate')
  };
}

export function assessProofBinding(
  proof: StateProofEnvelope,
  expectedRoot: string,
  expectedHeight?: number
): ProofBindingAssessment {
  return {
    rootMatches: SHA256.test(expectedRoot) && proof.stateRoot === expectedRoot,
    heightMatches: expectedHeight === undefined ? undefined : proof.committedHeight === expectedHeight
  };
}

function stringField(value: Record<string, unknown>, name: string): string {
  if (typeof value[name] !== 'string') throw new Error(`Proof ${name} must be a string.`);
  return value[name];
}

function optionalStringField(value: Record<string, unknown>, name: string): string | undefined {
  if (value[name] === undefined) return undefined;
  return stringField(value, name);
}

function numberField(value: Record<string, unknown>, name: string): number {
  if (typeof value[name] !== 'number') throw new Error(`Proof ${name} must be a number.`);
  return value[name];
}

function optionalNumberField(value: Record<string, unknown>, name: string): number | undefined {
  if (value[name] === undefined) return undefined;
  return numberField(value, name);
}

function objectField(value: Record<string, unknown>, name: string): Record<string, unknown> | undefined {
  if (value[name] === undefined) return undefined;
  if (typeof value[name] !== 'object' || value[name] === null || Array.isArray(value[name])) {
    throw new Error(`Proof ${name} must be an object.`);
  }
  return value[name] as Record<string, unknown>;
}
