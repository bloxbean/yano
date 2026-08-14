export type SemanticCheck = {
  id: string;
  label: string;
  passed: boolean;
  actual?: string;
  expected?: string;
};

export type ClaimVerdict = {
  evaluated: boolean;
  valid: boolean;
  summary: string;
  checks: SemanticCheck[];
};

type JsonRecord = Record<string, unknown>;

export function combineProofAndClaim(
  cryptographic: JsonRecord, claim: ClaimVerdict | null
): JsonRecord {
  const proofValid = cryptographic.valid === true;
  const claimValid = claim?.valid ?? null;
  return {
    proofValid,
    claimEvaluated: claim?.evaluated ?? false,
    claimValid,
    accepted: proofValid && (claimValid ?? true),
    explanation: !proofValid
      ? 'The cryptographic proof or L1 anchor binding failed.'
      : claimValid === false
        ? 'The proof is authentic, but the requested claim is false.'
        : claimValid === true
          ? 'The proof is authentic and every requested condition is true.'
          : 'The proof is authentic. No semantic claim was requested.',
    claim,
    cryptographic
  };
}

export function packageVerification(
  result: JsonRecord, actual: JsonRecord, requested: JsonRecord | null
): JsonRecord {
  const proofAuthentic = result.proofValid === true || result.valid === true;
  const claimEvaluated = result.claimEvaluated === true;
  const claimSatisfied = typeof result.claimValid === 'boolean' ? result.claimValid : null;
  const accepted = result.accepted === true || (!requested && proofAuthentic);
  const verdict = result.claim && typeof result.claim === 'object' && !Array.isArray(result.claim)
    ? result.claim as JsonRecord : null;
  return {
    status: accepted ? 'accepted' : 'rejected',
    method: 'offchain',
    verifierMustRecompute: true,
    proofAuthentic,
    claimEvaluated,
    claimSatisfied,
    accepted,
    actual,
    requested,
    checks: Array.isArray(verdict?.checks) ? verdict.checks : [],
    explanation: String(result.explanation ?? (proofAuthentic
      ? 'The proof is authentic.' : 'The proof could not be authenticated.'))
  };
}

export function evaluateStakeClaim(history: JsonRecord, claim: JsonRecord): ClaimVerdict {
  const mode = String(claim.mode ?? '');
  const found = history.found === true;
  const actualCoin = decimal(history.coin);
  const expectedCoin = decimal(claim.expectedCoinLovelace);
  const actualPool = normalizedHex(history.poolHash);
  const expectedPool = normalizedHex(claim.expectedPoolHash);
  const checks: SemanticCheck[] = [];

  if (mode === 'absence') {
    checks.push({ id: 'absent', label: 'Credential is absent', passed: !found });
    checks.push({ id: 'complete', label: 'Epoch snapshot is complete',
      passed: history.complete === true });
    return verdict(checks, 'The credential is absent from a complete epoch snapshot.');
  }
  if (!found || actualCoin == null) {
    return { evaluated: true, valid: false,
      summary: 'The credential was not found, so this stake claim cannot be true.',
      checks: [{ id: 'present', label: 'Credential exists', passed: false }] };
  }

  checks.push({ id: 'present', label: 'Credential exists', passed: true });
  if (mode === 'minimum' || mode === 'minimum-and-pool') {
    checks.push(expectedCoin == null
      ? invalidInput('amount', 'Minimum amount is valid')
      : { id: 'amount', label: 'Stake is at least the required amount',
        passed: actualCoin >= expectedCoin, actual: actualCoin.toString(),
        expected: expectedCoin.toString() });
  }
  if (mode === 'exact' || mode === 'exact-and-pool') {
    checks.push(expectedCoin == null
      ? invalidInput('amount', 'Exact amount is valid')
      : { id: 'amount', label: 'Stake equals the required amount',
        passed: actualCoin === expectedCoin, actual: actualCoin.toString(),
        expected: expectedCoin.toString() });
  }
  if (mode === 'pool' || mode === 'minimum-and-pool' || mode === 'exact-and-pool') {
    checks.push(!expectedPool
      ? invalidInput('pool', 'Pool hash is valid')
      : { id: 'pool', label: 'Delegated pool matches', passed: actualPool === expectedPool,
        actual: actualPool || 'none', expected: expectedPool });
  }
  if (checks.length === 1) {
    return { evaluated: true, valid: false, summary: 'Choose a supported stake claim.', checks };
  }
  return verdict(checks, 'Every requested stake condition is satisfied.');
}

