import type { ArchiveDatasetStatus, ArchiveHistoryStatus } from '../api/types';

export type ArchiveState = 'DISABLED' | 'UNAVAILABLE' | 'CATCHING_UP' | 'READY' | 'UNHEALTHY';

export function archiveState(history: ArchiveHistoryStatus | undefined): ArchiveState {
  if (!history?.enabled) return 'DISABLED';
  if (!history.available) return 'UNAVAILABLE';
  // Health first: a degraded backend that also cannot serve the dataset section
  // must not be downgraded to the same state as a merely saturated reader pool.
  if (history.health?.status && history.health.status !== 'HEALTHY') return 'UNHEALTHY';
  // Dataset state could not be read at all (typically reader/permit saturation).
  // Reporting READY off an empty dataset map would be the opposite of the truth.
  if (history.datasetsUnavailable) return 'UNAVAILABLE';
  // A worker can be parked FAILED, or have had a mutation actually fail, while
  // the backend is still healthy and a live dataset still reports ready=true.
  // Readiness must not be evaluated before that is surfaced.
  if (Object.values(history.datasets ?? {}).some((dataset) => dataset.enabled && failedWorker(dataset))) {
    return 'UNHEALTHY';
  }
  if (Object.values(history.datasets ?? {}).some((dataset) => dataset.enabled && !dataset.ready)) {
    return 'CATCHING_UP';
  }
  return 'READY';
}

/** Mirrors ArchiveWorkerStatus.healthyState(): only DEGRADED and FAILED are failures. */
export function failedWorker(dataset: ArchiveDatasetStatus): boolean {
  return Object.values(dataset.workers ?? {}).some(
    (worker) => worker.state === 'FAILED' || worker.state === 'DEGRADED'
  );
}

/** Worker states worth showing next to a dataset row, most severe first. */
export function failedWorkerStates(dataset: ArchiveDatasetStatus): string[] {
  return Object.values(dataset.workers ?? {})
    .map((worker) => worker.state)
    .filter((state): state is string => state === 'FAILED' || state === 'DEGRADED')
    .sort((left, right) => (left === right ? 0 : left === 'FAILED' ? -1 : 1));
}

export function archiveDatasets(history: ArchiveHistoryStatus | undefined): Array<[string, ArchiveDatasetStatus]> {
  return Object.entries(history?.datasets ?? {}).sort(([left], [right]) => left.localeCompare(right));
}

export function coverageLabel(dataset: ArchiveDatasetStatus): string {
  const ranges = dataset.coverage?.completeRanges ?? [];
  if (ranges.length === 0) return 'none committed';
  return ranges.map((range) => `${range.startInclusive ?? '?'}–${range.endInclusive ?? '?'}`).join(', ');
}
