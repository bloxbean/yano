<script lang="ts">
  import { onMount } from 'svelte';
  import { ApiError, apiFailureMessage, resolveApiBase, YanoApi } from '$lib/api/client';
  import type { AppChainStatus, AuthenticatedSnapshotSummary } from '$lib/api/types';

  const BUNDLE = 'com.bloxbean.cardano.yano.appchain.cardano-history';
  type View = 'overview' | 'parameters' | 'stake' | 'governance' | 'proofs';

  let api: YanoApi | null = $state(null);
  let chains: Array<{ chainId: string; status: AppChainStatus }> = $state([]);
  let selectedChain = $state('');
  let status: AppChainStatus | null = $state(null);
  let history: Record<string, unknown> | null = $state(null);
  let epochs: number[] = $state([]);
  let anchor: Record<string, unknown> | null = $state(null);
  let view: View = $state('overview');
  let loading = $state(true);
  let error = $state('');
  let initializationPending = $state(false);
  let result = $state('Choose a query.');
  let proofBundle: Record<string, unknown> | null = $state(null);
  let proofStatus = $state('Run a query that returns proof coordinates.');
  let selectedProof: { response: Record<string, unknown>; kind: string; epoch: number } | null = $state(null);

  let parameterEpoch = $state(0);
  let stakeEpoch = $state(0);
  let credentialType = $state(0);
  let credentialHash = $state('');
  let drepEpoch = $state(0);
  let drepType = $state(0);
  let drepHash = $state('');
  let proposalEpoch = $state(0);
  let proposalTx = $state('');
  let proposalIndex = $state(0);

  let hasStake = $derived(hasComponent(status, 'l1-epoch-stake-v1'));
  let hasGovernance = $derived(hasComponent(status, 'l1-epoch-governance-v1'));

  onMount(() => void load());

  async function load() {
    loading = true;
    error = '';
    try {
      api = new YanoApi(await resolveApiBase());
      const summaries = await api.chains();
      const inspected = await Promise.all(summaries.map(async (chain) => ({
        chainId: chain.chainId,
        status: await api!.chainStatus(chain.chainId)
      })));
      chains = inspected.filter((chain) => hasComponent(chain.status, 'l1-epoch-params-v1'));
      const requested = new URLSearchParams(location.search).get('chain');
      selectedChain = chains.some((chain) => chain.chainId === requested)
        ? requested! : chains[0]?.chainId ?? '';
      if (selectedChain) await selectChain(selectedChain);
    } catch (cause) {
      error = apiFailureMessage(cause, 'Cardano History is unavailable');
    } finally {
      loading = false;
    }
  }

  async function selectChain(chainId: string) {
    if (!api) return;
    selectedChain = chainId;
    status = chains.find((chain) => chain.chainId === chainId)?.status
      ?? await api.chainStatus(chainId);
    history = null;
    epochs = [];
    initializationPending = false;
    const parameters = { chain: chainId };
    try {
      const [historyValue, epochsValue, anchorValue] = await Promise.all([
        api.domain<Record<string, unknown>>(BUNDLE, 'status', parameters),
        api.domain<{ epochs?: number[] }>(BUNDLE, 'epochs', { ...parameters, limit: '100' }),
        api.chainAnchorCommitment(chainId).catch(() => null)
      ]);
      history = historyValue;
      epochs = epochsValue.epochs ?? [];
      anchor = anchorValue as Record<string, unknown> | null;
      const latest = Number(historyValue.latestEpoch ?? epochs[0] ?? 0);
      parameterEpoch = stakeEpoch = drepEpoch = proposalEpoch = latest;
    } catch (cause) {
      if (cause instanceof ApiError && cause.status === 404) {
        initializationPending = true;
        anchor = await api.chainAnchorCommitment(chainId).catch(() => null) as Record<string, unknown> | null;
      } else {
        throw cause;
      }
    }
  }

  async function query(
    kind: string, epoch: number, path: string, extra: Record<string, string> = {}
  ) {
    if (!api || !selectedChain) return;
    result = 'Loading…';
    proofBundle = null;
    try {
      const response = await api.domain<Record<string, unknown>>(
        BUNDLE, path, { chain: selectedChain, ...extra });
      result = pretty(response);
      selectedProof = { response, kind, epoch };
      proofStatus = response.proof ? 'Proof coordinates are ready.' : 'This response has no proof coordinates.';
      view = kind === 'parameters' ? 'parameters'
        : kind === 'stake' ? 'stake' : 'governance';
    } catch (cause) {
      selectedProof = null;
      result = apiFailureMessage(cause, 'Query failed');
      proofStatus = 'No proof selected.';
    }
  }

  async function generateProof() {
    if (!api || !selectedProof) return;
    proofStatus = 'Generating root-fixed proof…';
    try {
      const coordinates = selectedProof.response.proof as Record<string, unknown> | undefined;
      if (!coordinates) throw new Error('The query did not return proof coordinates');
      if (coordinates.kind === 'authenticated-snapshot') {
        const series = String(coordinates.seriesId ?? '');
        const prefix = selectedProof.kind === 'stake' ? 'epoch-stake-' : 'epoch-drep-';
        const snapshot = await findSnapshot(series, `${prefix}${selectedProof.epoch}`);
        const nested = await api.chainSnapshotProof(
          selectedChain, series, snapshot.sequence, String(coordinates.secondaryKey ?? ''));
        proofBundle = {
          schema: 'cardano-history-console-proof-v1', kind: 'authenticated-snapshot',
          chainId: selectedChain, subject: selectedProof.kind, epoch: selectedProof.epoch,
          history: selectedProof.response, proof: nested
        };
      } else {
        const key = String(coordinates.physicalKey ?? coordinates.factPhysicalKey ?? '');
        const height = Number(selectedProof.response.committedHeight);
        if (!key || !Number.isSafeInteger(height)) throw new Error('Invalid primary proof coordinates');
        const envelope = await api.chainProof(selectedChain, key, height);
        const verdict = await api.verifyChainProof(selectedChain, {
          mode: envelope.presence === 'ABSENT' ? 'exclusion' : 'inclusion',
          profile: envelope.profile,
          presence: envelope.presence,
          expectedRootHex: envelope.stateRoot,
          keyHex: envelope.key,
          valueHex: envelope.valueHex,
          proofWireHex: envelope.proofWireHex
        });
        proofBundle = {
          schema: 'cardano-history-console-proof-v1', kind: 'primary',
          chainId: selectedChain, subject: selectedProof.kind, epoch: selectedProof.epoch,
          history: selectedProof.response, proof: envelope, nodeVerification: verdict
        };
      }
      proofStatus = 'Proof generated. Export it and verify against an independently obtained anchored root.';
      view = 'proofs';
    } catch (cause) {
      proofStatus = apiFailureMessage(cause, 'Proof generation failed');
    }
  }

  async function findSnapshot(series: string, snapshotId: string): Promise<AuthenticatedSnapshotSummary> {
    if (!api) throw new Error('API unavailable');
    let cursor: string | undefined;
    for (let page = 0; page < 100; page++) {
      const response = await api.chainSnapshots(selectedChain, series, cursor, 100);
      const found = response.items.find((item) => item.snapshotId === snapshotId);
      if (found) return found;
      if (!response.nextCursor) break;
      cursor = response.nextCursor;
    }
    throw new Error(`No online authenticated snapshot found for ${snapshotId}`);
  }

  function downloadProof() {
    if (!proofBundle) return;
    const url = URL.createObjectURL(new Blob([pretty(proofBundle)], { type: 'application/json' }));
    const link = document.createElement('a');
    link.href = url;
    link.download = `cardano-history-${selectedProof?.kind ?? 'proof'}-${selectedProof?.epoch ?? 0}.json`;
    link.click();
    URL.revokeObjectURL(url);
  }

  function hasComponent(value: AppChainStatus | null, componentId: string): boolean {
    return value?.capabilityManifest?.components.some((component) => component.id === componentId) ?? false;
  }
  function validHash(value: string, bytes: number) { return new RegExp(`^[0-9a-fA-F]{${bytes * 2}}$`).test(value); }
  function pretty(value: unknown) { return JSON.stringify(value, null, 2); }
  function short(value: unknown) {
    const text = String(value ?? '—');
    return text.length > 30 ? `${text.slice(0, 16)}…${text.slice(-10)}` : text;
  }
