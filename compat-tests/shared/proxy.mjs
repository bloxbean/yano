// Recording reverse proxy: forwards to Yano and logs every request an SDK makes.
// This is how we learn which Blockfrost-shaped endpoints each JS SDK depends on,
// and which of them Yano does not implement.
import http from 'node:http';
import { writeFileSync } from 'node:fs';

const LISTEN = Number(process.env.PROXY_PORT ?? 7099);
const TARGET = new URL(process.env.YANO_URL ?? 'http://localhost:7070');
const OUT = process.env.PROXY_LOG ?? 'proxy-calls.json';

const calls = new Map(); // "METHOD /path" -> {count, statuses:{}}

function normalize(pathname) {
  return pathname
    .replace(/\/addresses\/[a-z0-9_]+/i, '/addresses/{addr}')
    .replace(/\/(txs|blocks|scripts|utxos)\/[0-9a-f]{64}/gi, '/$1/{hash}')
    .replace(/\/datum\/[0-9a-f]{64}/gi, '/datum/{hash}')
    .replace(/\/[0-9a-f]{56}(\/|$)/gi, '/{hash28}$1')
    .replace(/\/\d+(\/|$)/g, '/{n}$1');
}

const server = http.createServer((req, res) => {
  const url = new URL(req.url, `http://localhost:${LISTEN}`);
  const key = `${req.method} ${normalize(url.pathname)}`;

  const chunks = [];
  req.on('data', (c) => chunks.push(c));
  req.on('end', () => {
    const body = Buffer.concat(chunks);
    const proxyReq = http.request(
      {
        hostname: TARGET.hostname,
        port: TARGET.port,
        path: req.url,
        method: req.method,
        headers: { ...req.headers, host: `${TARGET.hostname}:${TARGET.port}` },
      },
      (proxyRes) => {
        const entry = calls.get(key) ?? { count: 0, statuses: {} };
        entry.count += 1;
        entry.statuses[proxyRes.statusCode] = (entry.statuses[proxyRes.statusCode] ?? 0) + 1;
        calls.set(key, entry);
        res.writeHead(proxyRes.statusCode, proxyRes.headers);
        proxyRes.pipe(res);
      },
    );
    proxyReq.on('error', (e) => {
      const entry = calls.get(key) ?? { count: 0, statuses: {} };
      entry.count += 1;
      entry.statuses['ERR'] = (entry.statuses['ERR'] ?? 0) + 1;
      calls.set(key, entry);
      res.writeHead(502).end(JSON.stringify({ error: String(e) }));
    });
    if (body.length) proxyReq.write(body);
    proxyReq.end();
  });
});

function dump() {
  const out = {};
  for (const [k, v] of [...calls.entries()].sort()) out[k] = v;
  writeFileSync(OUT, JSON.stringify(out, null, 2));
  console.log(`\n--- endpoints called (${calls.size} distinct) -> ${OUT} ---`);
  for (const [k, v] of [...calls.entries()].sort()) {
    const st = Object.entries(v.statuses).map(([s, n]) => `${s}x${n}`).join(' ');
    const bad = Object.keys(v.statuses).some((s) => s === 'ERR' || Number(s) >= 400);
    console.log(`${bad ? 'FAIL' : ' ok '} ${String(v.count).padStart(5)}  ${k}  [${st}]`);
  }
}

process.on('SIGINT', () => { dump(); process.exit(0); });
process.on('SIGTERM', () => { dump(); process.exit(0); });
// Keep the file current even if the process is hard-killed by a supervisor.
setInterval(() => { if (calls.size) writeFileSync(OUT, JSON.stringify(Object.fromEntries([...calls.entries()].sort()), null, 2)); }, 3000).unref?.();

server.listen(LISTEN, () => {
  console.log(`recording proxy :${LISTEN} -> ${TARGET.origin}`);
});
