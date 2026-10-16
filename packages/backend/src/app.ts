import { createHash, randomBytes, randomUUID, timingSafeEqual } from 'node:crypto';
import { Hono, type Context } from 'hono';
import { bodyLimit } from 'hono/body-limit';
import { z } from 'zod';
import {
  COLLECTIONS,
  LIMITS,
  MAX_WAIT_SECONDS,
  compareVersions,
  type DeviceInfo,
  type LoginResponse,
  type PullResponse,
  type PushResponse,
  type PushResult,
  type RegisterDeviceResponse,
  type RegisterResponse,
  type WaitResponse,
  type WireRecord,
} from '@omnisync/protocol';
import type { Db, Sql } from './db.ts';

const SESSION_TTL_MS = 90 * 24 * 60 * 60 * 1000;

const b64url = z.string().regex(/^[A-Za-z0-9_-]+$/);
const envelope = z
  .string()
  .regex(/^v\d+\.[A-Za-z0-9_-]+$/)
  .max(Math.ceil((LIMITS.maxCiphertextBytes * 4) / 3) + 8);

const kdfSchema = z.object({
  alg: z.literal('argon2id13'),
  opsLimit: z.number().int().min(2).max(64),
  memLimitBytes: z.number().int().min(19 * 1024 * 1024).max(1024 * 1024 * 1024),
});
const registerSchema = z.object({
  loginId: b64url.length(43),
  authKey: b64url.length(43),
  kdf: kdfSchema,
  wrappedVaultKey: envelope,
});
const loginSchema = z.object({ loginId: b64url.length(43), authKey: b64url.length(43) });
const deviceSchema = z.object({ deviceId: z.uuid().optional(), nameCiphertext: envelope });
const pushSchema = z.object({
  records: z
    .array(
      z.object({
        collection: z.enum(COLLECTIONS),
        id: z.string().min(1).max(LIMITS.maxRecordIdLength).regex(/^[A-Za-z0-9_:-]+$/),
        ciphertext: envelope,
        modifiedAt: z.number().int().nonnegative().max(Number.MAX_SAFE_INTEGER),
      }),
    )
    .min(1)
    .max(LIMITS.maxPushRecords),
});
const waitSchema = z.object({
  since: z.coerce.number().int().nonnegative(),
  timeout: z.coerce.number().int().min(1).max(MAX_WAIT_SECONDS).default(MAX_WAIT_SECONDS),
});
const pullSchema = z.object({
  since: z.coerce.number().int().nonnegative().default(0),
  limit: z.coerce.number().int().min(1).max(LIMITS.maxPullRecords).default(500),
});

export interface AppOptions {
  db: Db;
  /** Returns a client identifier for rate limiting (e.g. remote IP). */
  clientKey?: (c: Context) => string;
  /** Clock override for tests. */
  now?: () => number;
  /** Request log sink. Only method, route pattern, status and latency are ever passed. */
  log?: (line: string) => void;
}

type Vars = { userId: string; deviceId: string | null; tokenHash: string };

const sha256 = (s: string) => createHash('sha256').update(s).digest('hex');

function hashEquals(a: string, b: string): boolean {
  const x = Buffer.from(a, 'hex');
  const y = Buffer.from(b, 'hex');
  return x.length === y.length && timingSafeEqual(x, y);
}

// Compared against when the login id is unknown, so response timing doesn't reveal account existence.
const DUMMY_HASH = sha256('omnisync-dummy');

/** Fixed-window limiter for unauthenticated endpoints (password guessing via authKey is already Argon2id-bound client-side). */
function rateLimiter(max: number, windowMs: number, now: () => number) {
  const hits = new Map<string, { n: number; reset: number }>();
  return (key: string): boolean => {
    const t = now();
    if (hits.size > 10_000) for (const [k, v] of hits) if (v.reset <= t) hits.delete(k);
    const h = hits.get(key);
    if (!h || h.reset <= t) {
      hits.set(key, { n: 1, reset: t + windowMs });
      return true;
    }
    return ++h.n <= max;
  };
}

