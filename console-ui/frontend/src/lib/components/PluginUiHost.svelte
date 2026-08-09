<script lang="ts">
  import type { UiExtensionCatalogEntry } from '$lib/api/types';
  import { checkedBridgeResponse, validateBridgeRequest } from '$lib/plugins/ui-extension';
  import type { YanoApi } from '$lib/api/client';

  let { extension, chainId, api } = $props<{
    extension: UiExtensionCatalogEntry;
    chainId: string;
    api: YanoApi;
  }>();
  let frame: HTMLIFrameElement;
  let error = $state('');
  let active = 0;
  const sessionNonce = crypto.randomUUID();

  function frameUrl(): string {
    return `${api.base}${extension.entrypointUrl}`;
  }

  function loaded() {
    frame.contentWindow?.postMessage({
      type: 'yano-ui-init', uiApiVersion: 1, sessionNonce, chainId,
      bundleId: extension.bundleId, extensionId: extension.extensionId
    }, '*');
  }

  async function invoke(method: string, payload: unknown, signal: AbortSignal): Promise<unknown> {
    const body = payload && typeof payload === 'object' ? payload as Record<string, unknown> : {};
    switch (method) {
      case 'host.context':
        return { chainId, bundleId: extension.bundleId, extensionId: extension.extensionId };
      case 'app-chain.status':
        return api.chainStatus(chainId, signal);
      case 'app-chain.anchor':
        return api.chainAnchorCommitment(chainId, signal);
      case 'app-chain.query': {
        const path = typeof body.path === 'string' ? body.path : '';
        const paramsHex = typeof body.paramsHex === 'string' ? body.paramsHex : '';
        if (!/^[A-Za-z0-9._~/-]{1,160}$/.test(path) || !/^(?:[0-9a-fA-F]{2})*$/.test(paramsHex)) {
          throw new Error('invalid query request');
        }
        return api.chainQuery(chainId, path, paramsHex, signal);
      }
      case 'app-chain.proof': {
        const keyHex = typeof body.keyHex === 'string' ? body.keyHex : '';
        const height = typeof body.height === 'number' ? body.height : undefined;
        if (!/^(?:[0-9a-fA-F]{2}){1,2048}$/.test(keyHex)
          || (height !== undefined && (!Number.isSafeInteger(height) || height < 0))) {
          throw new Error('invalid proof request');
        }
        return api.chainProof(chainId, keyHex, height, signal);
      }
      case 'app-chain.snapshots': {
        const series = typeof body.series === 'string' ? body.series : '';
        const cursor = typeof body.cursor === 'string' ? body.cursor : '';
        const limit = typeof body.limit === 'number' ? body.limit : 100;
        if (!/^[A-Za-z0-9._-]{1,128}$/.test(series)
          || (cursor && !/^[A-Za-z0-9_-]{1,684}$/.test(cursor))
          || !Number.isSafeInteger(limit) || limit < 1 || limit > 100) {
          throw new Error('invalid snapshot catalog request');
        }
        return api.chainSnapshots(chainId, series, cursor || undefined, limit, signal);
      }
      case 'app-chain.snapshot': {
        const series = typeof body.series === 'string' ? body.series : '';
        const sequence = typeof body.sequence === 'number' ? body.sequence : -1;
        if (!/^[A-Za-z0-9._-]{1,128}$/.test(series)
          || !Number.isSafeInteger(sequence) || sequence < 0) {
          throw new Error('invalid snapshot descriptor request');
        }
        return api.chainSnapshot(chainId, series, sequence, signal);
      }
      case 'app-chain.snapshot-proof': {
        const series = typeof body.series === 'string' ? body.series : '';
        const sequence = typeof body.sequence === 'number' ? body.sequence : -1;
        const keyHex = typeof body.keyHex === 'string' ? body.keyHex : '';
        if (!/^[A-Za-z0-9._-]{1,128}$/.test(series)
          || !Number.isSafeInteger(sequence) || sequence < 0
          || !/^(?:[0-9a-fA-F]{2}){1,256}$/.test(keyHex)) {
          throw new Error('invalid snapshot proof request');
        }
        return api.chainSnapshotProof(chainId, series, sequence, keyHex, signal);
      }
      case 'file.export': {
        const filename = typeof body.filename === 'string' ? body.filename : '';
        const mediaType = typeof body.mediaType === 'string' ? body.mediaType : '';
        const text = typeof body.text === 'string' ? body.text : '';
        if (!/^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/.test(filename)
          || mediaType !== 'application/json' || new TextEncoder().encode(text).length > 2 * 1024 * 1024) {
          throw new Error('invalid file export request');
        }
        const url = URL.createObjectURL(new Blob([text], { type: mediaType }));
        const link = document.createElement('a');
        link.href = url;
        link.download = filename;
        link.click();
        setTimeout(() => URL.revokeObjectURL(url), 0);
        return { exported: true, filename };
      }
      case 'app-chain.domain': {
        const path = typeof body.path === 'string' ? body.path : '';
        const parameters = body.parameters && typeof body.parameters === 'object'
          ? body.parameters as Record<string, string> : {};
        if (!/^[A-Za-z0-9._~/-]{1,160}$/.test(path) || Object.keys(parameters).length > 32
          || Object.entries(parameters).some(([key, value]) => !/^[A-Za-z0-9._~-]{1,80}$/.test(key)
            || typeof value !== 'string' || value.length > 4096)) {
          throw new Error('invalid domain request');
        }
        return api.domain(extension.bundleId, path, parameters, signal);
      }
      default:
        throw new Error('unsupported bridge method');
    }
  }

  async function message(event: MessageEvent) {
    if (event.source !== frame?.contentWindow || event.origin !== 'null' || active >= 8) return;
    let requestId = 'invalid';
    try {
      const request = validateBridgeRequest(event.data, extension, chainId, sessionNonce);
      requestId = request.requestId;
      active += 1;
      const controller = new AbortController();
      const timeout = request.method === 'app-chain.snapshot-proof' ? 30_000 : 5_000;
      const timer = setTimeout(() => controller.abort(), timeout);
      try {
        const result = checkedBridgeResponse(await invoke(request.method, request.payload, controller.signal));
        frame.contentWindow?.postMessage({ type: 'yano-ui-response', sessionNonce,
          requestId, ok: true, result }, '*');
      } finally {
        clearTimeout(timer);
        active -= 1;
      }
    } catch (cause) {
      const message = cause instanceof Error && cause.name === 'AbortError'
        ? 'bridge request timed out' : cause instanceof Error ? cause.message : 'bridge request failed';
      frame?.contentWindow?.postMessage({ type: 'yano-ui-response', sessionNonce,
        requestId, ok: false, error: message }, '*');
      error = message;
    }
  }
</script>

<svelte:window onmessage={message} />

{#if error}<div class="mb-3 rounded-lg border border-amber-500/30 bg-amber-500/10 p-3 text-xs text-amber-200">{error}</div>{/if}
<iframe bind:this={frame} src={frameUrl()} title={extension.title}
  sandbox="allow-scripts" referrerpolicy="no-referrer" onload={loaded}
  class="min-h-[720px] w-full rounded-xl border border-slate-700 bg-slate-950"></iframe>
