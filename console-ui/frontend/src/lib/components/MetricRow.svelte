<script lang="ts">
  import CopyValue from './CopyValue.svelte';

  let {
    label,
    value,
    copyValue,
    labelCopyValue,
    copyLabel
  } = $props<{
    label: string;
    value: unknown;
    copyValue?: unknown;
    labelCopyValue?: unknown;
    copyLabel?: string;
  }>();
</script>

<div class="metric-row">
  <span class="metric-label">
    {#if labelCopyValue !== undefined}
      <CopyValue value={labelCopyValue} display={label} label={copyLabel ?? 'identifier'} />
    {:else}
      {label}
    {/if}
  </span>
  <span class="metric-value">
    {#if copyValue !== undefined}
      <CopyValue value={copyValue} display={value === null || value === undefined || value === '' ? '-' : String(value)}
                 label={copyLabel ?? label.toLowerCase()} />
    {:else}
      {value === null || value === undefined || value === '' ? '-' : String(value)}
    {/if}
  </span>
</div>