interface RecordRow {
  collection: string;
  id: string;
  ciphertext: string;
  modified_at: string | number;
  device_id: string;
  seq: string | number;
}
const toWire = (r: RecordRow): WireRecord => ({
  collection: r.collection as WireRecord['collection'],
  id: r.id,
  ciphertext: r.ciphertext,
  modifiedAt: Number(r.modified_at),
  deviceId: r.device_id,
  seq: Number(r.seq),
});

export function createApp(opts: AppOptions) {
  const { db } = opts;
  const now = opts.now ?? Date.now;
  const clientKey = opts.clientKey ?? (() => 'global');
  const authLimit = rateLimiter(20, 60_000, now);

  // Long-poll waiters per user. In-process: a multi-instance deployment would fan this out via
  // Postgres LISTEN/NOTIFY. Clients treat a timeout as "maybe" and still poll on an alarm.
  const waiters = new Map<string, Set<() => void>>();
  const notify = (userId: string) => {
    const set = waiters.get(userId);
    waiters.delete(userId);
    set?.forEach((wake) => wake());
  };

  const app = new Hono<{ Variables: Vars }>();

  app.use('*', async (c, next) => {
    const t0 = performance.now();
    await next();
    // routePath is the pattern ("/v1/records"), never the concrete URL, query string, or body.
    opts.log?.(`${c.req.method} ${c.req.routePath} ${c.res.status} ${Math.round(performance.now() - t0)}ms`);
  });
  app.use('*', bodyLimit({ maxSize: 4 * 1024 * 1024, onError: (c) => c.json({ error: 'payload too large' }, 413) }));
  app.onError((err, c) => {
    // Never echo error details; they could contain request content.
    opts.log?.(`error ${c.req.method} ${c.req.routePath}: ${err.name}`);
    return c.json({ error: 'internal error' }, 500);
  });

  const parse = async <T>(c: Context, schema: z.ZodType<T>): Promise<T | null> => {
    const body = await c.req.json().catch(() => undefined);
    const r = schema.safeParse(body);
    return r.success ? r.data : null;
  };

  app.get('/health', (c) => c.json({ ok: true }));

  app.post('/v1/accounts', async (c) => {
    if (!authLimit(clientKey(c))) return c.json({ error: 'too many requests' }, 429);
    const body = await parse(c, registerSchema);
    if (!body) return c.json({ error: 'invalid request' }, 400);
    const userId = randomUUID();
    const inserted = await db.query(
      `insert into users (id, login_id, auth_hash, kdf, wrapped_vault_key) values ($1, $2, $3, $4, $5)
       on conflict (login_id) do nothing returning id`,
      [userId, body.loginId, sha256(body.authKey), JSON.stringify(body.kdf), body.wrappedVaultKey],
    );
    if (inserted.length === 0) return c.json({ error: 'account exists' }, 409);
    return c.json<RegisterResponse>({ userId }, 201);
  });

  app.post('/v1/sessions', async (c) => {
    if (!authLimit(clientKey(c))) return c.json({ error: 'too many requests' }, 429);
    const body = await parse(c, loginSchema);
    if (!body) return c.json({ error: 'invalid request' }, 400);
    const [user] = await db.query<{ id: string; auth_hash: string; kdf: unknown; wrapped_vault_key: string }>(
      `select id, auth_hash, kdf, wrapped_vault_key from users where login_id = $1`,
      [body.loginId],
    );
    const ok = hashEquals(sha256(body.authKey), user?.auth_hash ?? DUMMY_HASH);
    if (!user || !ok) return c.json({ error: 'invalid credentials' }, 401);
    const token = randomBytes(32).toString('base64url');
    await db.query(`insert into sessions (token_hash, user_id, expires_at) values ($1, $2, $3)`, [
      sha256(token),
      user.id,
      new Date(now() + SESSION_TTL_MS).toISOString(),
    ]);
    const kdf = (typeof user.kdf === 'string' ? JSON.parse(user.kdf) : user.kdf) as LoginResponse['kdf'];
    return c.json<LoginResponse>({ token, userId: user.id, kdf, wrappedVaultKey: user.wrapped_vault_key }, 201);
  });

  // ---- everything below requires a bearer token
  const authed = new Hono<{ Variables: Vars }>();
  authed.use('*', async (c, next) => {
    const m = /^Bearer ([A-Za-z0-9_-]{43})$/.exec(c.req.header('authorization') ?? '');
    if (!m) return c.json({ error: 'unauthorized' }, 401);
    const tokenHash = sha256(m[1]!);
    const [s] = await db.query<{ user_id: string; device_id: string | null }>(
      `select user_id, device_id from sessions where token_hash = $1 and expires_at > $2`,
      [tokenHash, new Date(now()).toISOString()],
    );
    if (!s) return c.json({ error: 'unauthorized' }, 401);
    c.set('userId', s.user_id);
    c.set('deviceId', s.device_id);
    c.set('tokenHash', tokenHash);
    await next();
  });

  authed.delete('/sessions/current', async (c) => {
    await db.query(`delete from sessions where token_hash = $1`, [c.get('tokenHash')]);
    return c.body(null, 204);
  });

  authed.post('/devices', async (c) => {
    const body = await parse(c, deviceSchema);
    if (!body) return c.json({ error: 'invalid request' }, 400);
    const userId = c.get('userId');
    let deviceId = body.deviceId;
    if (deviceId) {
      const rows = await db.query(
        `update devices set name_ciphertext = $3, last_seen_at = now() where id = $1 and user_id = $2 returning id`,
        [deviceId, userId, body.nameCiphertext],
      );
      if (rows.length === 0) deviceId = undefined; // unknown or revoked: issue a fresh id
    }
    if (!deviceId) {
      deviceId = randomUUID();
      await db.query(`insert into devices (id, user_id, name_ciphertext) values ($1, $2, $3)`, [
        deviceId,
        userId,
        body.nameCiphertext,
      ]);
    }
    await db.query(`update sessions set device_id = $1 where token_hash = $2`, [deviceId, c.get('tokenHash')]);
    return c.json<RegisterDeviceResponse>({ deviceId }, 201);
  });

  authed.get('/devices', async (c) => {
    const rows = await db.query<{ id: string; name_ciphertext: string; created_at: Date; last_seen_at: Date }>(
      `select id, name_ciphertext, created_at, last_seen_at from devices where user_id = $1 order by created_at`,
      [c.get('userId')],
    );
    return c.json<{ devices: DeviceInfo[] }>({
      devices: rows.map((r) => ({
        deviceId: r.id,
        nameCiphertext: r.name_ciphertext,
        createdAt: new Date(r.created_at).toISOString(),
        lastSeenAt: new Date(r.last_seen_at).toISOString(),
      })),
    });
  });

  authed.delete('/devices/:id', async (c) => {
    const id = c.req.param('id');
    if (!z.uuid().safeParse(id).success) return c.json({ error: 'invalid request' }, 400);
    await db.query(`delete from devices where id = $1 and user_id = $2`, [id, c.get('userId')]);
    return c.body(null, 204);
  });

  // Push: per-record last-write-wins, enforced atomically under the user's row lock.
  authed.post('/records', async (c) => {
    const deviceId = c.get('deviceId');
    if (!deviceId) return c.json({ error: 'register a device first' }, 409);
    const body = await parse(c, pushSchema);
    if (!body) return c.json({ error: 'invalid request' }, 400);
    const t = now();
    if (body.records.some((r) => r.modifiedAt > t + LIMITS.maxClockSkewMs)) {
      return c.json({ error: 'modifiedAt too far in the future; correct the client clock using serverTime' }, 400);
    }
    const userId = c.get('userId');

    const results = await db.tx(async (sql: Sql) => {
      // Locks the user row: concurrent pushes for this user serialize here, so seq values
      // are committed in increasing order and a pull cursor can never skip a record.
      const [u] = await sql.query<{ seq: string | number }>(`select seq from users where id = $1 for update`, [userId]);
      let seq = Number(u!.seq);
      const out: PushResult[] = [];
      for (const r of body.records) {
        const [cur] = await sql.query<RecordRow>(
          `select collection, id, ciphertext, modified_at, device_id, seq from records
           where user_id = $1 and collection = $2 and id = $3`,
          [userId, r.collection, r.id],
        );
        if (cur) {
          const cmp = compareVersions({ modifiedAt: r.modifiedAt, deviceId }, toWire(cur));
          if (cmp === 0) {
            out.push({ collection: r.collection, id: r.id, status: 'ok', seq: Number(cur.seq) }); // retry of the same write
            continue;
          }
          if (cmp < 0) {
            out.push({ collection: r.collection, id: r.id, status: 'stale', current: toWire(cur) });
            continue;
          }
        }
        seq += 1;
        await sql.query(
          `insert into records (user_id, collection, id, ciphertext, modified_at, device_id, seq)
           values ($1, $2, $3, $4, $5, $6, $7)
           on conflict (user_id, collection, id) do update set
             ciphertext = excluded.ciphertext, modified_at = excluded.modified_at,
             device_id = excluded.device_id, seq = excluded.seq`,
          [userId, r.collection, r.id, r.ciphertext, r.modifiedAt, deviceId, seq],
        );
        out.push({ collection: r.collection, id: r.id, status: 'ok', seq });
      }
      await sql.query(`update users set seq = $2 where id = $1`, [userId, seq]);
      return out;
    });
    if (results.some((r) => r.status === 'ok')) notify(userId);
    await db.query(`update devices set last_seen_at = now() where id = $1`, [deviceId]);
    return c.json<PushResponse>({ results, serverTime: now() });
  });

  authed.get('/records/wait', async (c) => {
    const q = waitSchema.safeParse({ since: c.req.query('since'), timeout: c.req.query('timeout') });
    if (!q.success) return c.json({ error: 'invalid request' }, 400);
    const userId = c.get('userId');
    const latest = async () => {
      const [u] = await db.query<{ seq: string | number }>(`select seq from users where id = $1`, [userId]);
      return Number(u?.seq ?? 0);
    };
    let changed = (await latest()) > q.data.since;
    if (!changed) {
      changed = await new Promise<boolean>((resolve) => {
        const set = waiters.get(userId) ?? new Set();
        waiters.set(userId, set);
        const done = (v: boolean) => {
          clearTimeout(timer);
          set.delete(wake);
          if (set.size === 0 && waiters.get(userId) === set) waiters.delete(userId);
          c.req.raw.signal?.removeEventListener('abort', onAbort);
          resolve(v);
        };
        const wake = () => done(true);
        const onAbort = () => done(false);
        const timer = setTimeout(() => done(false), q.data.timeout * 1000);
        set.add(wake);
        c.req.raw.signal?.addEventListener('abort', onAbort);
      });
    }
    return c.json<WaitResponse>({ changed, serverTime: now() });
  });

  // Pull: everything with seq > since, in seq order. Only the latest version of each record exists.
  authed.get('/records', async (c) => {
    const q = pullSchema.safeParse({ since: c.req.query('since'), limit: c.req.query('limit') });
    if (!q.success) return c.json({ error: 'invalid request' }, 400);
    const { since, limit } = q.data;
    const rows = await db.query<RecordRow>(
      `select collection, id, ciphertext, modified_at, device_id, seq from records
       where user_id = $1 and seq > $2 order by seq limit $3`,
      [c.get('userId'), since, limit + 1],
    );
    const hasMore = rows.length > limit;
    const records = rows.slice(0, limit).map(toWire);
    return c.json<PullResponse>({
      records,
      cursor: records.at(-1)?.seq ?? since,
      hasMore,
      serverTime: now(),
    });
  });

  app.route('/v1', authed);
  app.notFound((c) => c.json({ error: 'not found' }, 404));
  return app;
}
