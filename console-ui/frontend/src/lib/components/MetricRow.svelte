<script lang="ts">
  let { label, value } = $props<{ label: string; value: unknown }>();
</script>

<div class="metric-row">
  <span class="metric-label">{label}</span>
  <span class="metric-value">{value === null || value === undefined || value === '' ? '-' : String(value)}</span>
</div>
