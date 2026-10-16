/**
 * Two simulated browsers (a Chromium tree and a Firefox tree) syncing through
 * the real backend app (in-process, PGlite) with real encryption.
 */
import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import * as oc from '@omnisync/crypto';
import { createApp } from '@omnisync/backend';
import { createPgliteDb, migrate, type Db } from '@omnisync/backend/db';
import { signIn, signUp, unlock, type AccountConfig } from '../src/account.ts';
import { ApiClient, type Fetch } from '../src/api.ts';
import { BookmarkAdapter, DIRTY_PREFIX } from '../src/bookmarks/engine.ts';
import { memoryKV } from '../src/kv.ts';
import { makeEnv, resetSyncState, runSync } from '../src/sync.ts';
import { FakeBookmarks } from './fake-bookmarks.ts';

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
  const app = createApp({ db }); // fresh rate limiter per test
  fetchImpl = async (url, init) => app.fetch(new Request(url, init));
});

class Browser {
  kv = memoryKV();
  bm: FakeBookmarks;
  config!: AccountConfig;
  vaultKey!: Uint8Array;
  clockSkew = 0;

  constructor(flavor: 'chromium' | 'firefox') {
    this.bm = new FakeBookmarks(flavor);
  }

  async connect(mode: 'signUp' | 'signIn', username = 'alice@example.com') {
    const creds = { serverUrl: SERVER, username, password: 'correct horse battery staple', deviceName: this.bm.flavor };
    const r = await (mode === 'signUp' ? signUp : signIn)(creds, { fetch: fetchImpl, kdf: FAST });
    this.config = r.config;
    this.vaultKey = r.vaultKey;
    return this;
  }

  async sync() {
    const localNow = () => Date.now() + this.clockSkew;
    const clock = await makeEnv(this.kv, this.vaultKey, this.config.deviceId, localNow);
    const adapter = new BookmarkAdapter(this.bm, this.kv, clock.env);
    return runSync({
      api: new ApiClient(SERVER, this.config.token, fetchImpl),
      kv: this.kv,
      vaultKey: this.vaultKey,
      deviceId: this.config.deviceId,
      adapters: [adapter],
      clock,
      localNow,
    });
  }

  /** What the background's bookmark listeners do on every local edit. */
  touch(localId: string, at = Date.now()) {
    return this.kv.set({ [DIRTY_PREFIX + localId]: at });
  }
}

async function pair() {
  const chrome = await new Browser('chromium').connect('signUp');
  const firefox = await new Browser('firefox').connect('signIn');
  return { chrome, firefox };
}