export function evaluateParameterClaim(history: JsonRecord, claim: JsonRecord): ClaimVerdict {
  const mode = String(claim.mode ?? '');
  const found = history.found === true;
  if (mode === 'absence') {
    return verdict([
      { id: 'absent', label: 'Field is absent for this epoch', passed: !found },
      { id: 'complete', label: 'Parameter document is complete', passed: history.complete === true }
    ], 'The field is absent from a complete parameter document.');
  }
  if (!found) {
    return { evaluated: true, valid: false,
      summary: 'The field was not found, so this parameter claim cannot be true.',
      checks: [{ id: 'present', label: 'Parameter field exists', passed: false }] };
  }

  const type = String(history.type ?? '');
  const actual = history.value;
  const expectedText = String(claim.expectedValue ?? '').trim();
  const upperText = String(claim.maximumValue ?? '').trim();
  const checks: SemanticCheck[] = [
    { id: 'present', label: 'Parameter field exists', passed: true },
    { id: 'field', label: 'Authenticated field matches the requested parameter',
      passed: String(history.fieldId ?? '') === String(claim.fieldId ?? ''),
      actual: String(history.fieldId ?? ''), expected: String(claim.fieldId ?? '') },
    { id: 'type', label: `Authenticated type is ${type || 'known'}`, passed: Boolean(type) }
  ];
  if (['unsigned-integer', 'signed-integer', 'lovelace'].includes(type)) {
    const signed = type === 'signed-integer';
    const actualNumber = decimal(actual, signed);
    const expected = decimal(expectedText, signed);
    const upper = decimal(upperText, signed);
    if (actualNumber == null || expected == null) {
      checks.push(invalidInput('value', 'Expected integer is valid'));
    } else if (mode === 'exact') {
      checks.push({ id: 'value', label: 'Value equals the expected value',
        passed: actualNumber === expected, actual: actualNumber.toString(), expected: expected.toString() });
    } else if (mode === 'minimum') {
      checks.push({ id: 'value', label: 'Value is at least the minimum',
        passed: actualNumber >= expected, actual: actualNumber.toString(), expected: expected.toString() });
    } else if (mode === 'maximum') {
      checks.push({ id: 'value', label: 'Value is at most the maximum',
        passed: actualNumber <= expected, actual: actualNumber.toString(), expected: expected.toString() });
    } else if (mode === 'range') {
      checks.push({ id: 'range', label: 'Value is inside the inclusive range',
        passed: upper != null && expected <= actualNumber && actualNumber <= upper,
        actual: actualNumber.toString(), expected: upper == null ? 'invalid range'
          : `${expected}…${upper}` });
    } else checks.push(invalidInput('mode', 'Claim mode is supported'));
    return verdict(checks, 'The authenticated numeric parameter satisfies the claim.');
  }

  if (mode !== 'exact') {
    checks.push(invalidInput('mode', 'This field type supports exact comparison'));
    return verdict(checks, 'The authenticated parameter satisfies the exact claim.');
  }
  let expected: unknown = expectedText;
  if (type === 'rational') expected = parseRational(expectedText);
  else if (type === 'protocol-version' || type === 'structured') expected = parseJson(expectedText);
  else if (type === 'bytes') expected = normalizedHex(expectedText);
  const actualNormalized = type === 'bytes' ? normalizedHex(actual) : actual;
  checks.push({ id: 'value', label: 'Value exactly matches',
    passed: expected != null && stableJson(actualNormalized) === stableJson(expected),
    actual: display(actualNormalized), expected: display(expected) });
  return verdict(checks, 'The authenticated parameter exactly matches the claim.');
}

function verdict(checks: SemanticCheck[], success: string): ClaimVerdict {
  const valid = checks.length > 0 && checks.every((check) => check.passed);
  return { evaluated: true, valid,
    summary: valid ? success : 'One or more requested conditions are not satisfied.', checks };
}

function invalidInput(id: string, label: string): SemanticCheck {
  return { id, label, passed: false, expected: 'valid input required' };
}

function decimal(value: unknown, signed = false): bigint | null {
  const text = typeof value === 'bigint' ? value.toString() : String(value ?? '');
  const pattern = signed ? /^-?(0|[1-9]\d*)$/ : /^(0|[1-9]\d*)$/;
  return pattern.test(text) ? BigInt(text) : null;
}

function normalizedHex(value: unknown): string {
  const text = String(value ?? '').toLowerCase();
  return /^(?:[0-9a-f]{2})+$/.test(text) ? text : '';
}

function parseRational(value: string): unknown {
  const match = value.match(/^(-?\d+)\s*\/\s*([1-9]\d*)$/);
  if (!match) return null;
  let numerator = BigInt(match[1]);
  let denominator = BigInt(match[2]);
  const divisor = gcd(numerator < 0n ? -numerator : numerator, denominator);
  numerator /= divisor;
  denominator /= divisor;
  return [numerator.toString(), denominator.toString()];
}

function gcd(left: bigint, right: bigint): bigint {
  while (right !== 0n) [left, right] = [right, left % right];
  return left;
}

function parseJson(value: string): unknown {
  try { return JSON.parse(value); } catch { return null; }
}

function stableJson(value: unknown): string {
  if (Array.isArray(value)) return `[${value.map(stableJson).join(',')}]`;
  if (value && typeof value === 'object') {
    const entries = Object.entries(value as JsonRecord).sort(([left], [right]) => left.localeCompare(right));
    return `{${entries.map(([key, item]) => `${JSON.stringify(key)}:${stableJson(item)}`).join(',')}}`;
  }
  return JSON.stringify(value);
}

function display(value: unknown): string {
  return value == null ? 'invalid input' : typeof value === 'string' ? value : stableJson(value);
}
