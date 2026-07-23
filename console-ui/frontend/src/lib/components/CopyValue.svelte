<script lang="ts">
  import { onDestroy } from 'svelte';
  import { copyText } from '$lib/clipboard';
  import { shortHash, stringValue } from '$lib/appchain/value';

  let {
    value,
    display,
    width = 28,
    label = 'value',
    mono = true
  } = $props<{
    value: unknown;
    display?: string;
    width?: number;
    label?: string;
    mono?: boolean;
  }>();

  let state = $state<'idle' | 'copied' | 'failed'>('idle');
  let resetTimer: ReturnType<typeof setTimeout> | undefined;
  let fullValue = $derived(stringValue(value));
  let shown = $derived(display ?? shortHash(value, width));
  let copyable = $derived(fullValue !== '-' && fullValue.length > 0);

  onDestroy(() => {
    if (resetTimer) clearTimeout(resetTimer);
  });

  async function copy(): Promise<void> {
    if (!copyable) return;
    if (resetTimer) clearTimeout(resetTimer);
    try {
      await copyText(fullValue);
      state = 'copied';
    } catch {
      state = 'failed';
    }
    resetTimer = setTimeout(() => { state = 'idle'; }, 1_800);
  }
</script>

<span class="inline-flex min-w-0 items-center justify-end gap-1.5">
  <span class:font-mono={mono} class="min-w-0 break-all" title={fullValue}>{shown}</span>
  {#if copyable}
    <button type="button"
            class="copy-button"
            aria-label={`Copy ${label}`}
            title={state === 'copied' ? 'Copied' : state === 'failed' ? 'Copy failed' : `Copy ${label}`}
            onclick={copy}>
      {#if state === 'copied'}
        <svg viewBox="0 0 20 20" aria-hidden="true"><path d="m4 10 3.3 3.3L16 5.8" /></svg>
      {:else}
        <svg viewBox="0 0 20 20" aria-hidden="true">
          <rect x="6.5" y="6.5" width="9" height="9" rx="1.5" />
          <path d="M4.5 13.5h-1v-10h10v1" />
        </svg>
      {/if}
    </button>
    <span class="sr-only" aria-live="polite">
      {state === 'copied' ? `${label} copied` : state === 'failed' ? `Unable to copy ${label}` : ''}
    </span>
  {/if}
</span>
