<script lang="ts">
  import { onMount } from 'svelte';
  import LineChart from '$lib/components/LineChart.svelte';
  import MetricCard from '$lib/components/MetricCard.svelte';
  import MetricRow from '$lib/components/MetricRow.svelte';
  import { apiFailureMessage, resolveApiBase, YanoApi } from '$lib/api/client';
  import type { NodeConfig, NodePeers, NodeStatus, StorageStatus } from '$lib/api/types';
  import { SessionHistory, type CompactSample } from '$lib/telemetry/history';
  import { createPoller, type Poller } from '$lib/telemetry/poller';
  import { isLocalProducer, syncProgress } from '$lib/status/view-model';

  let status: NodeStatus | null = null;
  let peers: NodePeers | null = null;
  let storage: StorageStatus | null = null;
  let config: NodeConfig | null = null;
  let apiBase = '/api/v1';
  let samples: CompactSample[] = [];
  let lastUpdated = '';
  let requestMs = 0;
  let error = '';
  let previousStatus: NodeStatus | null = null;
  let history: SessionHistory | null = null;
  let poller: Poller | null = null;

  const n = (value: unknown) => Number.isFinite(Number(value)) ? Number(value) : 0;
  const fmt = (value: unknown) => value === null || value === undefined ? '-' : n(value).toLocaleString();
  const metric = (value: unknown, suffix = '') => value === null || value === undefined
    ? '-' : `${n(value).toLocaleString(undefined, { maximumFractionDigits: 2 })}${suffix}`;
  const bytes = (value: unknown) => {
    const amount = n(value);
    if (amount >= 1024 * 1024) return `${(amount / (1024 * 1024)).toFixed(1)} MB`;
    if (amount >= 1024) return `${(amount / 1024).toFixed(1)} KB`;
    return `${amount.toLocaleString()} B`;
  };
  const short = (value: unknown) => {
    const text = String(value ?? '-');
    return text.length > 28 ? `${text.slice(0, 12)}…${text.slice(-10)}` : text;
  };

  $: localBlock = n(status?.localTipBlockNumber);
  $: remoteBlock = status?.remoteTipBlockNumber == null ? localBlock : n(status.remoteTipBlockNumber);
  $: gap = Math.max(0, remoteBlock - localBlock);
  $: localProducer = isLocalProducer(status);
  $: syncPercent = syncProgress(status);
  $: utxo = storage?.utxo ?? {};
  $: storageMetrics = utxo.metrics ?? {};
  $: prune = utxo.prune ?? {};
  $: cfEstimates = utxo.cfEstimates ?? storage?.cfEstimates ?? {};
  $: peerRows = (peers?.peers ?? []).slice(0, 250);
  $: chartGap = [samples.map((sample) => sample[1] ?? null)];
  $: chartPeers = [2, 3, 4].map((index) => samples.map((sample) => sample[index] ?? null));
  $: chartMempool = [5, 6].map((index) => samples.map((sample) => sample[index] ?? null));
  $: chartTx = [7, 8, 9].map((index) => samples.map((sample) => sample[index] ?? null));
  $: chartUtxo = [samples.map((sample) => sample[10] ?? null)];

  onMount(() => {
    let disposed = false;
    void (async () => {
      apiBase = await resolveApiBase();
      const api = new YanoApi(apiBase);
      try {
        config = await api.config();
        const identity = `${apiBase}|${config.protocolMagic}|node`;
        history = new SessionHistory(identity);
        samples = history.values();
      } catch (cause) {
        error = apiFailureMessage(cause, 'Unable to load node identity');
      }
      if (disposed) return;
      poller = createPoller(async (signal) => {
        const started = performance.now();
        try {
          const [nextStatus, nextPeers, nextStorage] = await Promise.all([
            api.status(signal), api.peers(signal), api.storage(signal)
          ]);
          addSample(nextStatus, nextStorage);
          status = nextStatus;
          peers = nextPeers;
          storage = nextStorage;
          requestMs = Math.round(performance.now() - started);
          lastUpdated = new Date().toLocaleTimeString();
          error = '';
        } catch (cause) {
          if (!(cause instanceof DOMException && cause.name === 'AbortError')) {
            error = apiFailureMessage(cause, 'Status request failed');
          }
        }
      });
      poller.start();
    })();
    return () => {
      disposed = true;
      poller?.stop();
      history?.persist();
    };
  });

  function addSample(next: NodeStatus, nextStorage: StorageStatus): void {
    if (!history) return;
    const now = Date.now();
    const prior = samples.at(-1)?.[0] ?? now;
    const countersReset = previousStatus && (
      n(next.txDiffusionOutboundForwarded) < n(previousStatus.txDiffusionOutboundForwarded)
      || n(next.txDiffusionInboundTxBodiesAccepted) < n(previousStatus.txDiffusionInboundTxBodiesAccepted)
      || n(next.txDiffusionServedTxs) < n(previousStatus.txDiffusionServedTxs));
    if (now - prior > 30_000 || countersReset) {
      history.append([now, null, null, null, null, null, null, null, null, null, null]);
    }
    const nextLocal = n(next.localTipBlockNumber);
    const nextRemote = next.remoteTipBlockNumber == null ? nextLocal : n(next.remoteTipBlockNumber);
    const delta = previousStatus ? [
      Math.max(0, n(next.txDiffusionOutboundForwarded) - n(previousStatus.txDiffusionOutboundForwarded)),
      Math.max(0, n(next.txDiffusionInboundTxBodiesAccepted) - n(previousStatus.txDiffusionInboundTxBodiesAccepted)),
      Math.max(0, n(next.txDiffusionServedTxs) - n(previousStatus.txDiffusionServedTxs))
    ] : [0, 0, 0];
    history.append([
      now,
      Math.max(0, nextRemote - nextLocal),
      n(next.relayInboundConnectionCount),
      n(next.relayOutboundConnectionCount),
      n(next.relayHotPeerCount),
      n(next.mempoolSize),
      n(next.mempoolBytes) / 1024,
      delta[0], delta[1], delta[2],
      n(nextStorage.utxo?.lagBlocks)
    ]);
    samples = history.values();
    previousStatus = next;
  }

  function capabilities(peer: NonNullable<NodePeers['peers']>[number]): string {
    return [peer.chainSync && 'cs', peer.blockFetch && 'bf', peer.txSubmission && 'tx',
      peer.peerSharing && 'ps', peer.query && 'q'].filter(Boolean).join(' ');
  }
