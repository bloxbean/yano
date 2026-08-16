import type { ArchiveDatasetStatus, ArchiveHistoryStatus } from '../api/types';

export type ArchiveState = 'DISABLED' | 'UNAVAILABLE' | 'CATCHING_UP' | 'READY' | 'UNHEALTHY';

export function archiveState(history: ArchiveHistoryStatus | undefined): ArchiveState {
  if (!history?.enabled) return 'DISABLED';
  if (!history.available) return 'UNAVAILABLE';
  if (history.health?.status && history.health.status !== 'HEALTHY') return 'UNHEALTHY';
  if (Object.values(history.datasets ?? {}).some((dataset) => dataset.enabled && !dataset.ready)) {
    return 'CATCHING_UP';
  }
  return 'READY';
}

export function archiveDatasets(history: ArchiveHistoryStatus | undefined): Array<[string, ArchiveDatasetStatus]> {
  return Object.entries(history?.datasets ?? {}).sort(([left], [right]) => left.localeCompare(right));
}

export function coverageLabel(dataset: ArchiveDatasetStatus): string {
  const ranges = dataset.coverage?.completeRanges ?? [];
  if (ranges.length === 0) return 'none committed';
  return ranges.map((range) => `${range.startInclusive ?? '?'}–${range.endInclusive ?? '?'}`).join(', ');
}
