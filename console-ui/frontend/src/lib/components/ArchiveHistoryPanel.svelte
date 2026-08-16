<script lang="ts">
  import MetricCard from './MetricCard.svelte';
  import MetricRow from './MetricRow.svelte';
  import CopyValue from './CopyValue.svelte';
  import type { ArchiveHistoryStatus, ArchiveWorkerStatus } from '$lib/api/types';
  import { archiveDatasets, archiveState, coverageLabel } from '$lib/status/archive';

  let { history } = $props<{ history?: ArchiveHistoryStatus }>();

  const n = (value: unknown) => Number.isFinite(Number(value)) ? Number(value) : 0;
  const fmt = (value: unknown) => value === null || value === undefined ? '-' : n(value).toLocaleString();
  const when = (value: string | undefined) => {
    if (!value) return '-';
    const date = new Date(value);
    return Number.isNaN(date.getTime()) ? value : date.toLocaleString();
  };
  const bytes = (value: unknown) => {
    const amount = n(value);
    if (amount >= 1024 * 1024 * 1024) return `${(amount / (1024 * 1024 * 1024)).toFixed(1)} GiB`;
    if (amount >= 1024 * 1024) return `${(amount / (1024 * 1024)).toFixed(1)} MiB`;
    if (amount >= 1024) return `${(amount / 1024).toFixed(1)} KiB`;
    return `${amount.toLocaleString()} B`;
  };
  const stateTone = (state: string) => state === 'READY' ? 'badge-ok'
    : state === 'CATCHING_UP' ? 'badge-warn' : state === 'DISABLED' ? '' : 'badge-bad';
  const workerRows = (workers: Record<string, ArchiveWorkerStatus> | undefined) => Object.entries(workers ?? {});
</script>

