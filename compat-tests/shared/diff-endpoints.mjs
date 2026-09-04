// Diffs a recorded endpoint capture (from proxy.mjs) against a checked-in baseline.
//
//   node diff-endpoints.mjs <baseline.json> <capture.json>
//
// Exit 1 only when the capture contains an endpoint the SDK actually called and
// Yano answered with 4xx/5xx or a transport error. Added and removed endpoints
// are reported but do not fail: an SDK upgrade legitimately changes which paths
// it touches, and that is information, not a regression.
import { readFileSync, writeFileSync, existsSync } from 'node:fs';

const [baselinePath, capturePath] = process.argv.slice(2);
if (!baselinePath || !capturePath) {
  console.error('usage: diff-endpoints.mjs <baseline.json> <capture.json>');
  process.exit(2);
}
if (!existsSync(capturePath)) {
  console.error(`no capture at ${capturePath}`);
  process.exit(2);
}

const capture = JSON.parse(readFileSync(capturePath, 'utf8'));

if (!existsSync(baselinePath)) {
  writeFileSync(baselinePath, JSON.stringify(capture, null, 2) + '\n');
  console.log(`no baseline yet - wrote ${Object.keys(capture).length} endpoint(s) to ${baselinePath}`);
  console.log('Review it, then commit it: it becomes the record of what this SDK depends on.');
  process.exit(0);
}

const baseline = JSON.parse(readFileSync(baselinePath, 'utf8'));
const capturedKeys = Object.keys(capture).sort();
const baselineKeys = Object.keys(baseline).sort();

const added = capturedKeys.filter((k) => !(k in baseline));
const removed = baselineKeys.filter((k) => !(k in capture));

// An endpoint is broken only when it NEVER answered successfully. A 404 that is
// later followed by a 200 is the canonical-state polling loop - the harness waits
// for an outpoint to appear - and flagging that would fail every healthy run.
const broken = capturedKeys.filter((k) => {
  const statuses = Object.keys(capture[k].statuses ?? {});
  const anyOk = statuses.some((s) => Number(s) >= 200 && Number(s) < 400);
  const anyBad = statuses.some((s) => s === 'ERR' || Number(s) >= 400);
  return anyBad && !anyOk;
});

console.log(`captured ${capturedKeys.length} endpoint(s), baseline has ${baselineKeys.length}`);
for (const k of added) console.log(`  + new dependency   ${k}`);
for (const k of removed) console.log(`  - no longer called ${k}`);
for (const k of broken) {
  const st = Object.entries(capture[k].statuses).map(([s, n]) => `${s}x${n}`).join(' ');
  console.log(`  ! NEVER SUCCEEDED  ${k}  [${st}]`);
}

if (broken.length) {
  console.log(`\nFAIL: ${broken.length} endpoint(s) this SDK calls never answered successfully.`);
  process.exit(1);
}
console.log('\nOK: every endpoint this SDK called answered successfully.');
process.exit(0);
