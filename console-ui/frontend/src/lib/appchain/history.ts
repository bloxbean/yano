import type { AppChainStatus } from '$lib/api/types';
import type { CompactSample } from '$lib/telemetry/history';
import { numberValue, objectValue } from './value';

export interface AppChainHistoryState {
  at: number;
  tip: number;
  submitted: number;
  received: number;
  relayed: number;
}

export interface AppChainSampleResult {
  discontinuity: boolean;
  sample: CompactSample;
  state: AppChainHistoryState;
}

export function appChainSample(status: AppChainStatus, previous: AppChainHistoryState | null,
                               at = Date.now()): AppChainSampleResult {
  const tip = numberValue(status.tipHeight);
  const submitted = numberValue(status.submitted);
  const received = numberValue(status.received);
  const relayed = numberValue(status.relayed);
  const anchor = objectValue(status.anchor);
  const countersReset = previous !== null && (tip < previous.tip || submitted < previous.submitted
    || received < previous.received || relayed < previous.relayed);
  const discontinuity = previous !== null && (at - previous.at > 30_000 || countersReset);
  const elapsedSeconds = previous ? Math.max(.001, (at - previous.at) / 1_000) : 1;
  const tipRate = previous && !countersReset ? Math.max(0, tip - previous.tip) / elapsedSeconds : 0;
  return {
    discontinuity,
    sample: [at, tipRate, numberValue(status.poolSize), numberValue(status.blockIntervalMs),
      numberValue(anchor.lagBlocks)],
    state: { at, tip, submitted, received, relayed }
  };
}
