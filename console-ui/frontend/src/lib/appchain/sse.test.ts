import { describe, expect, it } from 'vitest';
import { parseSseFrame, StreamCursor } from './sse';

describe('app-chain SSE', () => {
  it('parses CRLF and multi-line data', () => {
    expect(parseSseFrame('event: note\r\ndata: one\r\ndata: two')).toEqual({ event: 'note', data: 'one\ntwo' });
  });

  it('does not advance the cursor on heartbeat and deduplicates replay', () => {
    const cursor = new StreamCursor();
    expect(cursor.fromHeight(8)).toBe(8);
    expect(cursor.accept('event: heartbeat\ndata: 99', 'orders')).toBeNull();
    expect(cursor.fromHeight(8)).toBe(8);
    const raw = 'event: app-message\ndata: {"height":9,"index":0,"topic":"orders"}';
    expect(cursor.accept(raw, 'orders')?.height).toBe(9);
    expect(cursor.fromHeight(8)).toBe(9);
    expect(cursor.accept(raw, 'orders')).toBeNull();
  });

  it('rejects malformed positions', () => {
    const cursor = new StreamCursor();
    expect(cursor.accept('event: app-message\ndata: {"height":1.5,"index":0}', 'x')).toBeNull();
    expect(cursor.accept('event: app-message\ndata: nope', 'x')).toBeNull();
  });
});
