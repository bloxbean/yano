import { Buffer } from "buffer";

const globals = globalThis as typeof globalThis & {
  Buffer?: typeof Buffer;
  global?: typeof globalThis;
  process?: { browser?: boolean; env?: Record<string, string> };
};

globals.Buffer = globals.Buffer || Buffer;
globals.global = globals.global || globalThis;
globals.process = globals.process || { browser: true, env: {} };

await import("./app");
