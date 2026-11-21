export function numberValue(value: unknown, fallback = 0): number {
  const result = Number(value);
  return Number.isFinite(result) ? result : fallback;
}

export function stringValue(value: unknown, fallback = '-'): string {
  return value === null || value === undefined || value === '' ? fallback : String(value);
}

export function objectValue(value: unknown): Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
    ? value as Record<string, unknown> : {};
}

export function shortHash(value: unknown, width = 20): string {
  const text = stringValue(value);
  if (text.length <= width) return text;
  const side = Math.max(4, Math.floor((width - 1) / 2));
  return `${text.slice(0, side)}…${text.slice(-side)}`;
}
