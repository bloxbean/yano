import { describe, expect, it } from 'vitest';
import { adaToLovelace, validateDeposit, type EutxoBridgeInfo } from './deposit';

const info: EutxoBridgeInfo = {
  chainId: 'payment-chain-l1bridge',
  vaultAddress: 'addr_test1wpxg9ntn83pztkpw09lfkvv4uurd7pxztlx7yg0zqr0frdcuc9zzj',
  vaultScriptHash: '4c82cd733c4225d82e797e9b3195e706df04c25fcde221e200de91b7',
  withdrawalAddress: 'addr_test1vrpz48l78va55y3ewuv7p6narrtgsw2ajq3ns9xx945e0vsmpxjls',
  bridgeEpoch: 1,
  maxDepositLovelace: 100_000_000,
  withdrawalsPaused: false,
  stabilityDepth: 2
};

describe('adaToLovelace', () => {
  it('parses whole and fractional ADA', () => {
    expect(adaToLovelace('5')).toBe(5_000_000);
    expect(adaToLovelace('5.25')).toBe(5_250_000);
    expect(adaToLovelace(' 0.000001 ')).toBe(1);
  });
  it('rejects malformed values', () => {
    expect(adaToLovelace('')).toBeNull();
    expect(adaToLovelace('-3')).toBeNull();
    expect(adaToLovelace('1.1234567')).toBeNull();
    expect(adaToLovelace('5 ada')).toBeNull();
  });
});

describe('validateDeposit', () => {
  it('enforces the minimum and the chain cap', () => {
    expect(validateDeposit(null, info)).toContain('ADA amount');
    expect(validateDeposit(500_000, info)).toContain('at least 1 ADA');
    expect(validateDeposit(200_000_000, info)).toContain('caps deposits at 100');
    expect(validateDeposit(5_000_000, info)).toBeNull();
  });
});
