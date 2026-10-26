/** The password vault on two devices (think Zen and Brave), syncing through the real backend. */
import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import * as oc from '@omnisync/crypto';
import { createApp } from '@omnisync/backend';
import { createPgliteDb, migrate, type Db } from '@omnisync/backend/db';
import { signIn, signUp, type AccountConfig } from '../src/account.ts';
import { ApiClient, type Fetch } from '../src/api.ts';
import { memoryKV } from '../src/kv.ts';
import { parsePasswordCsv } from '../src/passwords/csv.ts';
import { PasswordVault } from '../src/passwords/vault.ts';
import { makeEnv, resetSyncState, runSync } from '../src/sync.ts';

const SERVER = 'http://omnisync.test';
const FAST = { alg: 'argon2id13', opsLimit: 1, memLimitBytes: 8192 } as const;
let db: Db;
let fetchImpl: Fetch;

beforeAll(async () => {
  await oc.ready();
  db = await createPgliteDb();
  await migrate(db);
});
afterAll(() => db.close());
beforeEach(async () => {
  await db.query('truncate users cascade');
  const app = createApp({ db });
  fetchImpl = async (url, init) => app.fetch(new Request(url, init));
});

class Device {
  kv = memoryKV();
  config!: AccountConfig;
  vaultKey!: Uint8Array;

  async connect(mode: 'signUp' | 'signIn') {
    const r = await (mode === 'signUp' ? signUp : signIn)(
      { serverUrl: SERVER, username: 'alice@example.com', password: 'correct horse battery staple', deviceName: 'd' },
      { fetch: fetchImpl, kdf: FAST },
    );
    this.config = r.config;
    this.vaultKey = r.vaultKey;
    return this;
  }

  /** A vault handle, as the background creates one per operation. */
  async vault() {
    const { env } = await makeEnv(this.kv, this.vaultKey, this.config.deviceId);
    const v = new PasswordVault(this.kv, env, this.vaultKey);
    await v.begin();
    return v;
  }

  async sync() {
    const clock = await makeEnv(this.kv, this.vaultKey, this.config.deviceId);
    return runSync({
      api: new ApiClient(SERVER, this.config.token, fetchImpl),
      kv: this.kv,
      vaultKey: this.vaultKey,
      deviceId: this.config.deviceId,
      adapters: [new PasswordVault(this.kv, clock.env, this.vaultKey)],
      clock,
    });
  }

  async list() {
    return (await this.vault()).entries().map((e) => `${e.origin} ${e.username}:${e.password}`);
  }
}

async function pair() {
  return { zen: await new Device().connect('signUp'), brave: await new Device().connect('signIn') };
}

const login = (origin: string, username: string, password: string) => ({ origin, username, password });

