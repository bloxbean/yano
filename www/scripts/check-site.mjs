import { readFile, readdir, stat } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
const dist = resolve(dirname(fileURLToPath(import.meta.url)), '../dist');
async function walk(dir) {
  const result = [];
  for (const e of await readdir(dir, { withFileTypes: true })) {
    const p = resolve(dir, e.name);
    result.push(...(e.isDirectory() ? await walk(p) : [p]));
  }
  return result;
}
const all = await walk(dist),
  failures = [];
const htmlFiles = new Map(
  await Promise.all(
    all
      .filter((f) => f.endsWith('.html'))
      .map(async (file) => [file, await readFile(file, 'utf8')]),
  ),
);
const ids = new Map(
  [...htmlFiles].map(([file, html]) => [
    file,
    new Set([...html.matchAll(/id="([^"]+)"/g)].map((m) => m[1])),
  ]),
);
for (const file of all.filter((f) => f.endsWith('.html'))) {
  const html = await readFile(file, 'utf8');
  if (/\bADR(?:s|[-\s]\d+)?\b|(?:\.\.\/|\/)adr\//i.test(html))
    failures.push(`Internal reference: ${file}`);
  for (const match of html.matchAll(/(?:href|src)="([^"]+)"/g)) {
    const value = match[1].replaceAll('&amp;', '&');
    if (value.startsWith('#')) {
      const id = decodeURIComponent(value.slice(1));
      if (id && !ids.get(file).has(id))
        failures.push(`Missing anchor in ${file.replace(dist, '')}: ${value}`);
      continue;
    }
    if (!value.startsWith('/') || value.startsWith('//')) continue;
    const path = decodeURIComponent(value.split(/[?#]/)[0]);
    if (!path) continue;
    const target = resolve(dist, '.' + path);
    try {
      const s = await stat(target);
      const document = s.isDirectory() ? resolve(target, 'index.html') : target;
      if (s.isDirectory()) await stat(document);
      const fragment = value.split('#')[1];
      if (fragment && ids.has(document) && !ids.get(document).has(decodeURIComponent(fragment)))
        failures.push(`Missing anchor in ${file.replace(dist, '')}: ${value}`);
    } catch {
      failures.push(`Broken link in ${file.replace(dist, '')}: ${value}`);
    }
  }
}
const manifest = JSON.parse(await readFile(resolve(dist, 'ai/manifest.json'), 'utf8'));
for (const item of [
  ...manifest.pages.map((p) => ({ url: p.markdown, sha256: p.sha256 })),
  ...manifest.artifacts,
]) {
  const bytes = await readFile(resolve(dist, '.' + new URL(item.url).pathname));
  if (createHash('sha256').update(bytes).digest('hex') !== item.sha256)
    failures.push(`Checksum mismatch: ${item.url}`);
}
if (manifest.pages.length < 30) failures.push('Unexpectedly incomplete documentation export');
if (failures.length) throw new Error([...new Set(failures)].join('\n'));
console.log(
  `Verified ${all.filter((f) => f.endsWith('.html')).length} HTML pages: local assets and links resolve; no internal-document references.`,
);
