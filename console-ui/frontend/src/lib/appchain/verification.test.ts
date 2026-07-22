import { describe, expect, it } from 'vitest';
import { asciiHex, boundedPretty, hexSha256, PRODUCT_ID, SHA256, STATE_KEY } from './verification';

describe('capability verification helpers', () => {
  it('hashes the exact bytes represented by canonical hex', async () => {
    expect(await hexSha256('68656c6c6f')).toBe(
      '2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824');
    await expect(hexSha256('ABC')).rejects.toThrow('canonical');
  });

  it('bounds identifiers, state keys and rendered inspection output', () => {
    expect(SHA256.test('a'.repeat(64))).toBe(true);
    expect(STATE_KEY.test('00ff')).toBe(true);
    expect(PRODUCT_ID.test('proposal-42')).toBe(true);
    expect(asciiHex('proposal-42')).toBe('70726f706f73616c2d3432');
    expect(boundedPretty({ value: 'x'.repeat(100) }, 20)).toContain('truncated');
  });
});
