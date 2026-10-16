import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import * as oc from '@omnisync/crypto';
import type { LoginResponse, PullResponse, PushResponse, WireRecord } from '@omnisync/protocol';
import { createApp } from '../src/app.ts';
import { type Db, createPgliteDb, createPostgresDb, migrate } from '../src/db.ts';

// Runs against in-process PGlite by default; set TEST_DATABASE_URL to also exercise real Postgres.
const targets: [string, () => Promise<Db>][] = [['pglite', () => createPgliteDb()]];
if (process.env.TEST_DATABASE_URL) targets.push(['postgres', () => createPostgresDb(process.env.TEST_DATABASE_URL!)]);

const FAST = { alg: 'argon2id13', opsLimit: 1, memLimitBytes: 8192 } as const;
const KDF_WIRE = { alg: 'argon2id13', opsLimit: 3, memLimitBytes: 64 * 1024 * 1024 } as const;

describe.each(targets)('backend on %s', (_name, makeDb) => {
  let db: Db;
  let app: ReturnType<typeof createApp>;
  let clock = 1_700_000_000_000;
  const logs: string[] = [];

  beforeAll(async () => {
    await oc.ready();
    db = await makeDb();
    await migrate(db);
  });
  afterAll(() => db.close());
  beforeEach(async () => {
    await db.query('truncate users cascade');
    logs.length = 0;
    app = createApp({ db, now: () => clock, log: (l) => logs.push(l) });
  });

  const req = async <T = any>(method: string, path: string, body?: unknown, token?: string) => {
    const res = await app.request(path, {
      method,
      headers: { 'content-type': 'application/json', ...(token ? { authorization: `Bearer ${token}` } : {}) },
      body: body === undefined ? undefined : typeof body === 'string' ? body : JSON.stringify(body),
    });
    const text = await res.text();
    return { status: res.status, body: (text ? JSON.parse(text) : null) as T };
  };

  /** Creates an account the same way the extension does and returns everything a client holds. */
  async function account(username = 'alice@example.com', password = 'correct horse battery staple') {
    const keys = oc.deriveMasterKeys(password, username, FAST, { unsafeAllowWeakParams: true });
    const vaultKey = oc.generateVaultKey();
    const loginId = oc.deriveLoginId(username);
    const reg = await req('POST', '/v1/accounts', {
      loginId,
      authKey: oc.toB64(keys.authKey),
      kdf: KDF_WIRE,
      wrappedVaultKey: oc.wrapVaultKey(vaultKey, keys.kek),
    });
    expect(reg.status).toBe(201);
    return { username, password, keys, vaultKey, loginId };
  }

  async function device(acct: Awaited<ReturnType<typeof account>>, name = 'laptop') {
    const login = await req<LoginResponse>('POST', '/v1/sessions', {
      loginId: acct.loginId,
      authKey: oc.toB64(acct.keys.authKey),
    });
    expect(login.status).toBe(201);
    const nameCiphertext = oc.encryptRecord(
      acct.vaultKey,
      { collection: 'device', id: 'name', modifiedAt: 0, deviceId: 'self' },
      name,
    );
    const d = await req('POST', '/v1/devices', { nameCiphertext }, login.body.token);
    expect(d.status).toBe(201);
    return { token: login.body.token, deviceId: d.body.deviceId as string, login: login.body };
  }

  const rec = (vaultKey: Uint8Array, deviceId: string, id: string, modifiedAt: number, payload: unknown) => ({
    collection: 'bookmarks' as const,
    id,
    modifiedAt,
    ciphertext: oc.encryptRecord(vaultKey, { collection: 'bookmarks', id, modifiedAt, deviceId }, payload),
  });

  describe('accounts and sessions', () => {
    it('registers, logs in, and returns the wrapped vault key the client can unwrap', async () => {
      const a = await account();
      const { login } = await device(a);
      const vk = oc.unwrapVaultKey(login.wrappedVaultKey, a.keys.kek);
      expect(oc.toB64(vk)).toBe(oc.toB64(a.vaultKey));
      expect(login.kdf).toEqual(KDF_WIRE);
    });

    it('rejects duplicate accounts', async () => {
      const a = await account();
      const again = await req('POST', '/v1/accounts', {
        loginId: a.loginId,
        authKey: oc.toB64(a.keys.authKey),
        kdf: KDF_WIRE,
        wrappedVaultKey: oc.wrapVaultKey(a.vaultKey, a.keys.kek),
      });
      expect(again.status).toBe(409);
    });

    it('rejects a wrong password and an unknown account identically', async () => {
      const a = await account();
      const wrong = oc.deriveMasterKeys('wrong password!', a.username, FAST, { unsafeAllowWeakParams: true });
      const r1 = await req('POST', '/v1/sessions', { loginId: a.loginId, authKey: oc.toB64(wrong.authKey) });
      const r2 = await req('POST', '/v1/sessions', {
        loginId: oc.deriveLoginId('nobody@example.com'),
        authKey: oc.toB64(a.keys.authKey),
      });
      expect(r1).toEqual({ status: 401, body: { error: 'invalid credentials' } });
      expect(r2).toEqual(r1);
    });

    it('refuses weak KDF params at registration', async () => {
      const r = await req('POST', '/v1/accounts', {
        loginId: oc.deriveLoginId('x@y.z'),
        authKey: oc.toB64(oc.generateVaultKey()),
        kdf: { alg: 'argon2id13', opsLimit: 1, memLimitBytes: 8192 },
        wrappedVaultKey: 'v1.AAAA',
      });
      expect(r.status).toBe(400);
    });

    it('requires a valid bearer token and honours logout', async () => {
      expect((await req('GET', '/v1/records')).status).toBe(401);
      expect((await req('GET', '/v1/records', undefined, 'x'.repeat(43))).status).toBe(401);
      const d = await device(await account());
      expect((await req('GET', '/v1/records', undefined, d.token)).status).toBe(200);
      expect((await req('DELETE', '/v1/sessions/current', undefined, d.token)).status).toBe(204);
      expect((await req('GET', '/v1/records', undefined, d.token)).status).toBe(401);
    });

    it('expires sessions', async () => {
      const d = await device(await account());
      clock += 91 * 24 * 3600 * 1000;
      expect((await req('GET', '/v1/records', undefined, d.token)).status).toBe(401);
      clock = 1_700_000_000_000;
    });

    it('re-registering a known device keeps its id; revoking a device kills its sessions', async () => {
      const a = await account();
      const d1 = await device(a);
      const login = await req<LoginResponse>('POST', '/v1/sessions', {
        loginId: a.loginId,
        authKey: oc.toB64(a.keys.authKey),
      });
      const again = await req('POST', '/v1/devices', { deviceId: d1.deviceId, nameCiphertext: 'v1.AAAA' }, login.body.token);
      expect(again.body.deviceId).toBe(d1.deviceId);
      const list = await req('GET', '/v1/devices', undefined, d1.token);
      expect(list.body.devices).toHaveLength(1);
      expect((await req('DELETE', `/v1/devices/${d1.deviceId}`, undefined, d1.token)).status).toBe(204);
      expect((await req('GET', '/v1/records', undefined, d1.token)).status).toBe(401);
    });

    it('rate-limits unauthenticated endpoints', async () => {
      const statuses: number[] = [];
      for (let i = 0; i < 22; i++) {
        statuses.push((await req('POST', '/v1/sessions', { loginId: 'x'.repeat(43), authKey: 'y'.repeat(43) })).status);
      }
      expect(statuses.slice(0, 20).every((s) => s === 401)).toBe(true);
      expect(statuses.at(-1)).toBe(429);
    });
  });

  describe('records', () => {
    it('pushes and pulls ciphertext between two devices of the same account', async () => {
      const a = await account();
      const d1 = await device(a, 'chrome');
      const d2 = await device(a, 'brave');
      const r = rec(a.vaultKey, d1.deviceId, 'bm1', clock, { title: 'Example', url: 'https://example.com' });
      const push = await req<PushResponse>('POST', '/v1/records', { records: [r] }, d1.token);
      expect(push.body.results).toEqual([{ collection: 'bookmarks', id: 'bm1', status: 'ok', seq: 1 }]);
      const pull = await req<PullResponse>('GET', '/v1/records?since=0', undefined, d2.token);
      expect(pull.body.records).toHaveLength(1);
      const w = pull.body.records[0]!;
      expect(w.deviceId).toBe(d1.deviceId);
      expect(oc.decryptRecord(a.vaultKey, w, w.ciphertext)).toEqual({ title: 'Example', url: 'https://example.com' });
      expect(pull.body.cursor).toBe(1);
      const empty = await req<PullResponse>('GET', `/v1/records?since=${pull.body.cursor}`, undefined, d2.token);
      expect(empty.body).toMatchObject({ records: [], cursor: 1, hasMore: false });
    });

    it('requires a registered device to push', async () => {
      const a = await account();
      const login = await req<LoginResponse>('POST', '/v1/sessions', { loginId: a.loginId, authKey: oc.toB64(a.keys.authKey) });
      const r = rec(a.vaultKey, 'x', 'bm1', clock, {});
      expect((await req('POST', '/v1/records', { records: [r] }, login.body.token)).status).toBe(409);
    });

    it('stores nothing but ciphertext', async () => {
      const a = await account();
      const d = await device(a, 'My Secret Laptop');
      await req('POST', '/v1/records', {
        records: [rec(a.vaultKey, d.deviceId, 'bm1', clock, { title: 'TOPSECRET', url: 'https://hidden.example' })],
      }, d.token);
      const dump = JSON.stringify([
        await db.query('select * from users'),
        await db.query('select * from devices'),
        await db.query('select * from sessions'),
        await db.query('select * from records'),
      ]);
      for (const needle of ['TOPSECRET', 'hidden.example', 'alice', 'My Secret Laptop', d.token, oc.toB64(a.keys.authKey)]) {
        expect(dump).not.toContain(needle);
      }
    });

    describe('last-write-wins', () => {
      it('accepts newer writes and reports older ones as stale with the current version', async () => {
        const a = await account();
        const d1 = await device(a);
        const d2 = await device(a);
        await req('POST', '/v1/records', { records: [rec(a.vaultKey, d1.deviceId, 'x', 2000, { v: 'new' })] }, d1.token);
        const stale = await req<PushResponse>('POST', '/v1/records', {
          records: [rec(a.vaultKey, d2.deviceId, 'x', 1000, { v: 'old' })],
        }, d2.token);
        expect(stale.body.results[0]).toMatchObject({ status: 'stale', current: { modifiedAt: 2000, deviceId: d1.deviceId } });
        const cur = (stale.body.results[0] as { current: WireRecord }).current;
        expect(oc.decryptRecord(a.vaultKey, cur, cur.ciphertext)).toEqual({ v: 'new' });

        const newer = await req<PushResponse>('POST', '/v1/records', {
          records: [rec(a.vaultKey, d2.deviceId, 'x', 3000, { v: 'newest' })],
        }, d2.token);
        expect(newer.body.results[0]).toMatchObject({ status: 'ok', seq: 2 });
      });

      it('breaks exact timestamp ties by device id', async () => {
        const a = await account();
        const [d1, d2] = [await device(a), await device(a)];
        const [lo, hi] = d1.deviceId < d2.deviceId ? [d1, d2] : [d2, d1];
        await req('POST', '/v1/records', { records: [rec(a.vaultKey, hi.deviceId, 'x', 5000, { by: 'hi' })] }, hi.token);
        const r = await req<PushResponse>('POST', '/v1/records', { records: [rec(a.vaultKey, lo.deviceId, 'x', 5000, { by: 'lo' })] }, lo.token);
        expect(r.body.results[0]!.status).toBe('stale');
      });

      it('treats a retried identical write as ok without bumping seq', async () => {
        const a = await account();
        const d = await device(a);
        const r = rec(a.vaultKey, d.deviceId, 'x', 5000, {});
        await req('POST', '/v1/records', { records: [r] }, d.token);
        const again = await req<PushResponse>('POST', '/v1/records', { records: [r] }, d.token);
        expect(again.body.results[0]).toEqual({ collection: 'bookmarks', id: 'x', status: 'ok', seq: 1 });
      });

      it('rejects timestamps from the far future', async () => {
        const a = await account();
        const d = await device(a);
        const r = await req('POST', '/v1/records', { records: [rec(a.vaultKey, d.deviceId, 'x', clock + 11 * 60_000, {})] }, d.token);
        expect(r.status).toBe(400);
      });
    });

    it('paginates with a monotonic cursor and returns only the latest version of each record', async () => {
      const a = await account();
      const d = await device(a);
      const records = Array.from({ length: 25 }, (_, i) => rec(a.vaultKey, d.deviceId, `r${i}`, 1000 + i, { i }));
      await req('POST', '/v1/records', { records }, d.token);
      await req('POST', '/v1/records', { records: [rec(a.vaultKey, d.deviceId, 'r0', 9999, { i: 'updated' })] }, d.token);
      const seen: WireRecord[] = [];
      let since = 0;
      for (;;) {
        const p = await req<PullResponse>('GET', `/v1/records?since=${since}&limit=10`, undefined, d.token);
        seen.push(...p.body.records);
        since = p.body.cursor;
        if (!p.body.hasMore) break;
      }
      expect(seen).toHaveLength(25);
      expect(seen.map((r) => r.seq)).toEqual([...seen.map((r) => r.seq)].sort((x, y) => x - y));
      expect(seen.at(-1)).toMatchObject({ id: 'r0', modifiedAt: 9999 });
    });

    it('serializes concurrent pushes so seqs are unique and none are skipped', async () => {
      const a = await account();
      const devs = await Promise.all([device(a), device(a), device(a)]);
      await Promise.all(
        devs.flatMap((d, di) =>
          Array.from({ length: 5 }, (_, i) =>
            req('POST', '/v1/records', { records: [rec(a.vaultKey, d.deviceId, `d${di}-${i}`, 1000, {})] }, d.token),
          ),
        ),
      );
      const p = await req<PullResponse>('GET', '/v1/records?since=0&limit=100', undefined, devs[0]!.token);
      expect(p.body.records.map((r) => r.seq)).toEqual(Array.from({ length: 15 }, (_, i) => i + 1));
    });

    it('long-poll returns immediately when behind, wakes on a push, and times out otherwise', async () => {
      const a = await account();
      const d1 = await device(a);
      const d2 = await device(a);
      expect((await req('GET', '/v1/records/wait?since=0&timeout=1', undefined, d2.token)).body.changed).toBe(false);
      await req('POST', '/v1/records', { records: [rec(a.vaultKey, d1.deviceId, 'x', 1, {})] }, d1.token);
      expect((await req('GET', '/v1/records/wait?since=0', undefined, d2.token)).body.changed).toBe(true);

      const t0 = Date.now();
      const waiting = req('GET', '/v1/records/wait?since=1&timeout=20', undefined, d2.token);
      await new Promise((r) => setTimeout(r, 200));
      await req('POST', '/v1/records', { records: [rec(a.vaultKey, d1.deviceId, 'y', 2, {})] }, d1.token);
      expect((await waiting).body.changed).toBe(true);
      expect(Date.now() - t0).toBeLessThan(5000);
      expect((await req('GET', '/v1/records/wait?since=1&timeout=26', undefined, d2.token)).status).toBe(400);
    });

    it('isolates users from each other', async () => {
      const a = await account('alice@example.com');
      const b = await account('bob@example.com');
      const da = await device(a);
      const dbb = await device(b);
      await req('POST', '/v1/records', { records: [rec(a.vaultKey, da.deviceId, 'x', 1, {})] }, da.token);
      const p = await req<PullResponse>('GET', '/v1/records', undefined, dbb.token);
      expect(p.body.records).toEqual([]);
      // Same record id for bob is an independent record.
      const r = await req<PushResponse>('POST', '/v1/records', { records: [rec(b.vaultKey, dbb.deviceId, 'x', 0, {})] }, dbb.token);
      expect(r.body.results[0]!.status).toBe('ok');
    });

    it('validates input', async () => {
      const a = await account();
      const d = await device(a);
      const bad = [
        { records: [] },
        { records: [{ collection: 'nope', id: 'x', ciphertext: 'v1.AA', modifiedAt: 1 }] },
        { records: [{ collection: 'bookmarks', id: 'has space', ciphertext: 'v1.AA', modifiedAt: 1 }] },
        { records: [{ collection: 'bookmarks', id: 'x', ciphertext: 'plaintext!', modifiedAt: 1 }] },
        { records: [{ collection: 'bookmarks', id: 'x', ciphertext: 'v1.AA', modifiedAt: -1 }] },
      ];
      for (const b of bad) expect((await req('POST', '/v1/records', b, d.token)).status).toBe(400);
      expect((await req('POST', '/v1/records', '{not json', d.token)).status).toBe(400);
      expect((await req('GET', '/v1/records?since=-1', undefined, d.token)).status).toBe(400);
      const huge = { records: [{ collection: 'bookmarks', id: 'x', ciphertext: `v1.${'A'.repeat(5 * 1024 * 1024)}`, modifiedAt: 1 }] };
      expect((await req('POST', '/v1/records', huge, d.token)).status).toBe(413);
    });
  });

  it('logs only method, route pattern, status and latency', async () => {
    const a = await account();
    const d = await device(a);
    await req('GET', '/v1/records?since=12345', undefined, d.token);
    await req('DELETE', `/v1/devices/${d.deviceId}`, undefined, d.token);
    const all = logs.join('\n');
    expect(all).not.toContain('12345');
    expect(all).not.toContain(d.deviceId);
    expect(all).not.toContain(a.loginId);
    for (const l of logs) expect(l).toMatch(/^(GET|POST|DELETE) \/[\w/:*]* \d{3} \d+ms$/);
  });
});
