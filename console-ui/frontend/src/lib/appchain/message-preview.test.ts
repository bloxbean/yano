import { describe, expect, it } from 'vitest';
import { MAX_PREVIEW_BYTES, messagePreview } from './message-preview';

describe('message preview', () => {
  it('renders JSON without HTML interpretation', () => {
    const hex = Array.from(new TextEncoder().encode('{"message":"<script>"}'),
      (value) => value.toString(16).padStart(2, '0')).join('');
    const preview = messagePreview(hex);
    expect(preview.format).toBe('json');
    expect(preview.bodyText).toContain('<script>');
  });

  it('rejects invalid hex and bounds large payloads', () => {
    expect(messagePreview('abc').valid).toBe(false);
    const preview = messagePreview('00'.repeat(MAX_PREVIEW_BYTES + 2));
    expect(preview.truncated).toBe(true);
    expect(preview.rawHex).toHaveLength(MAX_PREVIEW_BYTES * 2);
  });
});
