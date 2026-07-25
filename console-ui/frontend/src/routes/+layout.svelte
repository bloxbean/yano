<script lang="ts">
  import '../app.css';
  import ConsoleShell from '$lib/components/ConsoleShell.svelte';
  let { children } = $props();
</script>

<svelte:head>
  <meta name="color-scheme" content="dark" />
  <meta name="theme-color" content="#05070f" />
</svelte:head>

<ConsoleShell>{@render children()}</ConsoleShell>
