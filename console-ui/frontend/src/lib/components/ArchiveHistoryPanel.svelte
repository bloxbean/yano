<script lang="ts">
  import MetricCard from './MetricCard.svelte';
  import MetricRow from './MetricRow.svelte';
  import CopyValue from './CopyValue.svelte';
  import type { ArchiveHistoryStatus, ProjectionCoverage, ProjectionWatermark } from '$lib/api/types';
  import { agoLabel, count, cursorStalled, datasetRows, humanizeDuration,
    NO_CURSOR_PROGRESS, parseArtifactContracts, projectionState, queryableRange,
    stalledForMillis, type CursorProgress } from '$lib/status/projection';

  let { history, coverage, watermark, error = '', progress = NO_CURSOR_PROGRESS } = $props<{
    history?: ArchiveHistoryStatus;
    coverage?: ProjectionCoverage;
    watermark?: ProjectionWatermark;
    error?: string;
    progress?: CursorProgress;
  }>();

  const state = $derived(projectionState(history, coverage));
  const range = $derived(queryableRange(coverage));
  const datasets = $derived(datasetRows(history, coverage, watermark));
  const contracts = $derived(parseArtifactContracts(coverage?.artifactContracts));
  const stillFor = $derived(stalledForMillis(progress));
  const stalled = $derived(cursorStalled(progress, coverage));

  const stateTone = (value: string) => value === 'READY' ? 'badge-ok'
    : value === 'CATCHING_UP' || value === 'AWAITING_GENESIS' ? 'badge-warn'
    : value === 'DISABLED' ? '' : 'badge-bad';

  // Irreproducible artifacts are the ones an operator must not lose; that has to read
  // differently from an artifact the node could rebuild.
  const durabilityTone = (value: string) => value === 'IRREPRODUCIBLE' ? 'badge-warn'
    : value === 'RECONSTRUCTIBLE' ? 'badge-ok' : '';

  const stateNarrative: Record<string, string> = {
    DISABLED: 'The archive is not enabled on this node.',
    UNAVAILABLE: 'The archive is enabled but its sink cannot serve reads right now.',
    UNHEALTHY: 'The sink is degraded. Commits may be failing; check the node log.',
    AWAITING_GENESIS: 'The genesis distribution is not durable yet, so no range can be claimed.',
    CATCHING_UP: 'No batch has committed yet. The archive exists but cannot answer queries.',
    READY: 'The archive can answer queries over its committed range.'
  };
</script>

<div class="mb-4 flex flex-wrap items-center justify-between gap-3">
  <div>
    <p class="m-0 text-xs font-semibold uppercase tracking-[.18em] text-blue-400">Optional archive</p>
    <h2 class="mt-1 text-xl font-bold">History archive</h2>
    <p class="mb-0 mt-1 text-sm text-slate-400">Canonical projection outbox · read-only.</p>
  </div>
  <span class="badge {stateTone(state)}">{state.replace('_', ' ')}</span>
</div>

