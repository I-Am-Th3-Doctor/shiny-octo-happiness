/**
 * Minimal database seam so the same SQL runs on real Postgres (production)
 * and PGlite (in-process Postgres for zero-setup dev and tests).
 */
export interface Sql {
  query<T = Record<string, unknown>>(text: string, params?: unknown[]): Promise<T[]>;
}

export interface Db extends Sql {
  tx<T>(fn: (sql: Sql) => Promise<T>): Promise<T>;
  close(): Promise<void>;
}

export const SCHEMA = /* sql */ `
create table if not exists users (
  id                uuid primary key,
  login_id          text not null unique,      -- H(username), the server never sees the username
  auth_hash         text not null,             -- sha256(authKey), authKey is already Argon2id-derived
  kdf               jsonb not null,
  wrapped_vault_key text not null,             -- vault key encrypted under the client-only KEK
  seq               bigint not null default 0, -- per-user record sequence, row lock serializes writers
  created_at        timestamptz not null default now()
);

create table if not exists devices (
  id              uuid primary key,
  user_id         uuid not null references users(id) on delete cascade,
  name_ciphertext text not null,
  created_at      timestamptz not null default now(),
  last_seen_at    timestamptz not null default now()
);

create table if not exists sessions (
  token_hash text primary key,
  user_id    uuid not null references users(id) on delete cascade,
  device_id  uuid references devices(id) on delete cascade,
  created_at timestamptz not null default now(),
  expires_at timestamptz not null
);

create table if not exists records (
  user_id     uuid   not null references users(id) on delete cascade,
  collection  text   not null,
  id          text   not null,
  ciphertext  text   not null,
  modified_at bigint not null,
  device_id   uuid   not null,
  seq         bigint not null,
  primary key (user_id, collection, id)
);
create index if not exists records_user_seq on records (user_id, seq);
`;

export async function migrate(db: Db): Promise<void> {
  await db.tx(async (sql) => {
    for (const stmt of SCHEMA.split(';').map((s) => s.trim()).filter(Boolean)) await sql.query(stmt);
  });
}

export async function createPostgresDb(url: string): Promise<Db> {
  const { default: postgres } = await import('postgres');
  // No query logging: statements carry ciphertext and token hashes, nothing worth leaking anyway.
  const pg = postgres(url, { max: 10, onnotice: () => {} });
  const wrap = (s: { unsafe: (q: string, p?: never[]) => Promise<unknown> }): Sql => ({
    query: async <T>(text: string, params: unknown[] = []) => [...((await s.unsafe(text, params as never[])) as T[])],
  });
  return {
    ...wrap(pg),
    tx: (fn) => pg.begin((t) => fn(wrap(t as never))) as never,
    close: () => pg.end(),
  };
}

export async function createPgliteDb(dataDir?: string): Promise<Db> {
  const { PGlite } = await import('@electric-sql/pglite');
  if (dataDir) (await import('node:fs')).mkdirSync(dataDir, { recursive: true });
  const pg = new PGlite(dataDir);
  const wrap = (s: { query: (q: string, p?: unknown[]) => Promise<{ rows: unknown[] }> }): Sql => ({
    query: async <T>(text: string, params: unknown[] = []) => (await s.query(text, params)).rows as T[],
  });
  return {
    ...wrap(pg),
    tx: (fn) => pg.transaction((t) => fn(wrap(t))),
    close: () => pg.close(),
  };
}
