export const MAX_PREVIEW_BYTES = 64 * 1024;

export interface MessagePreview {
  valid: boolean;
  byteLength: number;
  truncated: boolean;
  rawHex: string;
  bodyText: string;
  format: 'json' | 'utf-8' | 'hex' | 'invalid';
}

export function messagePreview(bodyHex: unknown): MessagePreview {
  const source = typeof bodyHex === 'string' ? bodyHex.trim() : '';
  if (source.length % 2 !== 0 || !/^[0-9a-fA-F]*$/.test(source)) {
    return { valid: false, byteLength: 0, truncated: false, rawHex: source,
      bodyText: 'Invalid hexadecimal payload', format: 'invalid' };
  }
  const byteLength = source.length / 2;
  const previewHex = source.slice(0, MAX_PREVIEW_BYTES * 2);
  const bytes = new Uint8Array(previewHex.length / 2);
  for (let index = 0; index < bytes.length; index++) {
    bytes[index] = Number.parseInt(previewHex.slice(index * 2, index * 2 + 2), 16);
  }
  try {
    const decoded = new TextDecoder('utf-8', { fatal: true }).decode(bytes);
    if (/[\u0000-\u0008\u000b\u000c\u000e-\u001f]/.test(decoded)) throw new Error('binary');
    try {
      return { valid: true, byteLength, truncated: byteLength > MAX_PREVIEW_BYTES,
        rawHex: previewHex, bodyText: JSON.stringify(JSON.parse(decoded), null, 2), format: 'json' };
    } catch {
      return { valid: true, byteLength, truncated: byteLength > MAX_PREVIEW_BYTES,
        rawHex: previewHex, bodyText: decoded, format: 'utf-8' };
    }
  } catch {
    return { valid: true, byteLength, truncated: byteLength > MAX_PREVIEW_BYTES,
      rawHex: previewHex, bodyText: previewHex, format: 'hex' };
  }
}

export async function completePayloadDigest(bodyHex: string): Promise<string | null> {
  const preview = messagePreview(bodyHex);
  if (!preview.valid || preview.truncated) return null;
  const bytes = new Uint8Array(bodyHex.length / 2);
  for (let index = 0; index < bytes.length; index++) {
    bytes[index] = Number.parseInt(bodyHex.slice(index * 2, index * 2 + 2), 16);
  }
  const digest = new Uint8Array(await crypto.subtle.digest('SHA-256', bytes));
  return Array.from(digest, (value) => value.toString(16).padStart(2, '0')).join('');
}
