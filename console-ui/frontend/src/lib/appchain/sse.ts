import type { AppChainMessage } from '$lib/api/types';

export interface SseFrame { event: string; data: string; }

export function parseSseFrame(raw: string): SseFrame {
  let event = 'message';
  const data: string[] = [];
  for (const line of raw.replaceAll('\r\n', '\n').split('\n')) {
    if (line.startsWith('event:')) event = line.slice(6).trim();
    else if (line.startsWith('data:')) data.push(line.slice(5).trimStart());
  }
  return { event, data: data.join('\n') };
}

export class StreamCursor {
  private lastHeight: number | null = null;
  private seenHeight: number | null = null;
  private readonly seen = new Set<string>();

  fromHeight(initialTip: number): number {
    return this.lastHeight === null ? Math.max(1, initialTip) : Math.max(1, this.lastHeight);
  }

  accept(raw: string, selectedChain: string): AppChainMessage | null {
    const frame = parseSseFrame(raw);
    if (frame.event === 'heartbeat') return null;
    if (frame.event !== 'app-message' || !frame.data) return null;
    let message: AppChainMessage;
    try { message = JSON.parse(frame.data) as AppChainMessage; } catch { return null; }
    if (!Number.isSafeInteger(message.height) || message.height < 1
      || !Number.isSafeInteger(message.index) || message.index < 0) return null;
    if (this.seenHeight === null || message.height > this.seenHeight) {
      this.seen.clear();
      this.seenHeight = message.height;
    }
    if (this.lastHeight === null || message.height > this.lastHeight) this.lastHeight = message.height;
    const key = `${message.chainId || selectedChain}:${message.height}:${message.index}`;
    if (this.seen.has(key)) return null;
    this.seen.add(key);
    return message;
  }

  reset(): void {
    this.lastHeight = null;
    this.seenHeight = null;
    this.seen.clear();
  }
}