describe('password vault sync', () => {
  it('a login saved in one browser appears in the other', async () => {
    const { zen, brave } = await pair();
    await (await zen.vault()).upsert(login('https://github.com/login', 'octo', 'hunter2!'));
    expect(await zen.sync()).toMatchObject({ pushed: 1 });
    await brave.sync();
    expect(await brave.list()).toEqual(['https://github.com octo:hunter2!']);
  });

  it('updates and deletions propagate', async () => {
    const { zen, brave } = await pair();
    const { id } = await (await zen.vault()).upsert(login('https://a.example', 'me', 'one'));
    await zen.sync();
    await brave.sync();
    await (await brave.vault()).upsert({ id, ...login('https://a.example', 'me', 'two') });
    await brave.sync();
    await zen.sync();
    expect(await zen.list()).toEqual(['https://a.example me:two']);
    await (await zen.vault()).remove(id);
    await zen.sync();
    await brave.sync();
    expect(await brave.list()).toEqual([]);
  });

  it('saving the same site + username again updates instead of duplicating', async () => {
    const { zen } = await pair();
    const v = await zen.vault();
    expect((await v.upsert(login('https://a.example/x', 'me', 'one'))).status).toBe('added');
    expect((await v.upsert(login('https://a.example/y', 'me', 'one'))).status).toBe('unchanged');
    expect((await v.upsert(login('https://a.example', 'me', 'two'))).status).toBe('updated');
    expect((await v.upsert(login('https://a.example', 'other', 'x'))).status).toBe('added');
    expect(v.entries()).toHaveLength(2);
  });

  it('conflicting edits: last write wins', async () => {
    const { zen, brave } = await pair();
    const { id } = await (await zen.vault()).upsert(login('https://a.example', 'me', 'base'));
    await zen.sync();
    await brave.sync();
    await (await zen.vault()).upsert({ id, ...login('https://a.example', 'me', 'from zen') });
    await new Promise((r) => setTimeout(r, 5));
    await (await brave.vault()).upsert({ id, ...login('https://a.example', 'me', 'from brave, later') });
    await zen.sync();
    const r = await brave.sync(); // brave's newer write wins on the server
    expect(r.pushed).toBe(1);
    await zen.sync();
    expect(await zen.list()).toEqual(['https://a.example me:from brave, later']);
    expect(await brave.list()).toEqual(['https://a.example me:from brave, later']);
  });

  it('the same account created independently in both browsers converges to one login (newest wins)', async () => {
    const { zen, brave } = await pair();
    await (await zen.vault()).upsert(login('https://site.example', 'me', 'older'));
    await new Promise((r) => setTimeout(r, 5));
    await (await brave.vault()).upsert(login('https://site.example', 'me', 'newer'));
    for (let i = 0; i < 2; i++) {
      await zen.sync();
      await brave.sync();
    }
    expect(await zen.list()).toEqual(['https://site.example me:newer']);
    expect(await brave.list()).toEqual(['https://site.example me:newer']);
    expect((await zen.sync()).pushed).toBe(0);
  });

  it('never stores a password in plaintext, locally or on the server', async () => {
    const { zen, brave } = await pair();
    await (await zen.vault()).upsert({ ...login('https://bank.example', 'me@x.io', 'P4SSW0RD-PLAINTEXT'), notes: 'SECRET NOTE' });
    await zen.sync();
    await brave.sync();
    const local = JSON.stringify([await zen.kv.getAll(), await brave.kv.getAll()]);
    const server = JSON.stringify(await db.query('select * from records'));
    for (const needle of ['P4SSW0RD', 'SECRET NOTE', 'bank.example', 'me@x.io']) {
      expect(local).not.toContain(needle);
      expect(server).not.toContain(needle);
    }
  });

  it('autofill lookup only returns logins for the exact site', async () => {
    const { zen } = await pair();
    const v = await zen.vault();
    await v.importMany([
      login('https://example.com', 'a', '1'),
      login('https://accounts.example.com', 'b', '2'),
      login('https://example.org', 'c', '3'),
    ]);
    expect(v.forPage('https://www.example.com/login').map((e) => e.username)).toEqual(['a']);
    expect(v.forPage('http://example.com/login')).toEqual([]);
  });

  it('imports a browser CSV export and syncs it, reporting duplicates as unchanged', async () => {
    const { zen, brave } = await pair();
    const csv = 'name,url,username,password,note\nGH,https://github.com/,octo,pw1,\nGH,https://github.com/login,octo,pw1,\nX,https://x.com,me,pw2,';
    const parsed = parsePasswordCsv(csv);
    const counts = await (await brave.vault()).importMany(parsed.logins);
    expect(counts).toEqual({ added: 2, updated: 0, unchanged: 1, failed: 0 });
    await brave.sync();
    await zen.sync();
    expect(await zen.list()).toEqual(['https://github.com octo:pw1', 'https://x.com me:pw2']);
  });

  it('remembers "never save" per site without storing the site name', async () => {
    const { zen } = await pair();
    const v = await zen.vault();
    await v.addNever('https://private.example');
    expect(await v.isNever('https://private.example')).toBe(true);
    expect(await v.isNever('https://other.example')).toBe(false);
    expect(JSON.stringify(await zen.kv.getAll())).not.toContain('private.example');
  });

  it('unsynced local edits survive a sync failure and go out on the next sync', async () => {
    const { zen, brave } = await pair();
    await (await zen.vault()).upsert(login('https://a.example', 'me', 'pw'));
    const realFetch = fetchImpl;
    fetchImpl = async () => {
      throw new TypeError('network down');
    };
    await expect(zen.sync()).rejects.toThrow('network down');
    fetchImpl = realFetch;
    expect((await zen.sync()).pushed).toBe(1);
    await brave.sync();
    expect(await brave.list()).toEqual(['https://a.example me:pw']);
  });

  it('sign-out wipes the local vault copy (the server copy remains for the next sign-in)', async () => {
    const { zen } = await pair();
    await (await zen.vault()).upsert(login('https://a.example', 'me', 'pw'));
    await zen.sync();
    await resetSyncState(zen.kv);
    expect(await zen.list()).toEqual([]);
    await zen.connect('signIn');
    await zen.sync();
    expect(await zen.list()).toEqual(['https://a.example me:pw']);
  });
});
