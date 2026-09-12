import { readFile, writeFile, mkdir, readdir, rm } from 'node:fs/promises';
import { resolve, relative, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { parse } from 'yaml';
const site = 'https://getyano.dev';
const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const repo = resolve(root, '..');
const docs = resolve(root, 'src/content/docs');
const publicDir = resolve(root, 'public');
const sha = execFileSync('git', ['rev-parse', 'HEAD'], { cwd: repo, encoding: 'utf8' }).trim();
const version = (await readFile(resolve(repo, 'gradle.properties'), 'utf8')).match(
  /^version\s*=\s*(.+)$/m,
)?.[1];
const hash = (text) => createHash('sha256').update(text).digest('hex');
async function files(dir, suffix) {
  const entries = await readdir(dir, { withFileTypes: true });
  const nested = await Promise.all(
    entries
      .sort((a, b) => a.name.localeCompare(b.name))
      .map((e) =>
        e.isDirectory()
          ? files(resolve(dir, e.name), suffix)
          : e.name.endsWith(suffix)
            ? [resolve(dir, e.name)]
            : [],
      ),
  );
  return nested.flat();
}
async function save(path, content) {
  await mkdir(dirname(path), { recursive: true });
  await writeFile(path, content);
}
const json = (value) => JSON.stringify(value, null, 2) + '\n';
// This directory is entirely generated; remove obsolete page exports on rebuild.
await rm(resolve(publicDir, 'ai'), { recursive: true, force: true });
const configuration = [];
function flatten(value, prefix = '', rows = []) {
  if (value && typeof value === 'object' && !Array.isArray(value)) {
    for (const [key, item] of Object.entries(value))
      flatten(item, prefix ? `${prefix}.${key}` : key, rows);
  } else rows.push({ key: prefix, value });
  return rows;
}
const configFiles = [
  'app/src/main/resources/application.yml',
  ...(await files(resolve(repo, 'app/config'), '.yml'))
    .filter((f) => /\/application[^/]*\.yml$/.test(f))
    .map((f) => relative(repo, f)),
];
for (const source of configFiles) {
  const data = parse(await readFile(resolve(repo, source), 'utf8'));
  const profiles = [
    [null, Object.fromEntries(Object.entries(data).filter(([key]) => !key.startsWith('%')))],
    ...Object.entries(data)
      .filter(([key]) => key.startsWith('%'))
      .map(([key, value]) => [key.slice(1), value]),
  ];
  for (const [profile, settings] of profiles) {
    const values = flatten(settings).filter(
      (row) =>
        row.key.startsWith('yano.') ||
        row.key.startsWith('quarkus.http.') ||
        row.key.startsWith('quarkus.swagger-ui.'),
    );
    // Public demo identities are not copied into the machine-readable catalog.
    for (const row of values)
      if (/signing-key|api-key|secret|password/i.test(row.key) && row.value)
        row.value = '<configure privately; see local demo profile for test-only identity>';
    if (values.length) configuration.push({ source, profile, values });
  }
}
const keysSource = 'core-api/src/main/java/org/yanoproject/api/config/YanoPropertyKeys.java';
const keyText = await readFile(resolve(repo, keysSource), 'utf8');
const declaredKeys = [
  ...new Set(
    [...keyText.matchAll(/public static final String\s+\w+\s*=\s*"(yano\.[^"]+)"/g)].map(
      (m) => m[1],
    ),
  ),
].sort();
await save(
  resolve(publicDir, 'ai/configuration.json'),
  json({
    sourceRevision: sha,
    version,
    note: 'Active packaged YAML values by source file, not resolved runtime defaults. Declared keys may require feature-specific validation. Comments and commented examples are excluded.',
    configuration,
    declaredKeys,
  }),
);
const inline = (value) =>
  '`' +
  (typeof value === 'string' ? value : JSON.stringify(value))
    .replaceAll('`', '\\`')
    .replaceAll('|', '&#124;')
    .replaceAll('\n', ' ') +
  '`';
