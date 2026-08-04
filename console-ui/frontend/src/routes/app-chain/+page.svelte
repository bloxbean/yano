<script lang="ts">
  import { base } from '$app/paths';
  import { onMount, tick } from 'svelte';
  import LineChart from '$lib/components/LineChart.svelte';
  import MetricCard from '$lib/components/MetricCard.svelte';
  import MetricRow from '$lib/components/MetricRow.svelte';
  import CopyValue from '$lib/components/CopyValue.svelte';
  import Pager from '$lib/components/Pager.svelte';
  import AppChainCapabilityPanels from '$lib/components/AppChainCapabilityPanels.svelte';
  import { apiFailureMessage, resolveApiBase, YanoApi } from '$lib/api/client';
  import type {
    AppChainBlock, AppChainBlockDetail, AppChainBlocks, AppChainMessage, AppChainStatus,
    ChainSummary, NodeConfig
  } from '$lib/api/types';
  import { SessionHistory, type CompactSample } from '$lib/telemetry/history';
  import { columnSamples, mergeSamples } from '$lib/telemetry/durable-history';
  import { PrometheusHistoryProvider, resolveMetricsBase } from '$lib/telemetry/prometheus';
  import { createPoller, type Poller } from '$lib/telemetry/poller';
  import { appChainSample, type AppChainHistoryState } from '$lib/appchain/history';
  import { completePayloadDigest, messagePreview, type MessagePreview } from '$lib/appchain/message-preview';
  import { StreamCursor } from '$lib/appchain/sse';
  import { boolValue, numberValue, objectList, objectValue, recordEntries, shortHash, stringValue } from '$lib/appchain/value';
  import { isEutxoChain } from '$lib/eutxo/model';
  import { isAuthenticatedMapChain } from '$lib/authmap/model';

  const CHAIN_KEY = 'yano.console.app-chain.selected.v1';
  const PAGE_SIZE = 25;
  const MESSAGE_WINDOW = 50;
  let api: YanoApi | null = null;
  let apiBase = '/api/v1';
  let config: NodeConfig | null = null;
  let chains: ChainSummary[] = [];
  let selectedChain = '';
  let status: AppChainStatus | null = null;
  let blocks: AppChainBlocks | null = null;
  let latestBlocks: AppChainBlocks | null = null;
  let blockPages: AppChainBlocks[] = [];
  let blockPageIndex = 0;
  let blocksLoading = false;
  let messages: AppChainMessage[] = [];
  let messagePageIndex = 0;
  let samples: CompactSample[] = [];
  let history: SessionHistory | null = null;
  let historyState: AppChainHistoryState | null = null;
  let durableSamples: CompactSample[] = [];
  let historySource = 'browser session';
  let poller: Poller | null = null;
  let streamAbort: AbortController | null = null;
  let streamGeneration = 0;
  let streamState = 'OFFLINE';
  let streamError = '';
  let pageError = '';
  let lastUpdated = '';
  let requestMs = 0;
  let dialog: HTMLDialogElement;
  let blockDialog: HTMLDialogElement;
  let inspected: AppChainMessage | null = null;
  let inspectedPreview: MessagePreview | null = null;
  let inspectedDigest: string | null = null;
  let inspectedBlock: AppChainBlockDetail | null = null;
  let inspectedBlockHeight: number | null = null;
  let inspectedBlockError = '';
  let inspectedBlockLoading = false;
  let pluginBundleIds: string[] = [];
  const cursor = new StreamCursor();

  const fmt = (value: unknown) => Number.isFinite(Number(value)) ? Number(value).toLocaleString() : '-';
  const age = (value: unknown) => {
    const elapsed = Math.max(0, Date.now() - numberValue(value));
    if (!numberValue(value)) return '-';
    if (elapsed < 1_000) return 'now';
    if (elapsed < 60_000) return `${Math.floor(elapsed / 1_000)}s ago`;
    if (elapsed < 3_600_000) return `${Math.floor(elapsed / 60_000)}m ago`;
    return `${Math.floor(elapsed / 3_600_000)}h ago`;
  };
  const entries = (value: unknown) => Object.entries(objectValue(value));

  $: anchor = objectValue(status?.anchor);
  $: commitment = objectValue(status?.stateCommitment);
  $: profile = objectValue(status?.stateMachineStatus);
  $: effects = objectValue(status?.effects);
  $: effectRuntime = objectValue(effects.executor);
  $: executors = objectList(effectRuntime.executorOperations);
  $: sinks = recordEntries(status?.sinks);
  $: peers = entries(status?.peers);
  $: latestBlock = latestBlocks?.blocks.at(-1);
  $: messagePageCount = Math.max(1, Math.ceil(messages.length / PAGE_SIZE));
  $: if (messagePageIndex >= messagePageCount) messagePageIndex = messagePageCount - 1;
  $: visibleMessages = messages.slice(
    messagePageIndex * PAGE_SIZE, (messagePageIndex + 1) * PAGE_SIZE);
  $: blockHasPrevious = blockPageIndex > 0;
  $: blockHasNext = (blocks?.from ?? 1) > 1;
  $: chartTip = [samples.map((sample) => sample[1] ?? null)];
  $: chartPool = [samples.map((sample) => sample[2] ?? null)];
  $: chartInterval = [samples.map((sample) => sample[3] ?? null)];
  $: chartAnchor = [samples.map((sample) => sample[4] ?? null)];

  onMount(() => {
    let disposed = false;
    const visibility = () => document.hidden ? stopStream() : restartStream();
    document.addEventListener('visibilitychange', visibility);
    void (async () => {
      try {
        apiBase = await resolveApiBase();
        api = new YanoApi(apiBase);
        [config, chains] = await Promise.all([api.config(), api.chains()]);
        void api.pluginBundles(null, 100).then((page) => {
          pluginBundleIds = objectList(page.items).map((bundle) => stringValue(bundle.id, ''))
            .filter((id) => id !== '');
        }).catch(() => { pluginBundleIds = []; });
        if (disposed) return;
        const queryChain = new URLSearchParams(location.search).get('chain');
        const remembered = localStorage.getItem(CHAIN_KEY);
        selectedChain = chains.some((chain) => chain.chainId === queryChain) ? queryChain!
          : chains.some((chain) => chain.chainId === remembered) ? remembered! : chains[0]?.chainId ?? '';
        if (selectedChain) activateChain(selectedChain);
        else pageError = 'No app chains are enabled on this node.';
      } catch (cause) {
        pageError = apiFailureMessage(cause, 'Unable to load app chains');
      }
    })();
    return () => {
      disposed = true;
      document.removeEventListener('visibilitychange', visibility);
      poller?.stop();
      stopStream();
      history?.persist();
    };
  });

  function activateChain(chainId: string): void {
    poller?.stop();
    stopStream();
    history?.persist();
    selectedChain = chainId;
    localStorage.setItem(CHAIN_KEY, chainId);
    history = new SessionHistory(`${apiBase}|${config?.protocolMagic ?? 'unknown'}|app-chain|${chainId}`);
    samples = history.values();
    durableSamples = [];
    historySource = 'browser session';
    void loadDurableHistory(chainId);
    historyState = null;
    status = null;
    blocks = null;
    latestBlocks = null;
    blockPages = [];
    blockPageIndex = 0;
    messages = [];
    messagePageIndex = 0;
    cursor.reset();
    poller = createPoller(refresh);
    poller.start();
    restartStream();
  }

  async function refresh(signal: AbortSignal): Promise<void> {
    if (!api || !selectedChain) return;
    const started = performance.now();
    try {
      const [nextStatus, nextBlocks] = await Promise.all([
        api.chainStatus(selectedChain, signal), api.chainBlocks(selectedChain, signal)
      ]);
      const result = appChainSample(nextStatus, historyState);
      if (result.discontinuity) history?.append([result.sample[0], null, null, null, null]);
      history?.append(result.sample);
      historyState = result.state;
      samples = mergeSamples(durableSamples, history?.values() ?? []);
      status = nextStatus;
      latestBlocks = nextBlocks;
      if (blockPageIndex === 0) {
        // Keep one coherent tip snapshot while the operator pages backward.
        // Once they return to page one, polling starts a fresh snapshot.
        blockPages = [nextBlocks];
        blocks = nextBlocks;
      }
      requestMs = Math.round(performance.now() - started);
      lastUpdated = new Date().toLocaleTimeString();
      pageError = '';
    } catch (cause) {
      if (!(cause instanceof DOMException && cause.name === 'AbortError')) {
        pageError = apiFailureMessage(cause, 'App-chain status request failed');
      }
    }
  }

  async function loadDurableHistory(chainId: string): Promise<void> {
    const base = resolveMetricsBase();
    if (!base) return;
    try {
      const provider = new PrometheusHistoryProvider(base);
      const end = Date.now() / 1_000;
      const range = { start: end - 3_600, end, step: 5 };
      const [tip, poolSeries, blockInterval, anchorLag] = await Promise.all([
        provider.range('appchain.tip-rate', range, chainId), provider.range('appchain.pool', range, chainId),
        provider.range('appchain.block-interval-ms', range, chainId), provider.range('appchain.anchor-lag', range, chainId)
      ]);
      if (chainId !== selectedChain) return;
      durableSamples = columnSamples([tip[0], poolSeries[0], blockInterval[0], anchorLag[0]]);
      samples = mergeSamples(durableSamples, history?.values() ?? []);
      historySource = 'Prometheus + browser session';
    } catch { historySource = 'browser session · durable provider unavailable'; }
  }

  function stopStream(): void {
    streamGeneration++;
    streamAbort?.abort();
    streamAbort = null;
    streamState = 'OFFLINE';
  }

  function restartStream(): void {
    if (!api || !selectedChain || document.hidden) return;
    streamAbort?.abort();
    const generation = ++streamGeneration;
    const controller = new AbortController();
    streamAbort = controller;
    void streamLoop(generation, controller.signal);
  }

  async function streamLoop(generation: number, signal: AbortSignal): Promise<void> {
    while (!signal.aborted && generation === streamGeneration && api) {
      try {
        streamState = 'CONNECTING';
        const listedTip = chains.find((chain) => chain.chainId === selectedChain)?.tipHeight;
        const initialTip = Math.max(1, numberValue(status?.tipHeight, numberValue(listedTip, 1)));
        const response = await api.chainStream(selectedChain, cursor.fromHeight(initialTip), signal);
        if (!response.body) throw new Error('Stream response has no body');
        streamState = 'LIVE';
        streamError = '';
        const reader = response.body.getReader();
        const decoder = new TextDecoder();
        let buffer = '';
        for (;;) {
          const { done, value } = await reader.read();
          if (done || signal.aborted || generation !== streamGeneration) break;
          buffer += decoder.decode(value, { stream: true }).replaceAll('\r\n', '\n');
          let boundary: number;
          while ((boundary = buffer.indexOf('\n\n')) >= 0) {
            const message = cursor.accept(buffer.slice(0, boundary), selectedChain);
            buffer = buffer.slice(boundary + 2);
            if (message) messages = [message, ...messages].slice(0, MESSAGE_WINDOW);
          }
        }
        if (!signal.aborted) throw new Error('Stream ended');
      } catch (cause) {
        if (signal.aborted || generation !== streamGeneration) return;
        streamState = 'RETRYING';
        streamError = cause instanceof Error ? cause.message : 'Stream disconnected';
        await new Promise<void>((resolve) => {
          const timer = setTimeout(resolve, 3_000);
          signal.addEventListener('abort', () => { clearTimeout(timer); resolve(); }, { once: true });
        });
      }
    }
  }

  async function inspect(message: AppChainMessage): Promise<void> {
    inspected = message;
    inspectedPreview = messagePreview(message.bodyHex);
    inspectedDigest = null;
    await tick();
    dialog.showModal();
    if (typeof message.bodyHex === 'string') {
      try { inspectedDigest = await completePayloadDigest(message.bodyHex); } catch { inspectedDigest = null; }
    }
  }

  function previousMessagePage(): void {
    if (messagePageIndex > 0) messagePageIndex--;
  }

  function nextMessagePage(): void {
    if (messagePageIndex + 1 < messagePageCount) messagePageIndex++;
  }

  function previousBlockPage(): void {
    if (blockPageIndex === 0) return;
    blockPageIndex--;
    blocks = blockPages[blockPageIndex];
  }

  async function nextBlockPage(): Promise<void> {
    if (!api || !blocks || blocks.from <= 1 || blocksLoading) return;
    const cached = blockPages[blockPageIndex + 1];
    if (cached) {
      blockPageIndex++;
      blocks = cached;
      return;
    }
    const from = Math.max(1, blocks.from - PAGE_SIZE);
    const limit = blocks.from - from;
    if (limit < 1) return;
    blocksLoading = true;
    try {
      const page = await api.chainBlocks(selectedChain, undefined, from, limit);
      blockPageIndex++;
      blockPages = [...blockPages.slice(0, blockPageIndex), page];
      blocks = page;
    } catch (cause) {
      pageError = apiFailureMessage(cause, 'Unable to load older app-chain blocks');
    } finally {
      blocksLoading = false;
    }
  }

  async function inspectBlock(block: AppChainBlock): Promise<void> {
    if (!api) return;
    const chainId = selectedChain;
    inspectedBlockHeight = block.height;
    inspectedBlock = null;
    inspectedBlockError = '';
    inspectedBlockLoading = true;
    await tick();
    blockDialog.showModal();
    try {
      const detail = await api.chainBlock(chainId, block.height);
      if (chainId === selectedChain && inspectedBlockHeight === block.height) {
        inspectedBlock = detail;
      }
    } catch (cause) {
      inspectedBlockError = apiFailureMessage(cause, 'Unable to load app-chain block details');
    } finally {
      inspectedBlockLoading = false;
    }
  }

  function inspectBlockMessage(message: AppChainMessage, index: number): void {
    if (!inspectedBlock) return;
    blockDialog.close();
    void inspect({
      ...message,
      chainId: inspectedBlock.chainId,
      height: inspectedBlock.height,
      index
    });
  }