{#if error}
  <section class="mb-4 rounded-xl border border-rose-500/30 bg-rose-500/10 p-4 text-sm text-rose-200">
    <h3 class="m-0 text-sm font-semibold">Archive status could not be read</h3>
    <p class="mb-0 mt-2">{error}</p>
  </section>
{/if}

{#if coverage?.error}
  <section class="mb-4 rounded-xl border border-rose-500/30 bg-rose-500/10 p-4 text-sm text-rose-200">
    <h3 class="m-0 text-sm font-semibold">The archive failed to start</h3>
    <p class="mb-0 mt-2 font-mono text-xs">{coverage.error}</p>
    <p class="mb-0 mt-2">
      The node is still serving; only the archive is down. Nothing is being committed, so the
      coverage below is not merely stale — it is absent.
    </p>
  </section>
{/if}

<p class="mb-4 text-sm text-slate-400">{stateNarrative[state]}</p>

{#if stalled}
  <section class="mb-4 rounded-xl border border-amber-500/30 bg-amber-500/10 p-4 text-sm text-amber-200">
    <h3 class="m-0 text-sm font-semibold">The committed cursor has stopped advancing</h3>
    <p class="mb-0 mt-2">
      No commit in {agoLabel(stillFor).replace(' ago', '')}, longer than the
      {humanizeDuration(coverage?.maxCommitLatency)} this archive reports as its own upper bound.
      The drain fails closed, so a batch it cannot complete is retried rather than skipped —
      a stall means every dataset is held at this block, not that one fell behind. Check the
      node log for a repeating failure.
    </p>
  </section>
{/if}

{#if state !== 'DISABLED'}
  <div class="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
    <MetricCard title="Coverage" subtitle="What the archive can answer for">
      <MetricRow label="Queryable blocks" value={range ?? 'nothing committed yet'} />
      <MetricRow label="Committed through" value={count(coverage?.queryableThroughBlock)} />
      <MetricRow label="Chain tip" value={count(coverage?.tipBlock)} />
      <MetricRow label="Behind tip" value={`${count(coverage?.blocksBehindTip)} blocks`} />
      <MetricRow label="Genesis captured"
                 value={coverage?.genesisCaptured === undefined ? '—' : coverage.genesisCaptured ? 'yes' : 'not yet'} />
      <MetricRow label="Sink health" value={coverage?.sinkHealth ?? '—'} />
      <MetricRow label="Cursor last moved" value={agoLabel(stillFor)} />
    </MetricCard>

    <MetricCard title="Freshness" subtitle="How long finality takes to become queryable">
      <MetricRow label="Max commit latency" value={humanizeDuration(coverage?.maxCommitLatency)} />
      <MetricRow label="Source" value={history?.source ?? '—'} />
      <MetricRow label="Sections projected" value={count(coverage?.sections?.length)} />
      <MetricRow label="Datasets held" value={count(datasets.length)} />
      <MetricRow label="Artifact contracts" value={count(contracts.length)} />
    </MetricCard>

    <MetricCard title="Consistency point" subtitle="Cross-dataset watermark">
      <MetricRow label="Available" value={watermark?.available ? 'yes' : 'no'} />
      {#if watermark?.available}
        <MetricRow label="Complete range"
                   value={`${count(watermark.fromBlock)}–${count(watermark.toBlock)}`} />
        <MetricRow label="As-of block"
                   value={watermark.asOf ? count(watermark.asOf.blockNumber) : 'not resolvable'} />
        <MetricRow label="As-of slot"
                   value={watermark.asOf ? count(watermark.asOf.slot) : '—'} />
      {:else}
        <MetricRow label="Reason" value={watermark?.reason ?? '—'} />
      {/if}
    </MetricCard>
  </div>

  <section class="mt-4 rounded-xl border border-blue-500/25 bg-blue-500/5 p-4 text-sm text-slate-300">
    <h3 class="m-0 text-sm font-semibold text-slate-100">Reading this archive honestly</h3>
    <p class="mb-0 mt-2">
      Blocks above the committed range are <strong>unknown, not absent</strong>. A block can be
      final and durable and still be up to one batch linger plus one maintenance budget away from
      being queryable, so an empty result near tip does not mean there is no history. The archive
      drains only finalized blocks, so trailing the chain tip is by design.
    </p>
    {#if coverage?.transactionHashLookup?.mode === 'full-scan'}
      <p class="mb-0 mt-3 text-amber-300">
        <strong>Lookup by transaction hash is a full scan.</strong>
        {coverage.transactionHashLookup.note ?? ''}
      </p>
    {/if}
  </section>

  <section class="card mt-4 overflow-hidden">
    <div class="border-b border-slate-700/40 px-4 py-3">
      <h2 class="m-0 text-sm font-semibold">Datasets</h2>
      <p class="mb-0 mt-1 text-xs text-slate-500">
        Every section commits for the same range, so a projected dataset is covered by the
        consistency point above or not projected at all.
      </p>
    </div>
    <div class="overflow-x-auto">
      <table class="w-full min-w-[420px] text-left text-xs">
        <thead class="bg-slate-950/50 text-slate-500">
          <tr><th class="p-3">Dataset</th><th class="p-3">Kind</th>
            <th class="p-3">Projection version</th></tr>
        </thead>
        <tbody class="divide-y divide-slate-800">
          {#each datasets as row}
            <tr>
              <td class="p-3 font-mono text-slate-200">{row.name}</td>
              <td class="p-3 text-slate-400">{row.kind}</td>
              <td class="p-3">
                {#if row.version !== null}v{row.version}
                {:else if row.contract}
                  <span class="text-slate-400">schema v{row.contract.schemaVersion} · codec
                    v{row.contract.codecVersion}</span>
                {:else}—{/if}
              </td>
            </tr>
          {:else}
            <tr><td colspan="3" class="p-8 text-center text-slate-500">No datasets reported.</td></tr>
          {/each}
        </tbody>
      </table>
    </div>
  </section>

  <section class="card mt-4 overflow-hidden">
    <div class="border-b border-slate-700/40 px-4 py-3">
      <h2 class="m-0 text-sm font-semibold">Epoch artifact contracts</h2>
      <p class="mb-0 mt-1 text-xs text-slate-500">
        Not derivable from the section fingerprint, so it is stated here. Irreproducible artifacts
        cannot be rebuilt once their boundary has passed.
      </p>
    </div>
    <div class="overflow-x-auto">
      <table class="w-full min-w-[720px] text-left text-xs">
        <thead class="bg-slate-950/50 text-slate-500">
          <tr><th class="p-3">Artifact</th><th class="p-3">Schema</th><th class="p-3">Codec</th>
            <th class="p-3">Representation</th><th class="p-3">If lost</th></tr>
        </thead>
        <tbody class="divide-y divide-slate-800">
          {#each contracts as contract}
            <tr>
              <td class="p-3 font-mono text-slate-200">{contract.dataset}</td>
              <td class="p-3">v{contract.schemaVersion}</td>
              <td class="p-3">v{contract.codecVersion}</td>
              <td class="p-3 font-mono text-slate-400">{contract.representation}</td>
              <td class="p-3">
                <span class="badge {durabilityTone(contract.reconstructibility)}">
                  {contract.reconstructibility.replace(/_/g, ' ')}
                </span>
              </td>
            </tr>
          {:else}
            <tr><td colspan="5" class="p-8 text-center text-slate-500">
              This archive holds no epoch artifacts.
            </td></tr>
          {/each}
        </tbody>
      </table>
    </div>
  </section>

  <details class="card mt-4 p-4">
    <summary class="cursor-pointer text-sm font-semibold text-slate-200">Operator details</summary>
    <div class="mt-4 grid gap-3 text-sm md:grid-cols-2">
      <div>
        <div class="text-xs text-slate-500">Projection identity</div>
        <CopyValue value={coverage?.identity} width={58} label="projection identity" />
      </div>
      <div>
        <div class="text-xs text-slate-500">As-of block hash</div>
        <CopyValue value={watermark?.asOf?.blockHash} width={58} label="as-of block hash" />
      </div>
    </div>
  </details>
{/if}