{#if !history}
  <section class="card p-5">
    <h2 class="m-0 text-base font-semibold">History status unavailable</h2>
    <p class="mb-0 mt-2 text-sm text-slate-400">This node does not expose archive status. Upgrade the node or verify the API base.</p>
  </section>
{:else}
  {@const state = archiveState(history)}
  <div class="mb-4 flex flex-wrap items-center justify-between gap-3">
    <div>
      <p class="m-0 text-xs font-semibold uppercase tracking-[.18em] text-blue-400">Optional archive</p>
      <h2 class="mt-1 text-xl font-bold">History archive</h2>
      <p class="mb-0 mt-1 text-sm text-slate-400">Read-only archive and projection health.</p>
    </div>
    <span class="badge {stateTone(state)}">{state}</span>
  </div>

  {#if history.error || history.epochStagingError || history.maintenance?.error}
    <section class="mb-4 rounded-xl border border-rose-500/30 bg-rose-500/10 p-4 text-sm text-rose-200">
      <h3 class="m-0 text-sm font-semibold">Archive attention required</h3>
      {#if history.error}<p class="mb-0 mt-2"><strong>Archive:</strong> {history.error}</p>{/if}
      {#if history.epochStagingError}<p class="mb-0 mt-2"><strong>Epoch staging:</strong> {history.epochStagingError}</p>{/if}
      {#if history.maintenance?.error}<p class="mb-0 mt-2"><strong>Maintenance:</strong> {history.maintenance.error}</p>{/if}
    </section>
  {/if}

  <div class="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
    <MetricCard title="Archive state" subtitle="Backend and safety windows">
      <MetricRow label="Enabled / available" value={`${history.enabled ? 'yes' : 'no'} / ${history.available ? 'yes' : 'no'}`} />
      <MetricRow label="Engine / hot store" value={`${history.engine ?? '-'} / ${history.hotStoreEngine ?? '-'}`} />
      <MetricRow label="Backend health" value={history.health?.status ?? '-'} />
      <MetricRow label="Observed" value={when(history.health?.observedAt)} />
      <MetricRow label="Finality / rollback" value={`${fmt(history.finalityBlocks)} / ${fmt(history.rollbackRetentionBlocks)} blocks`} />
      <MetricRow label="Generation" value={fmt(history.generation)} />
    </MetricCard>

    <MetricCard title="Worker" subtitle="Projection and decode settings">
      <MetricRow label="Parallelism" value={`${history.worker?.projectionParallelismEffective ?? '-'} (${history.worker?.projectionParallelismRequested ?? '-'})`} />
      <MetricRow label="Batch limits" value={`${fmt(history.worker?.maxBlocksPerBatch)} blocks / ${fmt(history.worker?.maxRowsPerBatch)} rows`} />
      <MetricRow label="Core catch-up pause" value={history.worker?.pauseBackfillDuringCoreCatchup ? 'enabled' : 'disabled'} />
      <MetricRow label="Pause lag threshold" value={`${fmt(history.worker?.bulkPauseCoreLagBlocks)} blocks`} />
      <MetricRow label="Decoded blocks" value={fmt(history.worker?.decodedBlocks)} />
      <MetricRow label="Decode cache hits" value={fmt(history.worker?.decodedBlockCacheHits)} />
    </MetricCard>

    <MetricCard title="Maintenance" subtitle="Bounded archive housekeeping">
      <MetricRow label="Interval" value={`${fmt(history.maintenance?.intervalSeconds)} seconds`} />
      <MetricRow label="Time limit" value={`${fmt(history.maintenance?.timeLimitSeconds)} seconds`} />
      <MetricRow label="Rewrite budget" value={bytes(history.maintenance?.maxBytesToRewrite)} />
      <MetricRow label="Last completed" value={when(history.maintenance?.lastCompletedAt)} />
    </MetricCard>
  </div>

  <section class="card mt-4 overflow-hidden">
    <div class="flex flex-wrap items-center justify-between gap-3 border-b border-slate-700/40 px-4 py-3">
      <div><h2 class="m-0 text-sm font-semibold">Finalized consistency watermark</h2>
        <p class="mb-0 mt-1 text-xs text-slate-500">Safe common range across enabled block datasets.</p></div>
      <span class="badge {history.finalizedConsistency?.available ? 'badge-ok' : 'badge-warn'}">
        {history.finalizedConsistency?.available ? 'AVAILABLE' : 'UNAVAILABLE'}
      </span>
    </div>
    {#if history.finalizedConsistency?.available}
      <div class="grid gap-3 p-4 text-sm md:grid-cols-2 xl:grid-cols-4">
        <div><div class="text-xs text-slate-500">Complete range</div><div class="mt-1 font-mono">{fmt(history.finalizedConsistency.fromBlock)}–{fmt(history.finalizedConsistency.toBlock)}</div></div>
        <div><div class="text-xs text-slate-500">As-of block / slot</div><div class="mt-1 font-mono">{fmt(history.finalizedConsistency.asOf?.blockNumber)} / {fmt(history.finalizedConsistency.asOf?.slot)}</div></div>
        <div><div class="text-xs text-slate-500">Generation</div><div class="mt-1 font-mono">{fmt(history.finalizedConsistency.generation)}</div></div>
        <div><div class="text-xs text-slate-500">Projection versions</div><div class="mt-1 font-mono">{Object.entries(history.finalizedConsistency.projectionVersions ?? {}).map(([name, version]) => `${name} v${version}`).join(', ') || '-'}</div></div>
      </div>
    {:else}
      <p class="m-0 p-4 text-sm text-slate-400">{history.finalizedConsistency?.detail ?? 'No common finalized range is currently available.'}</p>
    {/if}
  </section>

  <section class="card mt-4 overflow-hidden">
    <div class="border-b border-slate-700/40 px-4 py-3">
      <h2 class="m-0 text-sm font-semibold">Datasets</h2>
      <p class="mb-0 mt-1 text-xs text-slate-500">Coverage is committed cold/archive coverage; LIVE is the near-tip projection.</p>
    </div>
    <div class="overflow-x-auto">
      <table class="w-full min-w-[1120px] text-left text-xs">
        <thead class="bg-slate-950/50 text-slate-500"><tr>
          <th class="p-3">Dataset</th><th class="p-3">State</th><th class="p-3">Start / retention</th>
          <th class="p-3">Coverage</th><th class="p-3">Projection</th><th class="p-3">Workers</th><th class="p-3">Subjects</th>
        </tr></thead>
        <tbody class="divide-y divide-slate-800">
          {#each archiveDatasets(history) as [name, dataset]}
            <tr>
              <td class="p-3 font-mono text-slate-200">{name}</td>
              <td class="p-3"><span class="badge {dataset.enabled ? dataset.ready ? 'badge-ok' : 'badge-warn' : ''}">
                {dataset.enabled ? dataset.ready ? 'LIVE' : dataset.phase === 'catching_up' ? 'CATCHING UP' : 'ENABLED' : 'DISABLED'}
              </span></td>
              <td class="p-3">{dataset.startMode ?? '-'} / {dataset.retentionEpochs ?? '-'} epochs</td>
              <td class="p-3 font-mono">{dataset.enabled ? coverageLabel(dataset) : '-'}</td>
              <td class="p-3">{dataset.coverage?.projectionVersion == null ? '-' : `v${dataset.coverage.projectionVersion} · r${dataset.coverage.revision ?? '-'}`}</td>
              <td class="p-3">
                {#each workerRows(dataset.workers) as [track, worker]}
                  <div class="mb-1 last:mb-0"><span class="font-semibold text-slate-300">{track}</span> {worker.state ?? '-'} · #{fmt(worker.coordinate)} · lag {fmt(worker.lag)}<br />
                    <span class="text-slate-500">{worker.detail ?? ''}</span></div>
                {:else}<span class="text-slate-500">-</span>{/each}
              </td>
              <td class="p-3">{Object.entries(dataset.subjects ?? {}).filter(([, enabled]) => enabled).map(([subject]) => subject).join(', ') || '-'}</td>
            </tr>
          {:else}<tr><td colspan="7" class="p-8 text-center text-slate-500">No archive datasets reported.</td></tr>{/each}
        </tbody>
      </table>
    </div>
  </section>

  <details class="card mt-4 p-4">
    <summary class="cursor-pointer text-sm font-semibold text-slate-200">Operator details</summary>
    <div class="mt-4 grid gap-3 text-sm md:grid-cols-2">
      <div><div class="text-xs text-slate-500">History directory</div><CopyValue value={history.directory} width={58} label="history directory" /></div>
      <div><div class="text-xs text-slate-500">Finalized block hash</div><CopyValue value={history.finalizedConsistency?.asOf?.blockHash} width={58} label="finalized block hash" /></div>
    </div>
  </details>
{/if}