let catalog = `---\ntitle: Configuration catalog\ndescription: Active packaged configuration values and declared property keys, generated from source.\nsidebar:\n  order: 2\n---\n\nThis reference is generated from the checked-out source. It keeps **bundled defaults and profile overrides separate**. Values are not a merged runtime configuration. Environment expressions retain their exact syntax. Comments and commented examples are excluded.\n\nSee [configuration layering](/reference/configuration/) before applying settings. Download [the JSON catalog](/ai/configuration.json). Secret-like populated fields are redacted; the local app-chain demo identity is intentionally not an operator credential.\n`;
for (const group of configuration) {
  catalog += `\n## ${group.source === configFiles[0] ? 'Bundled application defaults' : group.source.split('/').pop()}${group.profile ? ` — ${group.profile} profile` : ''}\n\nSource: [${group.source}](https://github.com/bloxbean/yano/blob/${sha}/${group.source})\n\n| Property | Packaged value |\n| --- | --- |\n`;
  for (const { key, value } of group.values) catalog += `| ${inline(key)} | ${inline(value)} |\n`;
}
catalog +=
  '\n## Declared property keys\n\nThese literal property names are declared by the public configuration contract. Presence here does not imply a default or support for arbitrary values. Consult the feature guide and runtime validation for constraints.\n\n';