</script>

<svelte:head><title>Yano · App Chains</title></svelte:head>

<div data-console-route="app-chain" class="mb-4 flex flex-wrap items-end justify-between gap-3">
  <div>
    <p class="m-0 text-xs font-semibold uppercase tracking-[.18em] text-violet-400">Application ledger</p>
    <h1 class="mt-1 text-2xl font-bold">App-chain operations</h1>
  </div>
  <div class="flex flex-wrap items-end gap-2">
    <label class="text-xs text-slate-400">Chain
      <select class="ml-2 rounded-lg border border-slate-700 bg-slate-950 px-3 py-2 text-slate-100"
              value={selectedChain} onchange={(event) => activateChain(event.currentTarget.value)}>
        {#each chains as chain}<option value={chain.chainId}>{chain.chainId}</option>{/each}
      </select>
    </label>
    <span class="badge {streamState === 'LIVE' ? 'badge-ok' : streamState === 'RETRYING' ? 'badge-bad' : 'badge-warn'}">
      STREAM {streamState}
    </span>
    {#if isEutxoChain(status)}
      <a class="rounded-lg border border-cyan-500/40 bg-cyan-500/10 px-3 py-2 text-xs font-semibold text-cyan-300 no-underline hover:border-cyan-400"
         href={`${base}/app-chain/eutxo/?chain=${encodeURIComponent(selectedChain)}`}>EUTxO Explorer</a>
    {/if}
    {#if isAuthenticatedMapChain(status)}
      <a class="rounded-lg border border-cyan-500/40 bg-cyan-500/10 px-3 py-2 text-xs font-semibold text-cyan-300 no-underline hover:border-cyan-400"
         href={`${base}/app-chain/authenticated-map/?chain=${encodeURIComponent(selectedChain)}`}>Authenticated Map</a>
    {/if}
    <span class="rounded-md border border-slate-700 px-2 py-1 text-xs font-mono text-slate-400">{requestMs || '-'} ms</span>
  </div>
</div>

{#if pageError}<div class="mb-4 rounded-xl border border-rose-500/30 bg-rose-500/10 p-3 text-sm text-rose-300">{pageError}</div>{/if}
{#if streamError}<div class="mb-4 text-xs text-amber-300">Live feed reconnecting: {streamError}</div>{/if}

<section class="card overflow-hidden p-5">
  <div class="flex flex-wrap items-end justify-between gap-6">
    <div>
      <div class="text-xs font-semibold uppercase tracking-[.18em] text-slate-500">Finalized tip</div>
      <div class="mt-2 text-4xl font-bold text-violet-300">{fmt(status?.tipHeight)}</div>
      <p class="mb-0 mt-2 flex flex-wrap items-center gap-1 text-sm text-slate-400">
        <span>chain {status?.chainId ?? (selectedChain || '-')} · state root</span>
        <CopyValue value={status?.stateRoot} width={30} label="state root" />
      </p>
      {#if commitment.profile}
        <p class="mb-0 mt-1 font-mono text-xs text-cyan-300">{stringValue(commitment.profile)} · version {fmt(commitment.version)}</p>
      {/if}
    </div>
    <div class="grid grid-cols-2 gap-5 text-right sm:grid-cols-4">
      <div><small class="text-slate-500">Role</small><div class="font-mono">{stringValue(status?.role, status?.sequencing ? 'member' : 'gossip')}</div></div>
      <div><small class="text-slate-500">Threshold</small><div class="font-mono">{fmt(status?.threshold)} of {fmt(status?.members)}</div></div>
      <div><small class="text-slate-500">Machine</small><div class="font-mono">{stringValue(status?.stateMachine)}</div></div>
      <div><small class="text-slate-500">Block interval</small>
        <div class="font-mono">{status?.configuredBlockIntervalMs != null
          ? `${fmt(status.configuredBlockIntervalMs)} ms target` : '-'}</div>
        <div class="mt-0.5 text-xs text-slate-500">{status?.blockIntervalMs != null
          ? `${fmt(status.blockIntervalMs)} ms observed` : 'waiting for more blocks'}</div>
      </div>
    </div>
  </div>
  <div class="mt-4 flex flex-wrap gap-2">
    <span class="badge {status?.running && !status?.stalled ? 'badge-ok' : 'badge-bad'}">{status?.running ? status.stalled ? 'STALLED' : 'RUNNING' : 'STOPPED'}</span>
    {#if status?.submissionsPaused}<span class="badge badge-warn">SUBMISSIONS PAUSED</span>{/if}
    <span class="text-xs text-slate-500">last block {age(status?.lastBlockAtMillis)} · updated {lastUpdated || 'waiting'}</span>
  </div>
</section>

<div class="section-title">Consensus & traffic</div>
<div class="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
  <MetricCard title="Consensus" subtitle="Finality and sequencing">
    <MetricRow label="Tip Height" value={fmt(status?.tipHeight)} />
    <MetricRow label="Threshold" value={`${fmt(status?.threshold)} of ${fmt(status?.members)}`} />
    <MetricRow label="Latest Cert Sigs" value={fmt(latestBlock?.certSignatures)} />
    <MetricRow label="Sequencing" value={status?.sequencing ? 'enabled' : 'diffusion-only'} />
    <MetricRow label="L1 Ref Deferrals" value={status?.l1RefDeferrals == null ? 'n/a' : fmt(status.l1RefDeferrals)} />
  </MetricCard>
  <MetricCard title="Pending pool" subtitle="Admission and drops">
    <MetricRow label="Messages" value={`${fmt(status?.poolSize)} / ${fmt(status?.poolCapacity)}`} />
    <MetricRow label="Admission" value={status?.submissionsPaused ? 'paused' : 'accepting'} />
    {#each entries(status?.drops) as [reason, count]}<MetricRow label={`Dropped · ${reason}`} value={fmt(count)} />{/each}
    {#if !entries(status?.drops).length}<MetricRow label="Dropped" value="0" />{/if}
  </MetricCard>
  <MetricCard title="Message traffic" subtitle="Counters since restart">
    <MetricRow label="Submitted" value={fmt(status?.submitted)} />
    <MetricRow label="Received" value={fmt(status?.received)} />
    <MetricRow label="Relayed" value={fmt(status?.relayed)} />
    <MetricRow label="Duplicates" value={fmt(status?.duplicates)} />
    <MetricRow label="Stored" value={fmt(status?.storedMessages)} />
  </MetricCard>
</div>

<div class="section-title">Anchoring & extensions</div>
<div class="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
  <MetricCard title="State commitment" subtitle="Genesis-selected authenticated state">
    <MetricRow label="Profile" value={stringValue(commitment.profile, 'unavailable')} />
    <MetricRow label="Backend" value={stringValue(commitment.backend, 'unavailable')} />
    <MetricRow label="Version" value={fmt(commitment.version)} />
    <MetricRow label="Oldest Proof" value={fmt(commitment.oldestProvableHeight)} />
    <MetricRow label="Proof Encoding" value={stringValue(commitment.nativeProofEncoding, 'unavailable')} />
    <MetricRow label="Format" value={shortHash(commitment.formatFingerprint, 24)}
               copyValue={commitment.formatFingerprint} copyLabel="state format fingerprint" />
    <MetricRow label="Genesis" value={commitment.legacy ? 'legacy generation' : shortHash(commitment.genesisId, 24)}
               copyValue={commitment.genesisId} copyLabel="state genesis identity" />
    <MetricRow label="State Root" value={shortHash(commitment.stateRoot, 24)}
               copyValue={commitment.stateRoot} copyLabel="state commitment root" />
  </MetricCard>
  <MetricCard title="L1 anchor" subtitle={boolValue(anchor.enabled) ? `${stringValue(anchor.mode, 'metadata')} mode` : 'Disabled'}>
    {#if boolValue(anchor.enabled)}
      <MetricRow label="Last Anchored Height" value={fmt(anchor.lastAnchoredHeight)} />
      <MetricRow label="Lag" value={`${fmt(anchor.lagBlocks)} blocks`} />
      {#if stringValue(anchor.mode, '') === 'script'}
        <MetricRow label="Bootstrapped" value={boolValue(anchor.bootstrapped) ? 'yes' : 'no'} />
        <MetricRow label="Thread Policy" value={shortHash(anchor.threadPolicyId)}
                   copyValue={anchor.threadPolicyId} copyLabel="thread policy" />
        <MetricRow label="Script Address" value={shortHash(anchor.scriptAddress)}
                   copyValue={anchor.scriptAddress} copyLabel="script address" />
      {/if}
      <MetricRow label={stringValue(anchor.mode, '') === 'script' && anchor.leader === false ? 'Anchors Observed (since restart)' : 'Anchors Confirmed (since restart)'}
                 value={fmt(anchor.leader === false ? anchor.observedAnchorCount : anchor.anchoredCount)} />
      <MetricRow label="Pending Tx" value={shortHash(anchor.pendingTx, 24)}
                 copyValue={anchor.pendingTx} copyLabel="pending transaction hash" />
      <MetricRow label="Last Tx" value={shortHash(anchor.lastAnchorTx, 24)}
                 copyValue={anchor.lastAnchorTx} copyLabel="last transaction hash" />
      <MetricRow label="Error" value={stringValue(anchor.lastError, 'none')} />
    {:else}<p class="text-sm text-slate-500">Anchoring not enabled on this chain.</p>{/if}
  </MetricCard>
  <MetricCard title="Finalized sinks" subtitle="Node-local delivery">
    {#each sinks as [id, sink]}
      <MetricRow label={shortHash(id, 28)} value={`cursor ${fmt(sink.cursor)} · lag ${fmt(sink.lagBlocks)} · sent ${fmt(sink.delivered)}`} />
      {#if sink.lastError}<MetricRow label="Error" value={stringValue(sink.lastError)} />{/if}
    {:else}<p class="text-sm text-slate-500">No sinks configured.</p>{/each}
  </MetricCard>
  <MetricCard title="Effect executors" subtitle="In-process operations">
    {#each executors as executor}
      <MetricRow label={stringValue(executor.id)} value={`${stringValue(executor.readiness, 'unknown')} · ${fmt(executor.successes)}/${fmt(executor.attempts)}`} />
      <MetricRow label="Types" value={Array.isArray(executor.types) ? executor.types.join(', ') : 'legacy predicate'} />
      {#if executor.failureCode && executor.failureCode !== 'NONE'}<MetricRow label="Failure" value={stringValue(executor.failureCode)} />{/if}
    {:else}<p class="text-sm text-slate-500">No in-process executors configured.</p>{/each}
  </MetricCard>
  <MetricCard title="Profile governance" subtitle="Composite state-machine profile">
    <MetricRow label="Mode" value={stringValue(profile.mode, 'n/a')} />
    {#if profile.activeProfileDigest}
      <MetricRow label="Active Profile" value={shortHash(profile.activeProfileDigest)}
                 copyValue={profile.activeProfileDigest} copyLabel="active profile digest" />
    {/if}
    {#if profile.currentEpoch != null}<MetricRow label="Epoch" value={fmt(profile.currentEpoch)} />{/if}
    {#if profile.activeFromHeight != null}<MetricRow label="Active From" value={fmt(profile.activeFromHeight)} />{/if}
    {#if profile.proposalStatus}<MetricRow label="Proposal" value={stringValue(profile.proposalStatus)} />{/if}
    {#if profile.approvals != null}<MetricRow label="Approvals / Ready" value={`${fmt(profile.approvals)} / ${fmt(profile.readiness)}`} />{/if}
    {#if profile.locallyReady != null}<MetricRow label="Local Catalog" value={boolValue(profile.locallyReady) ? 'ready' : 'missing'} />{/if}
  </MetricCard>
  <MetricCard title="App-chain peers" subtitle="Member connections">
    {#each peers as [peer, connected]}
      <MetricRow label={shortHash(peer, 28)} labelCopyValue={peer} copyLabel="app-chain peer"
                 value={`${connected ? 'connected' : 'disconnected'} · ${status?.peerTransports?.[peer] ?? '-'}`} />
    {:else}<p class="text-sm text-slate-500">No peers configured.</p>{/each}
  </MetricCard>
</div>

<div class="section-title">Trends <span class="font-normal normal-case tracking-normal text-slate-600">· {historySource} · up to 1 hour</span></div>
<div class="grid gap-4 md:grid-cols-2 xl:grid-cols-4">
  <MetricCard title="Tip rate" subtitle="blocks / second"><LineChart series={chartTip} colors={['#a78bfa']} label="App-chain tip rate" /></MetricCard>
  <MetricCard title="Pending pool" subtitle="messages"><LineChart series={chartPool} colors={['#f59e0b']} label="Pending message pool" /></MetricCard>
  <MetricCard title="Block interval" subtitle="milliseconds"><LineChart series={chartInterval} colors={['#38bdf8']} label="Block interval" /></MetricCard>
  <MetricCard title="Anchor lag" subtitle="app blocks"><LineChart series={chartAnchor} colors={['#10b981']} label="Anchor lag" /></MetricCard>
</div>

{#if api && status}
  {#key selectedChain}
    <AppChainCapabilityPanels {api} chainId={selectedChain} {status} {pluginBundleIds}
                              section="overview" />
  {/key}
{/if}

<div data-app-chain-section="finalized">
  <div class="section-title">Finalized data</div>
  <div class="grid items-stretch gap-4 xl:grid-cols-2">
  <section class="card flex h-[37rem] min-h-0 flex-col overflow-hidden">
    <div class="border-b border-slate-800 px-4 py-3"><h2 class="m-0 text-sm font-semibold">Live messages</h2><p class="m-0 text-xs text-slate-500">Authenticated fetch SSE · latest 50 · 25 per page</p></div>
    <div class="min-h-0 flex-1 overflow-auto text-[0.75rem] leading-4">
      {#each visibleMessages as message}
        <button type="button" class="grid w-full grid-cols-[70px_1fr_110px_80px] gap-2 border-b border-slate-800/60 px-4 py-2 text-left text-[0.75rem] leading-4 hover:bg-slate-800/50"
                aria-label={`Inspect finalized message at ${message.height}:${message.index}`} onclick={() => inspect(message)}>
          <span class="font-mono text-violet-300">{message.height}:{message.index}</span>
          <span class="truncate">{message.topic ?? 'default'}</span>
          <span class="truncate font-mono text-slate-500">
            <CopyValue value={message.sender} width={14} label="message sender" />
          </span>
          <span class="text-right font-mono text-slate-500">{Math.floor((message.bodyHex?.length ?? 0) / 2)} B</span>
        </button>
      {:else}<p class="p-5 text-sm text-slate-500">Waiting for finalized messages…</p>{/each}
    </div>
    {#if messages.length}
      <Pager page={messagePageIndex + 1}
             hasPrevious={messagePageIndex > 0}
             hasNext={messagePageIndex + 1 < messagePageCount}
             label="live messages"
             onPrevious={previousMessagePage}
             onNext={nextMessagePage} />
    {/if}
  </section>
  <section class="card flex h-[37rem] min-h-0 flex-col overflow-hidden">
    <div class="border-b border-slate-800 px-4 py-3"><h2 class="m-0 text-sm font-semibold">Recent blocks</h2><p class="m-0 text-xs text-slate-500">Finalized application blocks · 25 per page</p></div>
    <div class="min-h-0 flex-1 overflow-auto">
      <table class="w-full text-left text-[0.75rem] leading-4"><thead class="text-slate-500"><tr><th class="p-3">Height</th><th>Age</th><th>Messages</th><th>Signatures</th><th>State root</th></tr></thead>
        <tbody>{#each [...(blocks?.blocks ?? [])].reverse() as block}
          <tr class="border-t border-slate-800/60 hover:bg-slate-800/35">
            <td class="p-3 font-mono">
              <button type="button" class="text-violet-300 hover:text-violet-200"
                      aria-label={`Inspect finalized block ${block.height}`}
                      onclick={() => void inspectBlock(block)}>{fmt(block.height)}</button>
            </td>
            <td>{age(block.timestamp)}</td><td>{fmt(block.messageCount)}</td><td>{fmt(block.certSignatures)}</td>
            <td class="font-mono text-slate-500"><CopyValue value={block.stateRoot} label="block state root" /></td>
          </tr>
        {:else}<tr><td colspan="5" class="p-5 text-slate-500">No finalized blocks yet.</td></tr>{/each}</tbody>
      </table>
    </div>
    {#if blocks?.blocks.length}
      <Pager page={blockPageIndex + 1}
             hasPrevious={blockHasPrevious}
             hasNext={blockHasNext}
             busy={blocksLoading}
             label="app-chain blocks"
             onPrevious={previousBlockPage}
             onNext={() => void nextBlockPage()} />
    {/if}
  </section>
  </div>
</div>

{#if api && status}
  {#key `verification:${selectedChain}`}
    <div data-app-chain-section="verification">
      <AppChainCapabilityPanels {api} chainId={selectedChain} {status} {pluginBundleIds}
                                section="verification" />
    </div>
  {/key}
{/if}

{#if api && status}
  {#key `effects:${selectedChain}`}
    <div data-app-chain-section="effects">
      <AppChainCapabilityPanels {api} chainId={selectedChain} {status} {pluginBundleIds}
                                section="effects" />
    </div>
  {/key}
{/if}

<dialog bind:this={dialog} class="m-auto w-[min(900px,calc(100%-2rem))] rounded-2xl border border-slate-700 bg-slate-900 p-0 text-slate-100 backdrop:bg-slate-950/80">
  <div class="flex items-center justify-between border-b border-slate-700 p-4">
    <div><h2 class="m-0 text-lg font-semibold">Finalized message</h2><p class="m-0 text-xs text-slate-500">{inspected?.height}:{inspected?.index} · {inspected?.topic ?? 'default'}</p></div>
    <button type="button" class="rounded-lg border border-slate-700 px-3 py-2 text-sm" onclick={() => dialog.close()}>Close</button>
  </div>
  {#if inspected && inspectedPreview}
    <div class="grid gap-4 p-4 md:grid-cols-2">
      <div class="space-y-2 text-xs"><div><span class="text-slate-500">Message ID</span><div class="break-all font-mono"><CopyValue value={inspected.messageId} width={72} label="message id" /></div></div>
        <div><span class="text-slate-500">Sender / sequence</span><div class="flex flex-wrap items-center gap-1 break-all font-mono"><CopyValue value={inspected.sender} width={72} label="message sender" /><span>/ {inspected.senderSeq ?? '-'}</span></div></div>
        <div><span class="text-slate-500">Bytes / format</span><div class="font-mono">{fmt(inspectedPreview.byteLength)} / {inspectedPreview.format}{inspectedPreview.truncated ? ' / preview truncated' : ''}</div></div>
        <div><span class="text-slate-500">SHA-256</span><div class="break-all font-mono">
          {#if inspectedDigest}<CopyValue value={inspectedDigest} width={72} label="payload SHA-256" />
          {:else}{inspectedPreview.truncated ? 'unavailable for truncated preview' : 'calculating…'}{/if}
        </div></div>
      </div>
      <div><div class="mb-1 text-xs text-slate-500">Decoded payload</div><pre class="max-h-72 overflow-auto whitespace-pre-wrap break-all rounded-lg bg-slate-950 p-3 text-xs">{inspectedPreview.bodyText}</pre></div>
    </div>
    {#if isEutxoChain(status) && inspected.messageId}
      <div class="border-t border-slate-700 px-4 py-3">
        <a class="text-sm font-semibold text-cyan-300 no-underline hover:text-cyan-200"
           href={`${base}/app-chain/eutxo/?chain=${encodeURIComponent(selectedChain)}&message=${encodeURIComponent(inspected.messageId)}`}>
          Open decoded EUTxO transaction
        </a>
      </div>
    {/if}
    <div class="border-t border-slate-700 p-4"><div class="mb-1 text-xs text-slate-500">Raw hex preview</div><pre class="max-h-40 overflow-auto whitespace-pre-wrap break-all rounded-lg bg-slate-950 p-3 text-xs">{inspectedPreview.rawHex}</pre></div>
  {/if}
</dialog>

<dialog bind:this={blockDialog} class="m-auto max-h-[calc(100%-2rem)] w-[min(900px,calc(100%-2rem))] overflow-hidden rounded-2xl border border-slate-700 bg-slate-900 p-0 text-slate-100 backdrop:bg-slate-950/80">
  <div class="flex items-center justify-between border-b border-slate-700 p-4">
    <div>
      <h2 class="m-0 text-lg font-semibold">Finalized app-chain block</h2>
      <p class="m-0 text-xs text-slate-500">Height {inspectedBlockHeight ?? '-'}</p>
    </div>
    <button type="button" class="rounded-lg border border-slate-700 px-3 py-2 text-sm"
            onclick={() => blockDialog.close()}>Close</button>
  </div>
  {#if inspectedBlockLoading}
    <p class="p-5 text-sm text-slate-400">Loading certified block details…</p>
  {:else if inspectedBlockError}
    <p class="p-5 text-sm text-rose-300">{inspectedBlockError}</p>
  {:else if inspectedBlock}
    <div class="max-h-[calc(100vh-9rem)] overflow-auto">
      <dl class="grid gap-4 p-4 text-xs sm:grid-cols-2">
        <div><dt class="text-slate-500">Chain</dt><dd class="mt-1">{inspectedBlock.chainId}</dd></div>
        <div><dt class="text-slate-500">Timestamp</dt><dd class="mt-1">{new Date(inspectedBlock.timestamp).toLocaleString()}</dd></div>
        <div><dt class="text-slate-500">State root</dt><dd class="mt-1"><CopyValue value={inspectedBlock.stateRoot} width={56} label="block state root" /></dd></div>
        <div><dt class="text-slate-500">Messages root</dt><dd class="mt-1"><CopyValue value={inspectedBlock.messagesRoot} width={56} label="block messages root" /></dd></div>
        <div><dt class="text-slate-500">Previous block hash</dt><dd class="mt-1"><CopyValue value={inspectedBlock.prevHash} width={56} label="previous block hash" /></dd></div>
        <div><dt class="text-slate-500">Proposer</dt><dd class="mt-1"><CopyValue value={inspectedBlock.proposer} width={56} label="block proposer" /></dd></div>
        <div><dt class="text-slate-500">Certificate signatures</dt><dd class="mt-1">{fmt(inspectedBlock.certSignatures)}</dd></div>
        <div><dt class="text-slate-500">Messages</dt><dd class="mt-1">{fmt(inspectedBlock.messages.length)}</dd></div>
      </dl>
      <div class="border-t border-slate-700 p-4">
        <h3 class="m-0 text-sm font-semibold">Finalized messages</h3>
        <p class="mb-3 mt-1 text-xs text-slate-500">Select a message to inspect its decoded payload.</p>
        <div class="overflow-hidden rounded-xl border border-slate-800">
          {#each inspectedBlock.messages as message, index}
            <button type="button"
                    class="grid w-full grid-cols-[70px_1fr_130px] gap-3 border-b border-slate-800/60 px-3 py-2 text-left text-xs last:border-b-0 hover:bg-slate-800/50"
                    onclick={() => inspectBlockMessage(message, index)}>
              <span class="font-mono text-violet-300">{inspectedBlock.height}:{index}</span>
              <span class="truncate">{message.topic ?? 'default'}</span>
              <span class="truncate text-right font-mono text-slate-500">{shortHash(message.messageId, 18)}</span>
            </button>
          {:else}
            <p class="m-0 p-4 text-xs text-slate-500">This block contains no application messages.</p>
          {/each}
        </div>
      </div>
    </div>
  {/if}
</dialog>
