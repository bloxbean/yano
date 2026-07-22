export const SHA256 = /^[0-9a-f]{64}$/;
export const STATE_KEY = /^(?:[0-9a-f]{2}){1,256}$/;
export const PRODUCT_ID = /^[a-z0-9][a-z0-9._:-]{0,127}$/;

export function asciiHex(value: string): string {
  if (!PRODUCT_ID.test(value)) throw new Error('Invalid product identifier');
  return Array.from(new TextEncoder().encode(value),
    (item) => item.toString(16).padStart(2, '0')).join('');
}

export async function hexSha256(value: string): Promise<string> {
  if (value.length % 2 !== 0 || !/^[0-9a-f]*$/.test(value) || value.length > 2 * 1024 * 1024) {
    throw new Error('Value must be canonical lowercase hex up to 1 MiB');
  }
  const bytes = new Uint8Array(value.length / 2);
  for (let index = 0; index < bytes.length; index++) {
    bytes[index] = Number.parseInt(value.slice(index * 2, index * 2 + 2), 16);
  }
  const digest = new Uint8Array(await crypto.subtle.digest('SHA-256', bytes));
  return Array.from(digest, (item) => item.toString(16).padStart(2, '0')).join('');
}

export function boundedPretty(value: unknown, max = 32 * 1024): string {
  const rendered = JSON.stringify(value, null, 2) ?? String(value);
  return rendered.length <= max ? rendered : `${rendered.slice(0, max)}\n… output truncated in console …`;
}
