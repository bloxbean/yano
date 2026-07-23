<script lang="ts">
  import { onMount } from 'svelte';
  import type { AnchorCommitment, AppChainStatus, ProofVerificationResult,
    StateProofEnvelope } from '$lib/api/types';
  import { YanoApi, apiFailureMessage } from '$lib/api/client';
  import { discoverChainCapabilities } from '$lib/appchain/capabilities';
  import { effectStatsView } from '$lib/appchain/effect-stats';
  import { assessProofBinding, parseProofEnvelope } from '$lib/appchain/proof-verification';
  import { asciiHex, boundedPretty, hexSha256, PRODUCT_ID, SHA256, STATE_KEY } from '$lib/appchain/verification';
  import { numberValue, objectList, objectValue, shortHash, stringValue } from '$lib/appchain/value';
  import CopyValue from './CopyValue.svelte';
  import MetricCard from './MetricCard.svelte';
  import MetricRow from './MetricRow.svelte';

  type PanelSection = 'all' | 'overview' | 'verification' | 'effects';

  let { api, chainId, status, pluginBundleIds = [], section = 'all' } = $props<{
    api: YanoApi;
    chainId: string;
    status: AppChainStatus;
    pluginBundleIds?: string[];
    section?: PanelSection;
  }>();
  let effects = $state<Array<Record<string, unknown>>>([]);
  let effectStats = $state<Record<string, unknown>>({});
  let effectError = $state('');
  let effectProof = $state('');
  let proposalId = $state('');
  let proposal = $state('');
  let proposalError = $state('');
  let messageId = $state('');
  let stateKey = $state('');
  let expectedPayloadHash = $state('');
  let expectedProofValueHash = $state('');
  let evidence = $state('');
  let proof = $state('');
  let proofEnvelope = $state<StateProofEnvelope>();
  let expectedProofRoot = $state('');
  let expectedProofHeight = $state<number>();
  let rootProvenance = $state('');
  let proofVerification = $state<ProofVerificationResult>();
  let anchorCommitment = $state<AnchorCommitment>();
  let payloadDigest = $state('');
  let proofValueDigest = $state('');
  let verificationError = $state('');
  let busy = $state(false);
  let capabilities = $derived(discoverChainCapabilities(status, pluginBundleIds));
  let effectView = $derived(effectStatsView(effectStats));
  let showOverview = $derived(section === 'all' || section === 'overview');
  let showVerification = $derived(section === 'all' || section === 'verification');
  let showEffects = $derived(section === 'all' || section === 'effects');
  let proofBinding = $derived(proofEnvelope
    ? assessProofBinding(proofEnvelope, expectedProofRoot, expectedProofHeight) : undefined);
  let rawEffectStats = $derived(boundedPretty(effectStats, 65_536));
  let statsDialog = $state<HTMLDialogElement>();

  onMount(() => { if (showEffects && capabilities.effects) void refreshEffects(); });

  async function refreshEffects(): Promise<void> {
    effectError = '';
    try {
      const [page, stats] = await Promise.all([api.chainEffects(chainId), api.chainEffectStats(chainId)]);
      effects = objectList(page.effects);
      effectStats = objectValue(stats.stats);
    } catch (cause) { effectError = apiFailureMessage(cause, 'Effects unavailable'); }
  }

  async function operate(action: 'requeue' | 'cancel', effect: Record<string, unknown>): Promise<void> {
    const height = numberValue(effect.height, -1);
    const ordinal = numberValue(effect.ordinal, -1);
    if (height < 0 || ordinal < 0 || !confirm(`${action} effect ${height}/${ordinal}?`)) return;
    busy = true;
    effectError = '';
    try {
      if (action === 'requeue') await api.requeueEffect(chainId, height, ordinal);
      else await api.cancelEffect(chainId, height, ordinal);
      await refreshEffects();
    } catch (cause) { effectError = apiFailureMessage(cause, `Unable to ${action} effect`); }
    finally { busy = false; }
  }

  async function inspectEffectProof(effect: Record<string, unknown>): Promise<void> {
    effectError = '';
    try {
      effectProof = boundedPretty(await api.chainEffectProof(
        chainId, numberValue(effect.height), numberValue(effect.ordinal)));
    } catch (cause) { effectError = apiFailureMessage(cause, 'Effect proof unavailable'); }
  }

  async function queryProposal(): Promise<void> {
    proposal = '';
    proposalError = '';
    if (!capabilities.roleDomainBundle || !PRODUCT_ID.test(proposalId)) {
      proposalError = 'Enter a valid proposal id.';
      return;
    }
    try {
      const committedQuery = await api.chainQuery(
        chainId, 'components/role-approvals/proposal', asciiHex(proposalId));
      try {
        const decodedProjection = await api.domain(capabilities.roleDomainBundle,
          `proposals/${proposalId}`, { chain: chainId });
        proposal = boundedPretty({ committedQuery, decodedProjection });
      } catch (cause) {
        proposal = boundedPretty({ committedQuery,
          decodedProjectionUnavailable: apiFailureMessage(cause, 'Decoded projection unavailable') });
      }
    } catch (cause) { proposalError = apiFailureMessage(cause, 'Proposal unavailable'); }
  }

  async function loadEvidence(): Promise<void> {
    evidence = ''; payloadDigest = ''; verificationError = '';
    if (!SHA256.test(messageId)) { verificationError = 'Message id must be 64 lowercase hex characters.'; return; }
    try {
      const [bundle, message] = await Promise.all([
        api.chainEvidence(chainId, messageId), api.chainMessage(chainId, messageId)
      ]);
      evidence = boundedPretty(bundle);
      if (typeof message.bodyHex === 'string') payloadDigest = await hexSha256(message.bodyHex);
    } catch (cause) { verificationError = apiFailureMessage(cause, 'Evidence unavailable'); }
  }

  async function applyProofEnvelope(
    result: StateProofEnvelope,
    root: string,
    provenance: string,
    height?: number
  ): Promise<void> {
    proofEnvelope = result;
    proof = JSON.stringify(result, null, 2);
    stateKey = result.key;
    expectedProofRoot = root;
    expectedProofHeight = height;
    rootProvenance = provenance;
    proofVerification = undefined;
    proofValueDigest = typeof result.valueHex === 'string'
      ? await hexSha256(result.valueHex) : '';
  }

  async function loadProof(): Promise<void> {
    proof = ''; proofEnvelope = undefined; proofValueDigest = ''; verificationError = '';
    anchorCommitment = undefined;
    if (!STATE_KEY.test(stateKey)) { verificationError = 'State key must be 1–256 bytes of canonical lowercase hex.'; return; }
    try {
      const result = await api.chainProof(chainId, stateKey);
      await applyProofEnvelope(result, result.stateRoot, 'Current proof envelope (self-reported)');
    } catch (cause) { verificationError = apiFailureMessage(cause, 'State proof unavailable'); }
  }

  async function loadAnchoredProof(): Promise<void> {
    proof = ''; proofEnvelope = undefined; proofValueDigest = ''; verificationError = '';
    proofVerification = undefined;
    if (!STATE_KEY.test(stateKey)) {
      verificationError = 'State key must be 1–256 bytes of canonical lowercase hex.';
      return;
    }
    try {
      const anchor = await api.chainAnchorCommitment(chainId);
      const result = await api.chainProof(chainId, stateKey, anchor.anchoredHeight);
      anchorCommitment = anchor;
      await applyProofEnvelope(
        result, anchor.stateRoot, 'L1-confirmed by this node', anchor.anchoredHeight);
    } catch (cause) {
      verificationError = apiFailureMessage(
        cause, 'A retained proof for the latest confirmed anchor is unavailable');
    }
  }

  async function importProof(): Promise<void> {
    verificationError = '';
    try {
      const result = parseProofEnvelope(proof);
      const keepExternalRoot = SHA256.test(expectedProofRoot)
        && rootProvenance === 'User-supplied external root';
      await applyProofEnvelope(
        result,
        keepExternalRoot ? expectedProofRoot : result.stateRoot,
        keepExternalRoot ? rootProvenance : 'Pasted proof envelope (self-reported)');
    } catch (cause) {
      verificationError = cause instanceof Error ? cause.message : 'Invalid proof envelope';
    }
  }

  async function verifyProof(): Promise<void> {
    verificationError = '';
    proofVerification = undefined;
    if (!proofEnvelope) { verificationError = 'Load or import a proof first.'; return; }
    if (!SHA256.test(expectedProofRoot)) {
      verificationError = 'Expected root must be 64 lowercase hex characters.';
      return;
    }
    try {
      proofVerification = await api.verifyChainProof(chainId, {
        mode: proofEnvelope.valueHex === undefined ? 'exclusion' : 'inclusion',
        expectedRootHex: expectedProofRoot,
        keyHex: proofEnvelope.key,
        ...(proofEnvelope.valueHex === undefined ? {} : { valueHex: proofEnvelope.valueHex }),
        proofWireHex: proofEnvelope.proofWireHex
      });
    } catch (cause) {
      verificationError = apiFailureMessage(cause, 'Proof verification failed');
    }
  }

  function markExternalRoot(): void {
    expectedProofHeight = undefined;
    anchorCommitment = undefined;
    rootProvenance = 'User-supplied external root';
    proofVerification = undefined;
  }

  const match = (digest: string, expected: string) => !expected ? 'computed locally'
    : !SHA256.test(expected) ? 'invalid expected hash'
      : digest === expected ? 'MATCH' : 'MISMATCH';
