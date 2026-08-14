import type { NodeStatus } from '../api/types';

export function isLocalProducer(status: NodeStatus | null): boolean {
  return !!status && !status.upstreamActivePeer && String(status.upstreamMode ?? '').startsWith('disabled');
}

export function syncProgress(status: NodeStatus | null): number {
  if (!status || isLocalProducer(status)) return status ? 100 : 0;
  const local = Number(status.localTipBlockNumber ?? 0);
  const remote = Number(status.remoteTipBlockNumber ?? local);
  return remote > 0 ? Math.min(100, local / remote * 100) : 0;
}