catalog += declaredKeys.map((key) => `- ${inline(key)}`).join('\n') + '\n';
await save(resolve(docs, 'reference/configuration-catalog.md'), catalog);
// A path inventory, deliberately not represented as an OpenAPI schema.
const routes = [];
for (const file of await files(resolve(repo, 'app/src/main/java'), '.java')) {
  const source = (await readFile(file, 'utf8'))
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/^\s*\/\/.*$/gm, '');
  const classPos = source.search(/public\s+(?:final\s+)?class\s/);
  if (classPos < 0) continue;
  const base = source.slice(0, classPos).match(/@Path\("([^"]*)"\)/)?.[1];
  if (base === undefined) continue;
  const body = source.slice(classPos);
  for (const match of body.matchAll(
    /@(GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS)\b([\s\S]*?)\bpublic\s+(?:[\w<>?,.\[\]]+\s+)+(\w+)\s*\(/g,
  )) {
    const child = match[2].match(/@Path\("([^"]*)"\)/)?.[1] || '';
    const path =
      '/api/v1/' +
      [base, child]
        .map((s) => s.replace(/^\/+|\/+$/g, ''))
        .filter(Boolean)
        .join('/');
    routes.push({
      method: match[1],
      path,
      handler: match[3],
      source: relative(repo, file),
      hidden: /hidden\s*=\s*true/.test(match[2]),
    });
  }
}
routes.sort((a, b) => a.path.localeCompare(b.path) || a.method.localeCompare(b.method));
await save(
  resolve(publicDir, 'ai/routes.json'),
  json({
    sourceRevision: sha,
    version,
    apiPrefix: '/api/v1',
    note: 'Static literal JAX-RS annotation inventory. Not OpenAPI; excludes generated/dynamic plugin routes and does not encode schemas, authentication, feature gates, or availability. Obtain /q/openapi?format=json from your actual artifact.',
    routes,
  }),
);
const pages = [];
let full = '# Yano documentation\n\n';
for (const file of await files(docs, '.md')) {
  const raw = await readFile(file, 'utf8');
  if (/\bADR(?:s|[-\s]\d+)?\b|(?:\.\.\/|\/)adr\//i.test(raw))
    throw new Error(`Internal reference in ${file}`);
  const match = raw.match(/^---\n([\s\S]*?)\n---\n([\s\S]*)$/);
  if (!match) throw new Error(`Missing frontmatter: ${file}`);
  for (const body of raw.matchAll(/-d\s+'(\{[^']+\})'/g)) {
    try {
      JSON.parse(body[1]);
    } catch {
      throw new Error(`Invalid literal JSON request body in ${file}: ${body[1]}`);
    }
  }
  const data = parse(match[1]);
  const slug = relative(docs, file).replace(/\.md$/, '');
  const url = `${site}/${slug}/`;
  const markdown = `# ${data.title}\n\n${data.description}\n\nCanonical URL: ${url}\n\n${match[2].trim()}\n`;
  await save(resolve(publicDir, `ai/pages/${slug}.md`), markdown);
  pages.push({
    title: data.title,
    description: data.description,
    url,
    markdown: `${site}/ai/pages/${slug}.md`,
    sha256: hash(markdown),
  });
  full += `\n---\n\n${markdown}`;
}
const instructions = `# Yano coding context\n\nRead ${site}/llms.txt and the relevant Markdown pages before generating code.\n\n- For node onboarding, download a release from https://github.com/bloxbean/yano/releases/tag/v0.1.0-pre12 and run the extracted launcher. Prefer the JVM distribution for app chains. Source builds are an advanced contributor workflow. Pre12 predates the current namespace and some advanced features; match APIs and SDK versions to the installed artifact.
- Yano is a pre-release Cardano data node in Java, with devnet tooling and an app-chain host. Do not claim complete production consensus validation.\n- Use Java 25. The Maven group and package root are org.yanoproject. Keep dependency namespaces such as com.bloxbean.cardano.client unchanged. The npm package is @bloxbean/yano-testkit.\n- Pin compatible node, library, plugin, and verifier versions. Check manifest.json and the installed artifact.\n- The only built-in app state machine is ordered-log. Other stock extensions and SDKs belong to Yano X.\n- Distinguish accepted messages, member finality, and L1 confirmation. A proof needs an independently trusted root.\n- Native builds cannot load plugin JARs dynamically. DuckLake history is JVM-only and fresh-sync only.\n- Wallet indexes require complete historical coverage; unavailable is not empty.\n- Use the actual node's /q/openapi?format=json for client schemas. routes.json is only an annotation inventory.\n- Never place external I/O in deterministic state application. Never suggest deleting signing journals as a recovery shortcut.\n`;
await save(resolve(publicDir, 'ai/agent-instructions.md'), instructions);
await save(resolve(publicDir, 'llms-full.txt'), full);
await save(
  resolve(publicDir, 'llms.txt'),
  `# Yano\n\n> A pre-release Cardano data node in Java: query chain state, test on local devnets, embed the runtime, and host app chains.\n\nJava 25. Maven group org.yanoproject. Built-in machine: ordered-log. Additional stock extensions: Yano X. No claim of complete production consensus validation.\n\n## Machine-readable resources\n\n- [Full docs](${site}/llms-full.txt)\n- [Agent instructions](${site}/ai/agent-instructions.md)\n- [Build manifest](${site}/ai/manifest.json)\n- [Configuration](${site}/ai/configuration.json)\n- [Route inventory](${site}/ai/routes.json)\n\n## Guides\n\n` +
    pages.map((p) => `- [${p.title}](${p.markdown}): ${p.description}`).join('\n') +
    '\n',
);
const artifacts = [];
for (const name of [
  'llms.txt',
  'llms-full.txt',
  'ai/agent-instructions.md',
  'ai/configuration.json',
  'ai/routes.json',
])
  artifacts.push({
    url: `${site}/${name}`,
    sha256: hash(await readFile(resolve(publicDir, name))),
  });
await save(
  resolve(publicDir, 'ai/manifest.json'),
  json({
    schemaVersion: 1,
    site,
    version,
    sourceRevision: sha,
    sourceRepository: 'https://github.com/bloxbean/yano',
    note: 'Source revision at build time. Page and artifact hashes describe the generated content, including local edits. Not a release publication claim.',
    pages,
    artifacts,
  }),
);
console.log(
  `Generated ${pages.length} Markdown exports, ${declaredKeys.length} property keys, and ${routes.length} REST operations.`,
);
