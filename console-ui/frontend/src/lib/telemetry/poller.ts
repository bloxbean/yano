export interface Poller {
  start(): void;
  stop(): void;
  refresh(): Promise<void>;
}

export function createPoller(work: (signal: AbortSignal) => Promise<void>, intervalMs = 5_000,
                             timeoutMs = 4_000): Poller {
  let timer: ReturnType<typeof setTimeout> | undefined;
  let active = false;
  let inFlight: Promise<void> | undefined;

  const schedule = (delay: number) => {
    if (!active) return;
    clearTimeout(timer);
    timer = setTimeout(() => void refresh(), delay);
  };

  const refresh = async () => {
    if (inFlight) return inFlight;
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), timeoutMs);
    inFlight = work(controller.signal).finally(() => {
      clearTimeout(timeout);
      inFlight = undefined;
      schedule(document.hidden ? intervalMs * 4 : intervalMs);
    });
    return inFlight;
  };

  const visibility = () => {
    if (!document.hidden) {
      clearTimeout(timer);
      void refresh();
    }
  };

  return {
    start() {
      if (active) return;
      active = true;
      document.addEventListener('visibilitychange', visibility);
      void refresh();
    },
    stop() {
      active = false;
      clearTimeout(timer);
      document.removeEventListener('visibilitychange', visibility);
    },
    refresh
  };
}