describe('bookmark sync, Chromium <-> Firefox', () => {
  it('syncs a new bookmark and nested folder, mapping the toolbar root across browsers', async () => {
    const { chrome, firefox } = await pair();
    const folder = await chrome.bm.add(chrome.bm.roots.toolbar!, 'Work');
    await chrome.bm.add(folder.id, 'Tracker', 'https://tracker.example/board');
    await chrome.bm.add(chrome.bm.roots.toolbar!, 'News', 'https://news.example');
    await chrome.bm.add(chrome.bm.roots.other!, 'Recipes', 'https://food.example');

    expect(await chrome.sync()).toMatchObject({ pushed: 4, conflicts: 0 });
    expect(await firefox.sync()).toMatchObject({ pulled: 4, pushed: 0 });

    expect(firefox.bm.outline(firefox.bm.roots.toolbar!)).toEqual([
      'Work/',
      'Work/Tracker -> https://tracker.example/board',
      'News -> https://news.example',
    ]);
    expect(firefox.bm.outline(firefox.bm.roots.other!)).toEqual(['Recipes -> https://food.example']);
  });

  it('is quiescent: repeated syncs push nothing and do not duplicate', async () => {
    const { chrome, firefox } = await pair();
    await chrome.bm.add(chrome.bm.roots.toolbar!, 'A', 'https://a.example');
    await chrome.sync();
    await firefox.sync();
    for (let i = 0; i < 3; i++) {
      expect(await chrome.sync()).toMatchObject({ pulled: 0, pushed: 0 });
      expect(await firefox.sync()).toMatchObject({ pulled: 0, pushed: 0 });
    }
    expect(chrome.bm.outline(chrome.bm.roots.toolbar!)).toEqual(['A -> https://a.example']);
    expect(firefox.bm.outline(firefox.bm.roots.toolbar!)).toEqual(['A -> https://a.example']);
  });

  it('propagates edits, moves and deletions in both directions', async () => {
    const { chrome, firefox } = await pair();
    const f = await chrome.bm.add(chrome.bm.roots.toolbar!, 'Folder');
    await chrome.bm.add(f.id, 'Inside', 'https://inside.example');
    await chrome.bm.add(chrome.bm.roots.toolbar!, 'Loose', 'https://loose.example');
    await chrome.sync();
    await firefox.sync();

    // Firefox renames and moves.
    const loose = firefox.bm.find('Loose');
    await firefox.bm.update(loose, { title: 'Loose (renamed)', url: 'https://loose.example/v2' });
    await firefox.bm.move(loose, { parentId: firefox.bm.find('Folder'), index: 0 });
    await firefox.sync();
    await chrome.sync();
    expect(chrome.bm.outline(chrome.bm.roots.toolbar!)).toEqual([
      'Folder/',
      'Folder/Loose (renamed) -> https://loose.example/v2',
      'Folder/Inside -> https://inside.example',
    ]);

    // Chrome deletes the whole folder.
    await chrome.bm.removeTree(chrome.bm.find('Folder'));
    expect((await chrome.sync()).pushed).toBe(3); // folder + both children tombstoned
    await firefox.sync();
    expect(firefox.bm.outline(firefox.bm.roots.toolbar!)).toEqual([]);
  });

  it('first sync adopts identical existing bookmarks instead of duplicating them', async () => {
    const chrome = await new Browser('chromium').connect('signUp');
    const firefox = new Browser('firefox');
    await chrome.bm.add(chrome.bm.roots.toolbar!, 'Shared', 'https://shared.example');
    await firefox.bm.add(firefox.bm.roots.toolbar!, 'Shared', 'https://shared.example');
    await firefox.bm.add(firefox.bm.roots.toolbar!, 'Only on Firefox', 'https://ff.example');
    await chrome.sync();
    await firefox.connect('signIn');
    await firefox.sync();
    await chrome.sync();
    const expected = ['Shared -> https://shared.example', 'Only on Firefox -> https://ff.example'];
    expect(firefox.bm.outline(firefox.bm.roots.toolbar!)).toEqual(expected);
    expect(chrome.bm.outline(chrome.bm.roots.toolbar!)).toEqual(expected);
  });

  it('places items from a root the other browser lacks (Firefox menu) into Other bookmarks', async () => {
    const { chrome, firefox } = await pair();
    await firefox.bm.add(firefox.bm.roots.menu!, 'From menu', 'https://menu.example');
    await firefox.sync();
    await chrome.sync();
    expect(chrome.bm.outline(chrome.bm.roots.other!)).toEqual(['From menu -> https://menu.example']);
    // ...and Chrome doesn't push it back as a move.
    expect((await chrome.sync()).pushed).toBe(0);
  });

  describe('conflicts (last-write-wins per record)', () => {
    async function setup() {
      const p = await pair();
      await p.chrome.bm.add(p.chrome.bm.roots.toolbar!, 'Doc', 'https://doc.example');
      await p.chrome.sync();
      await p.firefox.sync();
      return p;
    }

    it('later edit wins even when it syncs second', async () => {
      const { chrome, firefox } = await setup();
      const t = Date.now();
      const c = chrome.bm.find('Doc');
      await chrome.bm.update(c, { title: 'Chrome edit' });
      await chrome.touch(c, t);
      const f = firefox.bm.find('Doc');
      await firefox.bm.update(f, { title: 'Firefox edit (later)' });
      await firefox.touch(f, t + 50);

      await chrome.sync();
      const r = await firefox.sync(); // sees chrome's older edit, keeps its own, pushes
      expect(r.pushed).toBe(1);
      await chrome.sync();
      expect(chrome.bm.outline(chrome.bm.roots.toolbar!)).toEqual(['Firefox edit (later) -> https://doc.example']);
      expect(firefox.bm.outline(firefox.bm.roots.toolbar!)).toEqual(['Firefox edit (later) -> https://doc.example']);
    });

    it('later edit wins when it syncs first', async () => {
      const { chrome, firefox } = await setup();
      const t = Date.now();
      const c = chrome.bm.find('Doc');
      await chrome.bm.update(c, { title: 'Chrome edit (later)' });
      await chrome.touch(c, t + 50);
      const f = firefox.bm.find('Doc');
      await firefox.bm.update(f, { title: 'Firefox edit' });
      await firefox.touch(f, t);

      await chrome.sync();
      await firefox.sync(); // its own edit is older: remote wins locally
      await chrome.sync();
      expect(chrome.bm.outline(chrome.bm.roots.toolbar!)).toEqual(['Chrome edit (later) -> https://doc.example']);
      expect(firefox.bm.outline(firefox.bm.roots.toolbar!)).toEqual(['Chrome edit (later) -> https://doc.example']);
    });

    it('an edit newer than a remote delete resurrects the bookmark', async () => {
      const { chrome, firefox } = await setup();
      const t = Date.now();
      const c = chrome.bm.find('Doc');
      await chrome.bm.removeTree(c);
      await chrome.touch(c, t);
      const f = firefox.bm.find('Doc');
      await firefox.bm.update(f, { title: 'Still needed' });
      await firefox.touch(f, t + 50);
      await chrome.sync();
      await firefox.sync();
      await chrome.sync();
      expect(chrome.bm.outline(chrome.bm.roots.toolbar!)).toEqual(['Still needed -> https://doc.example']);
    });

    it("corrects a device's skewed clock using server time", async () => {
      const { chrome, firefox } = await setup();
      chrome.clockSkew = 8 * 60_000; // 8 minutes fast: within server tolerance, but would win every conflict
      await chrome.sync(); // learns the offset
      const c = chrome.bm.find('Doc');
      await chrome.bm.update(c, { title: 'skewed' });
      await chrome.sync();
      const f = firefox.bm.find('Doc');
      await firefox.bm.update(f, { title: 'honest, later' });
      await firefox.touch(f, Date.now() + 10);
      await firefox.sync();
      await chrome.sync();
      expect(chrome.bm.outline(chrome.bm.roots.toolbar!)).toEqual(['honest, later -> https://doc.example']);
    });
  });

  it('ignores records the server tampered with', async () => {
    const { chrome, firefox } = await pair();
    await chrome.bm.add(chrome.bm.roots.toolbar!, 'Real', 'https://real.example');
    await chrome.sync();
    await db.query(`update records set ciphertext = 'v1.' || substr(ciphertext, 5) || 'AA'`);
    const r = await firefox.sync();
    expect(r).toMatchObject({ pulled: 0, undecryptable: 1 });
    expect(firefox.bm.outline(firefox.bm.roots.toolbar!)).toEqual([]);
  });

  it('server-side swap of two ciphertexts is detected (AAD binds record id)', async () => {
    const { chrome, firefox } = await pair();
    await chrome.bm.add(chrome.bm.roots.toolbar!, 'One', 'https://1.example');
    await chrome.bm.add(chrome.bm.roots.toolbar!, 'Two', 'https://2.example');
    await chrome.sync();
    await db.query(`
      with r as (select id, ciphertext, row_number() over (order by seq) n from records)
      update records set ciphertext = (select ciphertext from r where r.n = 3 - (select n from r r2 where r2.id = records.id))`);
    expect((await firefox.sync()).undecryptable).toBe(2);
  });

  it('sign-out and back in re-syncs without duplicates', async () => {
    const { chrome, firefox } = await pair();
    await chrome.bm.add(chrome.bm.roots.toolbar!, 'Keep', 'https://keep.example');
    await chrome.sync();
    await firefox.sync();
    await resetSyncState(firefox.kv);
    await firefox.connect('signIn');
    expect((await firefox.sync()).pushed).toBe(0);
    expect(firefox.bm.outline(firefox.bm.roots.toolbar!)).toEqual(['Keep -> https://keep.example']);
    expect(await chrome.sync()).toMatchObject({ pulled: 0, pushed: 0 }); // no echo of its own write either
  });

  it('unlock re-derives the vault key offline and rejects a wrong password', async () => {
    const { chrome } = await pair();
    expect(oc.toB64(await unlock(chrome.config, 'correct horse battery staple'))).toBe(oc.toB64(chrome.vaultKey));
    await expect(unlock(chrome.config, 'nope nope nope')).rejects.toThrow('Wrong master password');
  });

  it('handles many bookmarks across multiple pull pages and push chunks', async () => {
    const { chrome, firefox } = await pair();
    const f = await chrome.bm.add(chrome.bm.roots.other!, 'Bulk');
    for (let i = 0; i < 650; i++) await chrome.bm.add(f.id, `b${i}`, `https://bulk.example/${i}`);
    expect((await chrome.sync()).pushed).toBe(651);
    expect((await firefox.sync()).pulled).toBe(651);
    const out = firefox.bm.outline(firefox.bm.roots.other!);
    expect(out).toHaveLength(651);
    expect(out[1]).toBe('Bulk/b0 -> https://bulk.example/0');
    expect(out[650]).toBe('Bulk/b649 -> https://bulk.example/649');
  });
});