</script>

{#if showOverview}
  <div class="section-title">Discovered capabilities</div>
  <section class="card p-4">
    <div class="flex flex-wrap gap-2">
      <span class="badge badge-ok">EVIDENCE BUNDLES</span><span class="badge badge-ok">STATE PROOFS</span>
      {#if capabilities.effects}<span class="badge badge-ok">EFFECTS</span>{/if}
      {#if capabilities.roleApprovals}<span class="badge badge-ok">ROLE APPROVALS</span>{/if}
    </div>
    <p class="mb-0 mt-3 text-xs text-slate-500">Source: {capabilities.sources.join(' · ')}</p>
  </section>

  {#if capabilities.roleApprovals}
    <div class="section-title">Committed proposal</div>
    <section class="card p-5">
      <p class="mt-0 text-sm text-slate-400">Runs the stock role-aware committed query, then adds the decoded domain projection when available. The root-fixed result includes its committed height and state root.</p>
      <div class="flex flex-wrap gap-2"><input class="min-w-64 flex-1 rounded-lg border border-slate-700 bg-slate-950 px-3 py-2" bind:value={proposalId} placeholder="proposal id" />
        <button class="rounded-lg bg-violet-500 px-4 py-2 text-sm font-semibold" onclick={queryProposal}>Query</button></div>
      {#if proposalError}<p class="text-sm text-rose-300">{proposalError}</p>{/if}
      {#if proposal}<pre class="mt-4 max-h-96 overflow-auto rounded-lg bg-slate-950 p-3 text-xs">{proposal}</pre>{/if}
    </section>
  {/if}
{/if}

{#if showEffects && capabilities.effects}
  <div class="section-title">Effect lifecycle</div>
  {#if effectError}<div class="mb-3 rounded-xl border border-rose-500/30 bg-rose-500/10 p-3 text-sm text-rose-300">{effectError}</div>{/if}
  <div class="grid items-stretch gap-4 xl:grid-cols-2">
    <section class="card flex flex-col overflow-hidden xl:h-[42rem]">
      <div class="flex items-start justify-between gap-3 border-b border-slate-800 px-4 py-3">
        <div><h2 class="m-0 text-sm font-semibold">Effect statistics</h2>
          <p class="m-0 mt-1 text-xs text-slate-500">Consensus and node-local execution</p></div>
        <span class="badge {effectView.enabled ? 'badge-ok' : 'badge-warn'}">
          {effectView.enabled ? 'ENABLED' : 'DISABLED'}
        </span>
      </div>
      <div class="min-h-0 flex-1 overflow-auto p-4">
        <div class="grid grid-cols-2 gap-2 sm:grid-cols-3">
          {#each [
            ['Queue', effectView.queueDepth],
            ['In flight', effectView.inFlight],
            ['Open on chain', effectView.openOnChain],
            ['Result backlog', effectView.resultBacklog],
            ['Executed', effectView.executed],
            ['Parked / expired', `${effectView.parked} / ${effectView.expired}`]
          ] as item}
            <div class="rounded-lg border border-slate-800 bg-slate-950/50 p-3">
              <div class="text-[.68rem] uppercase tracking-wide text-slate-500">{item[0]}</div>
              <div class="mt-1 font-mono text-base text-slate-100">{item[1]}</div>
            </div>
          {/each}
        </div>

        <h3 class="mb-2 mt-5 text-xs font-semibold uppercase tracking-wide text-slate-400">
          Lifecycle status
        </h3>
        <div class="flex flex-wrap gap-2">
          {#each effectView.statuses as statusCount}
            <span class="badge {statusCount.count > 0 ? 'badge-ok' : ''}">
              {statusCount.name} {statusCount.count}
            </span>
          {:else}<span class="text-xs text-slate-500">No status counters reported.</span>{/each}
        </div>

        <div class="mt-5 grid gap-4 sm:grid-cols-2">
          <div>
            <h3 class="mb-1 text-xs font-semibold uppercase tracking-wide text-slate-400">
              Result backlog
            </h3>
            {#each effectView.backlogByType as item}
              <MetricRow label={item.name} value={item.count} />
            {:else}<p class="text-xs text-slate-500">No per-type backlog reported.</p>{/each}
          </div>
          <div>
            <h3 class="mb-1 text-xs font-semibold uppercase tracking-wide text-slate-400">
              Execution outcomes
            </h3>
            {#each effectView.executionTotals as item}
              <MetricRow label={item.name} value={item.count} />
            {:else}<p class="text-xs text-slate-500">No execution outcomes reported.</p>{/each}
          </div>
        </div>

        <h3 class="mb-1 mt-5 text-xs font-semibold uppercase tracking-wide text-slate-400">
          Pending age
        </h3>
        <MetricRow label="Oldest" value={`${effectView.oldestPendingAgeSeconds.toFixed(1)}s / ${effectView.oldestPendingAgeBlocks} blocks`} />

        <h3 class="mb-1 mt-5 text-xs font-semibold uppercase tracking-wide text-slate-400">
          Latency by operation
        </h3>
        {#each effectView.latency as latency}
          <MetricRow label={latency.type} value={latency.count
            ? `${latency.averageMillis?.toFixed(1)} ms avg / ${latency.count} runs`
            : 'no samples'} />
        {:else}<p class="text-xs text-slate-500">No latency series reported.</p>{/each}

        <h3 class="mb-2 mt-5 text-xs font-semibold uppercase tracking-wide text-slate-400">
          Executors
        </h3>
        <div class="space-y-2">
          {#each effectView.executors as executor}
            <article class="rounded-lg border border-slate-800 bg-slate-950/45 p-3 text-xs">
              <div class="flex flex-wrap items-start justify-between gap-2">
                <div><strong class="font-mono text-slate-100">{executor.id}</strong>
                  <div class="mt-1 text-slate-500">{executor.types.join(', ') || 'no declared types'}</div></div>
                <span class="badge {executor.readiness === 'READY' ? 'badge-ok'
                  : executor.readiness === 'UNKNOWN' ? 'badge-warn' : 'badge-bad'}">
                  {executor.readiness}
                </span>
              </div>
              <div class="mt-2 grid grid-cols-2 gap-1 text-slate-400 sm:grid-cols-4">
                <span>{executor.successes}/{executor.attempts} succeeded</span>
                <span>{executor.failures} failed</span>
                <span>{executor.inFlight} in flight</span>
                <span>{executor.lastActivity}</span>
              </div>
              {#if executor.failureCode !== 'NONE'}
                <div class="mt-2 text-rose-300">{executor.failureCode}</div>
              {/if}
            </article>
          {:else}<p class="text-xs text-slate-500">No in-process executors configured.</p>{/each}
        </div>
      </div>
      <div class="flex items-center justify-between gap-2 border-t border-slate-800 px-4 py-3">
        <button class="rounded-lg border border-slate-700 px-3 py-2 text-xs"
                onclick={refreshEffects}>Refresh</button>
        <button class="rounded-lg border border-slate-700 px-3 py-2 text-xs"
                onclick={() => statsDialog?.showModal()}>View raw statistics</button>
      </div>
    </section>
    <section class="card flex flex-col overflow-hidden xl:h-[42rem]">
      <div class="border-b border-slate-800 px-4 py-3"><h2 class="m-0 text-sm font-semibold">Emitted effects</h2><p class="m-0 text-xs text-slate-500">First 100 retained records · privileged actions require an operator API key</p></div>
      <div class="min-h-0 flex-1 overflow-auto">
        {#each effects as effect}
          <div class="grid gap-2 border-b border-slate-800/60 p-4 text-xs md:grid-cols-[90px_1fr_100px_auto]">
            <span class="font-mono text-violet-300">{numberValue(effect.height)}/{numberValue(effect.ordinal)}</span>
            <div><strong>{stringValue(effect.type)}</strong><div class="mt-1 text-slate-500">{stringValue(effect.scope)} · {stringValue(effect.gate)} · expires {stringValue(effect.expiryHeight)}</div></div>
            <span>{stringValue(effect.resultPolicy)}</span>
            <div class="flex flex-wrap gap-1">
              <button class="rounded border border-slate-700 px-2 py-1" onclick={() => inspectEffectProof(effect)}>Proof</button>
              <button disabled={busy} class="rounded border border-amber-700 px-2 py-1 text-amber-300" onclick={() => operate('requeue', effect)}>Requeue</button>
              <button disabled={busy} class="rounded border border-rose-700 px-2 py-1 text-rose-300" onclick={() => operate('cancel', effect)}>Cancel</button>
            </div>
          </div>
        {:else}<p class="p-5 text-sm text-slate-500">No effects emitted yet.</p>{/each}
      </div>
      {#if effectProof}<pre class="m-4 max-h-64 overflow-auto rounded-lg bg-slate-950 p-3 text-xs">{effectProof}</pre>{/if}
    </section>
  </div>

  <dialog bind:this={statsDialog}
          class="m-auto w-[min(900px,calc(100%-2rem))] rounded-2xl border border-slate-700
                 bg-slate-900 p-0 text-slate-100 backdrop:bg-slate-950/80">
    <div class="flex items-center justify-between border-b border-slate-700 p-4">
      <div><h2 class="m-0 text-lg font-semibold">Raw effect statistics</h2>
        <p class="m-0 mt-1 text-xs text-slate-500">Bounded API response for diagnostics</p></div>
      <div class="flex items-center gap-2">
        <CopyValue value={rawEffectStats} display="JSON" label="effect statistics JSON" mono={false} />
        <button type="button" class="rounded-lg border border-slate-700 px-3 py-2 text-sm"
                onclick={() => statsDialog?.close()}>Close</button>
      </div>
    </div>
    <pre class="m-4 max-h-[70vh] overflow-auto whitespace-pre-wrap break-all rounded-lg
                bg-slate-950 p-4 text-xs">{rawEffectStats}</pre>
  </dialog>
{/if}

{#if showVerification}
  <div class="section-title">Portable verification</div>
  <div class="grid gap-4 xl:grid-cols-2">
  <section class="card p-5">
    <h2 class="mt-0 text-sm font-semibold">Evidence bundle</h2>
    <p class="text-xs text-slate-500">Fetch the finalized bundle and message, then SHA-256 the exact payload bytes in this browser.</p>
    <div class="flex gap-2"><input class="min-w-0 flex-1 rounded-lg border border-slate-700 bg-slate-950 px-3 py-2 font-mono text-xs" bind:value={messageId} placeholder="64-character message id" />
      <button type="button" class="rounded-lg bg-blue-500 px-3 py-2 text-sm font-semibold" onclick={loadEvidence}>Load</button></div>
    <label class="mt-3 block text-xs text-slate-400">Expected payload SHA-256 (optional)
      <input class="mt-1 w-full rounded-lg border border-slate-700 bg-slate-950 px-3 py-2 font-mono"
             bind:value={expectedPayloadHash} placeholder="64 lowercase hex characters" />
    </label>
    {#if expectedPayloadHash && !SHA256.test(expectedPayloadHash)}
      <p class="text-xs text-amber-300">Expected payload hash must be 64 lowercase hex characters.</p>
    {/if}
    {#if payloadDigest}
      <div class="mt-3 text-xs">
        <span class="text-slate-500">Payload SHA-256 · {match(payloadDigest, expectedPayloadHash)}</span>
        <div class="mt-1 flex justify-start"><CopyValue value={payloadDigest} width={64} label="payload SHA-256" /></div>
      </div>
    {/if}
    {#if evidence}<pre class="mt-4 max-h-96 overflow-auto rounded-lg bg-slate-950 p-3 text-xs">{evidence}</pre>{/if}
  </section>
  <section class="card p-5">
    <h2 class="mt-0 text-sm font-semibold">MPF state proof</h2>
    <p class="text-xs text-slate-500">Verify an inclusion or exclusion proof with this release's bounded MPF verifier. Root provenance and root/height binding are reported separately from mathematical validity.</p>
    <input class="w-full rounded-lg border border-slate-700 bg-slate-950 px-3 py-2 font-mono text-xs"
           bind:value={stateKey} placeholder="state key hex (message id for ordered-log)" />
    <div class="mt-2 flex flex-wrap gap-2">
      <button type="button" class="rounded-lg bg-cyan-500 px-3 py-2 text-sm font-semibold text-slate-950"
              onclick={loadProof}>Load current proof</button>
      <button type="button" class="rounded-lg border border-cyan-500/50 px-3 py-2 text-sm text-cyan-200"
              onclick={loadAnchoredProof}>Load latest anchored proof</button>
    </div>

    <label class="mt-4 block text-xs text-slate-400">Proof envelope JSON
      <textarea class="mt-1 h-44 w-full resize-y rounded-lg border border-slate-700 bg-slate-950 p-3 font-mono text-xs"
                bind:value={proof} spellcheck="false"
                placeholder="Paste a proof envelope returned by the proof endpoint"></textarea>
    </label>
    <button type="button" class="mt-2 rounded-lg border border-slate-700 px-3 py-2 text-xs"
            onclick={importProof}>Import pasted envelope</button>

    <label class="mt-4 block text-xs text-slate-400">Expected state root
      <input class="mt-1 w-full rounded-lg border border-slate-700 bg-slate-950 px-3 py-2 font-mono"
             bind:value={expectedProofRoot} oninput={markExternalRoot}
             placeholder="64 lowercase hex characters" />
    </label>
    <p class="mb-0 mt-1 text-xs text-slate-500">
      Root source: {rootProvenance || 'not selected'}
    </p>
    {#if anchorCommitment}
      <div class="mt-2 rounded-lg border border-slate-800 bg-slate-950/60 p-3 text-xs">
        <div class="flex justify-between gap-3"><span class="text-slate-500">Anchored height</span><span>{anchorCommitment.anchoredHeight}</span></div>
        <div class="mt-1 flex justify-between gap-3"><span class="text-slate-500">L1 transaction</span>
          <CopyValue value={anchorCommitment.transactionHash} label="anchor transaction hash" /></div>
        <div class="mt-1 flex justify-between gap-3"><span class="text-slate-500">App block hash</span>
          <CopyValue value={anchorCommitment.blockHash} label="anchored app block hash" /></div>
      </div>
    {/if}

    <label class="mt-3 block text-xs text-slate-400">Expected proof-value SHA-256 (optional)
      <input class="mt-1 w-full rounded-lg border border-slate-700 bg-slate-950 px-3 py-2 font-mono"
             bind:value={expectedProofValueHash} placeholder="64 lowercase hex characters" />
    </label>
    {#if expectedProofValueHash && !SHA256.test(expectedProofValueHash)}
      <p class="text-xs text-amber-300">Expected proof-value hash must be 64 lowercase hex characters.</p>
    {/if}
    {#if proofValueDigest}
      <div class="mt-3 text-xs">
        <span class="text-slate-500">Proof value SHA-256 · {match(proofValueDigest, expectedProofValueHash)}</span>
        <div class="mt-1 flex justify-start"><CopyValue value={proofValueDigest} width={64} label="proof value SHA-256" /></div>
      </div>
    {/if}

    <button type="button" class="mt-4 rounded-lg bg-emerald-500 px-4 py-2 text-sm font-semibold text-slate-950"
            onclick={verifyProof}>Verify proof</button>
    {#if proofEnvelope && proofBinding}
      <div class="mt-4 grid gap-2 sm:grid-cols-3" aria-live="polite">
        <div class="rounded-lg border border-slate-800 p-3">
          <div class="text-xs text-slate-500">MPF path</div>
          <div class="mt-1 font-semibold {proofVerification?.valid ? 'text-emerald-300' : proofVerification ? 'text-rose-300' : 'text-slate-400'}">
            {proofVerification ? (proofVerification.valid ? 'VALID' : 'INVALID') : 'NOT CHECKED'}
          </div>
        </div>
        <div class="rounded-lg border border-slate-800 p-3">
          <div class="text-xs text-slate-500">Root binding</div>
          <div class="mt-1 font-semibold {proofBinding.rootMatches ? 'text-emerald-300' : 'text-rose-300'}">
            {proofBinding.rootMatches ? 'MATCH' : 'MISMATCH'}
          </div>
        </div>
        <div class="rounded-lg border border-slate-800 p-3">
          <div class="text-xs text-slate-500">Height binding</div>
          <div class="mt-1 font-semibold {proofBinding.heightMatches === false ? 'text-rose-300' : proofBinding.heightMatches ? 'text-emerald-300' : 'text-slate-400'}">
            {proofBinding.heightMatches === undefined ? 'NOT PINNED' : proofBinding.heightMatches ? 'MATCH' : 'MISMATCH'}
          </div>
        </div>
      </div>
    {/if}
    {#if proofEnvelope}
      <div class="mt-3 space-y-1 text-xs">
        <div class="flex justify-between gap-3"><span class="text-slate-500">Proof root</span>
          <CopyValue value={proofEnvelope.stateRoot} label="proof state root" /></div>
        <div class="flex justify-between gap-3"><span class="text-slate-500">Proof key</span>
          <CopyValue value={proofEnvelope.key} label="proof key" /></div>
      </div>
    {/if}
  </section>
  </div>
  {#if verificationError}<p class="text-sm text-rose-300">{verificationError}</p>{/if}
{/if}