</script>

<svelte:head><title>Yano · Cardano History</title></svelte:head>

<div class="mb-5 flex flex-wrap items-end justify-between gap-4">
  <div><p class="eyebrow">Authenticated L1 history</p><h1 class="mt-1 text-2xl font-bold">Cardano History</h1>
    <p class="mt-2 max-w-3xl text-sm text-slate-400">Query epoch facts and generate proofs against L1-anchored app-chain roots.</p></div>
  {#if chains.length > 0}<label class="text-xs text-slate-400">Chain
    <select class="ml-2 rounded-lg border border-slate-700 bg-slate-950 px-3 py-2 text-slate-100"
      value={selectedChain} onchange={(event) => void selectChain(event.currentTarget.value)}>
      {#each chains as chain}<option value={chain.chainId}>{chain.chainId}</option>{/each}
    </select></label>{/if}
</div>

{#if loading}<p class="text-slate-400">Discovering Cardano History capabilities…</p>
{:else if error}<div class="card border-rose-500/30 p-5 text-rose-300">{error}</div>
{:else if chains.length === 0}<div class="card p-6 text-slate-400">No running app chain declares the <code>l1-epoch-params-v1</code> capability.</div>
{:else}
  <nav class="mb-5 flex flex-wrap gap-2" aria-label="Cardano History views">
    {#each ['overview', 'parameters', ...(hasStake ? ['stake'] : []), ...(hasGovernance ? ['governance'] : []), 'proofs'] as item}
      <button class="rounded-lg px-4 py-2 text-sm font-semibold {view === item ? 'bg-cyan-500/20 text-cyan-200' : 'bg-slate-900 text-slate-400'}"
        onclick={() => view = item as View}>{item[0].toUpperCase() + item.slice(1)}</button>
    {/each}
  </nav>

  {#if initializationPending}
    <div class="mb-5 rounded-xl border border-amber-500/30 bg-amber-500/10 p-4 text-sm text-amber-200">
      The capability and anchor are active, but this fresh chain has not finalized its first retained or live epoch fact yet.
    </div>
  {/if}

  {#if view === 'overview'}
    <div class="grid gap-4 lg:grid-cols-2">
      <section class="card p-5"><div class="section-title">Commitment</div>
        <dl class="mt-4 grid grid-cols-[auto_1fr] gap-x-4 gap-y-2 text-sm">
          <dt class="text-slate-500">Chain</dt><dd>{selectedChain}</dd>
          <dt class="text-slate-500">Height</dt><dd>{history?.committedHeight ?? status?.tipHeight ?? 0}</dd>
          <dt class="text-slate-500">Latest epoch</dt><dd>{history?.latestEpoch ?? 'not observed'}</dd>
          <dt class="text-slate-500">State root</dt><dd class="font-mono text-xs">{short(history?.stateRoot ?? status?.stateRoot)}</dd>
        </dl></section>
      <section class="card p-5"><div class="section-title">Enabled datasets</div>
        <div class="mt-4 flex flex-wrap gap-2">
          {#each status?.capabilityManifest?.components ?? [] as component}<span class="badge badge-ok">{component.id}</span>{/each}
        </div></section>
      <section class="card p-5"><div class="section-title">Available epochs</div>
        <div class="mt-4 flex flex-wrap gap-2">{#each epochs as epoch}<span class="badge">{epoch}</span>{/each}{#if epochs.length === 0}<span class="text-sm text-slate-500">None yet</span>{/if}</div></section>
      <section class="card p-5"><div class="section-title">L1 anchor</div><pre class="mt-4 max-h-80 overflow-auto text-xs">{pretty(anchor)}</pre></section>
    </div>
  {:else if view === 'parameters'}
    <section class="card p-5"><div class="section-title">Protocol parameters</div>
      <div class="mt-4 flex flex-wrap items-end gap-3"><label class="text-xs text-slate-400">Epoch
        <input class="mt-1 block rounded-lg border border-slate-700 bg-slate-950 px-3 py-2" type="number" min="0" bind:value={parameterEpoch}></label>
        <button class="rounded-lg bg-cyan-600 px-4 py-2 text-sm font-semibold" onclick={() => void query('parameters', parameterEpoch, `epochs/${parameterEpoch}/parameters`)}>Query</button></div>
      <pre class="mt-4 max-h-[520px] overflow-auto text-xs">{result}</pre></section>
  {:else if view === 'stake'}
    <section class="card p-5"><div class="section-title">End-of-epoch stake credential</div>
      <div class="mt-4 grid gap-3 md:grid-cols-3"><label class="text-xs text-slate-400">Epoch<input class="mt-1 block w-full rounded-lg border border-slate-700 bg-slate-950 px-3 py-2" type="number" min="0" bind:value={stakeEpoch}></label>
        <label class="text-xs text-slate-400">Credential type<select class="mt-1 block w-full rounded-lg border border-slate-700 bg-slate-950 px-3 py-2" bind:value={credentialType}><option value={0}>Key</option><option value={1}>Script</option></select></label>
        <label class="text-xs text-slate-400">Credential hash<input class="mt-1 block w-full rounded-lg border border-slate-700 bg-slate-950 px-3 py-2 font-mono" bind:value={credentialHash}></label></div>
      <button class="mt-3 rounded-lg bg-cyan-600 px-4 py-2 text-sm font-semibold" disabled={!validHash(credentialHash, 28)} onclick={() => void query('stake', stakeEpoch, `epochs/${stakeEpoch}/stake/${credentialType}/${credentialHash}`)}>Query</button>
      <pre class="mt-4 max-h-[520px] overflow-auto text-xs">{result}</pre></section>
  {:else if view === 'governance'}
    <div class="grid gap-4 lg:grid-cols-2"><section class="card p-5"><div class="section-title">DRep distribution</div>
      <div class="mt-4 grid gap-3"><label class="text-xs text-slate-400">Epoch<input class="mt-1 block w-full rounded-lg border border-slate-700 bg-slate-950 px-3 py-2" type="number" min="0" bind:value={drepEpoch}></label>
        <label class="text-xs text-slate-400">DRep type<select class="mt-1 block w-full rounded-lg border border-slate-700 bg-slate-950 px-3 py-2" bind:value={drepType}><option value={0}>Key</option><option value={1}>Script</option></select></label>
        <label class="text-xs text-slate-400">DRep hash<input class="mt-1 block w-full rounded-lg border border-slate-700 bg-slate-950 px-3 py-2 font-mono" bind:value={drepHash}></label></div>
      <button class="mt-3 rounded-lg bg-cyan-600 px-4 py-2 text-sm font-semibold" disabled={!validHash(drepHash, 28)} onclick={() => void query('drep', drepEpoch, `epochs/${drepEpoch}/dreps/${drepType}/${drepHash}`)}>Query</button></section>
      <section class="card p-5"><div class="section-title">Proposal history</div><div class="mt-4 grid gap-3">
        <label class="text-xs text-slate-400">Epoch<input class="mt-1 block w-full rounded-lg border border-slate-700 bg-slate-950 px-3 py-2" type="number" min="0" bind:value={proposalEpoch}></label>
        <label class="text-xs text-slate-400">Transaction ID<input class="mt-1 block w-full rounded-lg border border-slate-700 bg-slate-950 px-3 py-2 font-mono" bind:value={proposalTx}></label>
        <label class="text-xs text-slate-400">Action index<input class="mt-1 block w-full rounded-lg border border-slate-700 bg-slate-950 px-3 py-2" type="number" min="0" max="65535" bind:value={proposalIndex}></label></div>
        <button class="mt-3 rounded-lg bg-cyan-600 px-4 py-2 text-sm font-semibold" disabled={!validHash(proposalTx, 32)} onclick={() => void query('proposal', proposalEpoch, `proposals/${proposalTx}/${proposalIndex}`, { epoch: String(proposalEpoch) })}>Query</button></section></div>
    <pre class="card mt-4 max-h-[520px] overflow-auto p-5 text-xs">{result}</pre>
  {:else}
    <section class="card p-5"><div class="section-title">Proof workspace</div>
      <p class="mt-3 text-sm text-slate-400">{proofStatus}</p><div class="mt-3 flex gap-2">
        <button class="rounded-lg bg-cyan-600 px-4 py-2 text-sm font-semibold disabled:opacity-40" disabled={!selectedProof} onclick={() => void generateProof()}>Generate proof</button>
        <button class="rounded-lg border border-slate-700 px-4 py-2 text-sm disabled:opacity-40" disabled={!proofBundle} onclick={downloadProof}>Export JSON</button></div>
      <pre class="mt-4 max-h-[620px] overflow-auto text-xs">{pretty(proofBundle)}</pre></section>
  {/if}
{/if}
