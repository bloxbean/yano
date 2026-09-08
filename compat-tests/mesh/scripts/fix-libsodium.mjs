// Some published `libsodium-wrappers-sumo` builds ship an ESM entry
// (`libsodium-wrappers.mjs`) that imports `./libsodium-sumo.mjs`, but that file is
// only published in the SIBLING `libsodium-sumo` package. Node then fails at module
// resolution the moment the Cardano serialisation stack is imported:
//
//   ERR_MODULE_NOT_FOUND .../libsodium-wrappers-sumo/dist/modules-sumo-esm/libsodium-sumo.mjs
//
// Runs as a postinstall hook. Paths are DISCOVERED rather than hardcoded: the
// affected directory differs between published versions (`modules-sumo` vs
// `modules-sumo-esm`), and only some dependency trees resolve an affected version
// at all. Idempotent, and a no-op when there is nothing to fix.
import { copyFileSync, existsSync, readdirSync, statSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const modules = join(root, 'node_modules');

/** Every file with the given name anywhere under `dir`. */
function findAll(dir, name, hits = []) {
  if (!existsSync(dir)) return hits;
  for (const entry of readdirSync(dir)) {
    const full = join(dir, entry);
    let st;
    try {
      st = statSync(full);
    } catch {
      continue; // broken symlink
    }
    if (st.isDirectory()) findAll(full, name, hits);
    else if (entry === name) hits.push(full);
  }
  return hits;
}

const sources = findAll(join(modules, 'libsodium-sumo'), 'libsodium-sumo.mjs');
if (sources.length === 0) {
  console.log('[fix-libsodium] no libsodium-sumo.mjs to copy, nothing to do');
  process.exit(0);
}

// Each ESM wrapper expects libsodium-sumo.mjs as its own sibling.
const importers = findAll(join(modules, 'libsodium-wrappers-sumo'), 'libsodium-wrappers.mjs');
let copied = 0;
for (const importer of importers) {
  const target = join(dirname(importer), 'libsodium-sumo.mjs');
  if (existsSync(target)) continue;
  copyFileSync(sources[0], target);
  console.log(`[fix-libsodium] ${target}`);
  copied += 1;
}

if (copied === 0) {
  console.log(`[fix-libsodium] ${importers.length} ESM wrapper(s) already resolvable`);
} else {
  console.log(`[fix-libsodium] patched ${copied} of ${importers.length} ESM wrapper(s)`);
}
