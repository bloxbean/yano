import type { CompactSample } from './history';
import type { PrometheusSeries } from './prometheus';

export function alignedSamples(series: ReadonlyArray<PrometheusSeries>, columns: number): CompactSample[] {
  const rows = new Map<number, Array<number | null>>();
  series.slice(0, columns).forEach((entry, column) => {
    entry.points.forEach(([time, value]) => {
      const row = rows.get(time) ?? Array.from({ length: columns }, () => null);
      row[column] = value;
      rows.set(time, row);
    });
  });
  return [...rows.entries()].sort(([left], [right]) => left - right)
    .map(([time, values]) => [time, ...values] as CompactSample);
}

export function columnSamples(columns: ReadonlyArray<PrometheusSeries | undefined>): CompactSample[] {
  return alignedSamples(columns.map((entry) => entry ?? { labels: {}, points: [] }), columns.length);
}

export function mergeSamples(durable: CompactSample[], session: CompactSample[], max = 720): CompactSample[] {
  const byTime = new Map<number, CompactSample>();
  durable.forEach((sample) => byTime.set(sample[0], [...sample] as CompactSample));
  session.forEach((sample) => byTime.set(sample[0], [...sample] as CompactSample));
  return [...byTime.values()].sort((left, right) => left[0] - right[0]).slice(-max);
}
