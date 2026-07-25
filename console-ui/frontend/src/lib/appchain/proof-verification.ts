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
  return { key, chainId, committedHeight, stateRoot, proofWireHex, valueHex, finalizedAtHeight };
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