</script>

<svelte:head><title>Yano · Node Status</title></svelte:head>

<div class="mb-4 flex flex-wrap items-center justify-between gap-3">
  <div>
    <p class="m-0 text-xs font-semibold uppercase tracking-[.18em] text-blue-400">L1 runtime</p>
    <h1 class="mt-1 text-2xl font-bold">Node status</h1>
  </div>
  <div class="flex items-center gap-2 text-xs text-slate-500">
    <span>{lastUpdated ? `Updated ${lastUpdated}` : 'Waiting for first sample'}</span>
    <span class="rounded-md border border-slate-700 px-2 py-1 font-mono">{requestMs || '-'} ms</span>
    <span class="badge {error ? 'badge-bad' : status?.running && !status.runtimeDegraded ? 'badge-ok' : 'badge-warn'}">
      {error ? 'ERROR' : status?.running ? status.runtimeDegraded ? 'DEGRADED' : 'RUNNING' : 'STOPPED'}
    </span>
  </div>
</div>

{#if error}
  <div class="mb-4 rounded-xl border border-rose-500/30 bg-rose-500/10 p-3 text-sm text-rose-300">{error}</div>
{/if}

<section class="card overflow-hidden p-5">
  <div class="flex flex-wrap items-end justify-between gap-6">
    <div>
      <div class="text-xs font-semibold uppercase tracking-[.18em] text-slate-500">
        {localProducer ? 'Local Producer' : 'Sync Progress'}
      </div>
      <div class="mt-2 text-4xl font-bold text-blue-300">{localProducer ? fmt(localBlock) : `${syncPercent.toFixed(1)}%`}</div>
      <p class="mb-0 mt-2 text-sm text-slate-400">{status?.statusMessage ?? 'Loading node status…'}</p>
    </div>
    <div class="grid grid-cols-3 gap-6 text-right">
      <div><small class="text-slate-500">Local Block</small><div class="font-mono">{fmt(localBlock)}</div></div>
      <div><small class="text-slate-500">Remote Block</small><div class="font-mono">{localProducer ? 'n/a' : fmt(remoteBlock)}</div></div>
      <div><small class="text-slate-500">Gap</small><div class="font-mono">{localProducer ? 'n/a' : fmt(gap)}</div></div>
    </div>
  </div>
  <progress class="mt-4 h-2 w-full overflow-hidden rounded-full accent-blue-500" max="100" value={syncPercent}
            aria-label="Sync progress"></progress>
  <div class="mt-3"><span class="badge {localProducer || status?.initialSyncComplete ? 'badge-ok' : 'badge-warn'}">
    {localProducer ? 'PRODUCING' : status?.initialSyncComplete ? 'IN SYNC' : 'SYNCING'}
  </span></div>
</section>

<div class="section-title">Node</div>
<div class="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
  <MetricCard title="Chain sync" subtitle="Local and upstream progress">
    <MetricRow label="Mode" value={`${status?.syncMode ?? '-'} / ${status?.initialSyncComplete ? 'steady' : 'initial'}`} />
    <MetricRow label="Local Tip" value={`${fmt(status?.localTipBlockNumber)} / slot ${fmt(status?.localTipSlot)}`} />
    <MetricRow label="Remote Tip" value={`${fmt(status?.remoteTipBlockNumber)} / slot ${fmt(status?.remoteTipSlot)}`} />
    <MetricRow label="Gap" value={`${fmt(gap)} blocks`} />
    <MetricRow label="Processed" value={fmt(status?.blocksProcessed)} />
  </MetricCard>
  <MetricCard title="Upstream" subtitle="Selected sync source">
    <MetricRow label="Mode" value={status?.upstreamMode} />
    <MetricRow label="Active Peer" value={status?.upstreamActivePeer} />
    <MetricRow label="Advertised" value={`${status?.relayAdvertisedHost ?? '-'}:${status?.relayAdvertisedPort ?? '-'}`} />
    <MetricRow label="Connections" value={`in ${fmt(status?.relayInboundConnectionCount)} / out ${fmt(status?.relayOutboundConnectionCount)} / est ${fmt(status?.relayEstablishedConnectionCount)}`} />
    <MetricRow label="Failures" value={`out ${fmt(status?.relayFailedOutboundConnections)} / rejected ${fmt(status?.relayRejectedInboundConnections)}`} />
  </MetricCard>
  <MetricCard title="Peer governor" subtitle="Bounded relay peer states">
    <MetricRow label="Known" value={`${fmt(status?.relayKnownPeerCount)} / target ${fmt(status?.relayGovernorTargetWarmPeers)} warm, ${fmt(status?.relayGovernorTargetHotPeers)} hot`} />
    <MetricRow label="Cold / Warm / Hot" value={`${fmt(status?.relayColdPeerCount)} / ${fmt(status?.relayWarmPeerCount)} / ${fmt(status?.relayHotPeerCount)}`} />
    <MetricRow label="Backoff / Quarantine" value={`${fmt(status?.relayBackoffPeerCount)} / ${fmt(status?.relayQuarantinedPeerCount)}`} />
    <MetricRow label="Sources" value={`boot ${fmt(status?.relayBootstrapPeerCount)} / gossip ${fmt(status?.relayGossipPeerCount)} / ledger ${fmt(status?.relayLedgerPeerCount)} / in ${fmt(status?.relayInboundPeerCount)}`} />
    <MetricRow label="Sharable" value={fmt(status?.relaySharablePeerCount)} />
  </MetricCard>
  <MetricCard title="Mempool" subtitle="Admission and capacity">
    <MetricRow label="Transactions" value={fmt(status?.mempoolSize)} />
    <MetricRow label="Bytes" value={bytes(status?.mempoolBytes)} />
    <MetricRow label="Capacity" value={`${fmt(status?.mempoolMaxTxs)} tx / ${bytes(status?.mempoolMaxBytes)}`} />
    <MetricRow label="TTL" value={`${fmt(status?.mempoolTtlSeconds)}s`} />
    <MetricRow label="Admission" value={status?.mempoolAccepting ? 'accepting' : 'paused'} />
  </MetricCard>
  <MetricCard title="Transaction diffusion" subtitle="N2N transaction flow">
    <MetricRow label="Mode" value={`${status?.txDiffusionMode ?? '-'} / ${status?.upstreamTxForwarding ?? '-'}`} />
    <MetricRow label="Forwarded / Suppressed" value={`${fmt(status?.txDiffusionOutboundForwarded)} / ${fmt(status?.txDiffusionOutboundSuppressed)}`} />
    <MetricRow label="Tx IDs" value={`${fmt(status?.txDiffusionInboundTxIdsRequested)} req / ${fmt(status?.txDiffusionInboundTxIdsIgnored)} ignored / ${fmt(status?.txDiffusionInboundTxIdsRejected)} skipped`} />
    <MetricRow label="Tx Bodies" value={`${fmt(status?.txDiffusionInboundTxBodiesAccepted)} admitted / ${fmt(status?.txDiffusionInboundTxBodiesRejected)} rejected / ${fmt(status?.txDiffusionInboundTxBodiesIgnored)} ignored`} />
    <MetricRow label="Served" value={`${fmt(status?.txDiffusionServedTxs)} / ${bytes(status?.txDiffusionServedBytes)}`} />
    <MetricRow label="In Flight" value={`${fmt(status?.txDiffusionInFlightTxs)} / ${bytes(status?.txDiffusionInFlightBytes)}`} />
  </MetricCard>
  <MetricCard title="Validation & storage" subtitle="Header and UTXO health">
    <MetricRow label="Header Validation" value={status?.upstreamValidationLevel} />
    <MetricRow label="Accepted / Rejected" value={`${fmt(status?.upstreamValidationAcceptedHeaders)} / ${fmt(status?.upstreamValidationRejectedHeaders)}`} />
    <MetricRow label="UTXO Store" value={`${utxo.enabled ? 'enabled' : 'disabled'} / ${utxo.store ?? '-'}`} />
    <MetricRow label="UTXO Lag" value={fmt(utxo.lagBlocks)} />
    <MetricRow label="Apply p95" value={metric(storageMetrics['apply.ms.p95'], ' ms')} />
  </MetricCard>
</div>

<div class="section-title">Storage & UTXO</div>
<div class="grid gap-4 md:grid-cols-2 xl:grid-cols-4">
  <MetricCard title="Chain tip"><MetricRow label="Block" value={fmt(storage?.chain?.blockNumber)} />
    <MetricRow label="Slot" value={fmt(storage?.chain?.slot)} /><MetricRow label="Hash" value={short(storage?.chain?.blockHash)} />
  </MetricCard>
  <MetricCard title="UTXO state"><MetricRow label="Status" value={utxo.enabled ? 'ENABLED' : 'DISABLED'} />
    <MetricRow label="Store" value={utxo.store} /><MetricRow label="Last Applied" value={`${fmt(utxo.lastAppliedBlock)} / slot ${fmt(utxo.lastAppliedSlot)}`} />
  </MetricCard>
  <MetricCard title="Apply performance"><MetricRow label="Average" value={metric(storageMetrics['apply.ms.avg'], ' ms')} />
    <MetricRow label="p95" value={metric(storageMetrics['apply.ms.p95'], ' ms')} />
    <MetricRow label="Created / Spent" value={`${metric(storageMetrics['apply.created.last'])} / ${metric(storageMetrics['apply.spent.last'])}`} />
  </MetricCard>
  <MetricCard title="Throughput"><MetricRow label="Blocks/sec" value={metric(storageMetrics['throughput.blocksPerSec'])} />
    <MetricRow label="Block Last" value={bytes(storageMetrics['block.size.last'])} />
    <MetricRow label="Block Avg" value={bytes(storageMetrics['block.size.avg'])} />
  </MetricCard>
  <div class="md:col-span-2"><MetricCard title="Pruning">
    <MetricRow label="Depth" value={fmt(prune['pruneDepth'])} /><MetricRow label="Rollback Window" value={fmt(prune['rollbackWindow'])} />
    <MetricRow label="Batch Size" value={fmt(prune['pruneBatchSize'])} /><MetricRow label="Delta Cursor" value={short(prune['deltaCursorKey'])} />
    <MetricRow label="Spent Cursor" value={short(prune['spentCursorKey'])} />
  </MetricCard></div>
  <div class="md:col-span-2"><MetricCard title="Column-family estimates">
    {#each Object.entries(cfEstimates) as [name, value]}
      <MetricRow label={name.replace('.estimateNumKeys', '')} value={fmt(value)} />
    {:else}<p class="text-sm text-slate-500">No estimates reported.</p>{/each}
  </MetricCard></div>
</div>

<div class="section-title">Trends · browser session · up to 1 hour</div>
<div class="grid gap-4 md:grid-cols-2">
  <MetricCard title="Sync gap"><LineChart series={chartGap} label="Sync gap history" /></MetricCard>
  <MetricCard title="Peers" subtitle="inbound / outbound / hot"><LineChart series={chartPeers} colors={['#10b981', '#60a5fa', '#f59e0b']} label="Peer history" /></MetricCard>
  <MetricCard title="Mempool" subtitle="transactions / KiB"><LineChart series={chartMempool} colors={['#a78bfa', '#14b8a6']} label="Mempool history" /></MetricCard>
  <MetricCard title="Transaction flow" subtitle="forwarded / inbound / served"><LineChart series={chartTx} colors={['#60a5fa', '#10b981', '#f59e0b']} label="Transaction flow history" /></MetricCard>
  <div class="md:col-span-2"><MetricCard title="UTXO lag"><LineChart series={chartUtxo} colors={['#22d3ee']} label="UTXO lag history" /></MetricCard></div>
</div>

<div class="section-title">Peers</div>
<section class="card overflow-hidden">
  <div class="flex items-center justify-between border-b border-slate-700/40 px-4 py-3">
    <h2 class="text-sm font-semibold">Peer inventory</h2>
    <span class="text-xs text-slate-500">{peerRows.length} peers · {fmt(peers?.establishedConnectionCount)} established · {fmt(peers?.knownPeerCount)} known</span>
  </div>
  <div class="overflow-x-auto">
    <table class="w-full min-w-[900px] border-collapse text-left text-xs">
      <thead class="bg-slate-950/50 text-slate-500"><tr>
        <th class="p-3">Endpoint</th><th class="p-3">Governor</th><th class="p-3">Connection</th>
        <th class="p-3">Direction</th><th class="p-3">Source</th><th class="p-3">Trust</th>
        <th class="p-3">Capabilities</th><th class="p-3">Last seen</th><th class="p-3">Reason</th>
      </tr></thead>
      <tbody class="divide-y divide-slate-800">
        {#each peerRows as peer}
          <tr class={peer.active ? 'bg-emerald-500/5' : ''}>
            <td class="p-3 font-mono text-slate-200">{peer.endpoint ?? peer.id ?? '-'}</td>
            <td class="p-3">{peer.governorState ?? '-'}</td><td class="p-3">{peer.connectionState ?? '-'}</td>
            <td class="p-3">{peer.direction ?? '-'}</td><td class="p-3">{peer.source ?? '-'}</td>
            <td class="p-3">{peer.trusted == null ? '-' : peer.trusted ? 'trusted' : 'untrusted'}</td>
            <td class="p-3 font-mono">{capabilities(peer) || '-'}</td>
            <td class="p-3">{peer.lastSeenMillis ? new Date(peer.lastSeenMillis).toLocaleTimeString() : '-'}</td>
            <td class="p-3 text-slate-500">{peer.connectionReason ?? ''}</td>
          </tr>
        {:else}<tr><td colspan="9" class="p-8 text-center text-slate-500">No peers reported</td></tr>{/each}
      </tbody>
    </table>
  </div>
</section>

<footer class="mt-6 flex flex-wrap gap-2 text-xs text-slate-600">
  <span>Yano node dashboard</span><span>·</span><span>auto-refresh every 5s</span><span>·</span>
  <span class="font-mono">{apiBase}</span><span>·</span><span>{config?.network ?? config?.protocolMagic ?? '-'}</span>
</footer>
