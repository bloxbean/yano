export type CompactSample = [number, ...(number | null)[]];

interface StoredHistory {
  version: 1;
  identity: string;
  samples: CompactSample[];
}

const PREFIX = 'yano.console.history.v1.';
export const MAX_SAMPLES = 720;
export const MAX_SERIALIZED_BYTES = 512 * 1024;

export class SessionHistory {
  private readonly key: string;
  private samples: CompactSample[] = [];
  private lastPersistedAt = 0;

  constructor(private readonly identity: string, private readonly storage: Storage = sessionStorage) {
    this.key = `${PREFIX}${hash(identity)}`;
    this.samples = this.restore();
  }

  values(): CompactSample[] { return this.samples.map((sample) => [...sample] as CompactSample); }

  append(sample: CompactSample, now = Date.now()): void {
    if (!validSample(sample)) return;
    this.samples.push([...sample] as CompactSample);
    if (this.samples.length > MAX_SAMPLES) this.samples.splice(0, this.samples.length - MAX_SAMPLES);
    if (now - this.lastPersistedAt >= 10_000) this.persist(now);
  }

  persist(now = Date.now()): void {
    let envelope: StoredHistory = { version: 1, identity: this.identity, samples: this.samples };
    let serialized = JSON.stringify(envelope);
    while (serialized.length > MAX_SERIALIZED_BYTES && envelope.samples.length > 1) {
      envelope = { ...envelope, samples: envelope.samples.slice(Math.ceil(envelope.samples.length / 10)) };
      serialized = JSON.stringify(envelope);
    }
    this.samples = envelope.samples;
    try { this.storage.setItem(this.key, serialized); } catch { /* storage may be disabled or full */ }
    this.lastPersistedAt = now;
  }

  clear(): void {
    this.samples = [];
    this.storage.removeItem(this.key);
  }

  private restore(): CompactSample[] {
    try {
      const raw = this.storage.getItem(this.key);
      if (!raw || raw.length > MAX_SERIALIZED_BYTES) return [];
      const parsed = JSON.parse(raw) as Partial<StoredHistory>;
      if (parsed.version !== 1 || parsed.identity !== this.identity || !Array.isArray(parsed.samples)) return [];
      return parsed.samples.filter(validSample).slice(-MAX_SAMPLES).map((sample) => [...sample] as CompactSample);
    } catch {
      this.storage.removeItem(this.key);
      return [];
    }
  }
}

function validSample(value: unknown): value is CompactSample {
  return Array.isArray(value) && value.length >= 2 && Number.isFinite(value[0])
    && value.slice(1).every((item) => item === null || Number.isFinite(item));
}

function hash(value: string): string {
  let result = 2166136261;
  for (let i = 0; i < value.length; i++) {
    result ^= value.charCodeAt(i);
    result = Math.imul(result, 16777619);
  }
  return (result >>> 0).toString(36);
}
