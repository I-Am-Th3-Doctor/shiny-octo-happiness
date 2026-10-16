import { serve } from '@hono/node-server';
import { getConnInfo } from '@hono/node-server/conninfo';
import { createApp } from './app.ts';
import { createPgliteDb, createPostgresDb, migrate } from './db.ts';

const port = Number(process.env.PORT ?? 8787);
const host = process.env.HOST ?? '127.0.0.1';
const trustProxy = process.env.TRUST_PROXY === '1';

const db = process.env.DATABASE_URL
  ? await createPostgresDb(process.env.DATABASE_URL)
  : await createPgliteDb(process.env.PGLITE_DIR ?? (process.env.PGLITE_MEMORY === '1' ? undefined : '.data/pglite'));
await migrate(db);

const app = createApp({
  db,
  clientKey: (c) =>
    (trustProxy ? c.req.header('x-forwarded-for')?.split(',')[0]?.trim() : undefined) ??
    getConnInfo(c).remote.address ??
    'unknown',
  log: process.env.QUIET === '1' ? undefined : (line) => console.log(line),
});

const server = serve({ fetch: app.fetch, port, hostname: host }, (info) => {
  console.log(
    `omnisync backend on http://${info.address}:${info.port} (${process.env.DATABASE_URL ? 'postgres' : 'pglite'})`,
  );
});

for (const sig of ['SIGINT', 'SIGTERM'] as const) {
  process.on(sig, () => {
    server.close();
    void db.close().then(() => process.exit(0));
  });
}
