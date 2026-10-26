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
    const adapter = new BookmarkAdapter(this.bm, this.kv, clock.env, { moveIndexBeforeRemoval: this.bm.flavor === 'chromium' });
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
  const brave = await new Browser('chromium').connect('signUp');
  const zen = await new Browser('firefox').connect('signIn');
  return { brave, zen };
}

describe('bookmark sync, Zen (Firefox engine) <-> Brave (Chromium engine)', () => {
  it('syncs a new bookmark and nested folder, mapping the toolbar root across browsers', async () => {
    const { brave, zen } = await pair();
    const folder = await brave.bm.add(brave.bm.roots.toolbar!, 'Work');
    await brave.bm.add(folder.id, 'Tracker', 'https://tracker.example/board');
    await brave.bm.add(brave.bm.roots.toolbar!, 'News', 'https://news.example');
    await brave.bm.add(brave.bm.roots.other!, 'Recipes', 'https://food.example');

    expect(await brave.sync()).toMatchObject({ pushed: 4, conflicts: 0 });
    expect(await zen.sync()).toMatchObject({ pulled: 4, pushed: 0 });

    expect(zen.bm.outline(zen.bm.roots.toolbar!)).toEqual([
      'Work/',
      'Work/Tracker -> https://tracker.example/board',
      'News -> https://news.example',
    ]);
    expect(zen.bm.outline(zen.bm.roots.other!)).toEqual(['Recipes -> https://food.example']);
  });

  it('is quiescent: repeated syncs push nothing and do not duplicate', async () => {
    const { brave, zen } = await pair();
    await brave.bm.add(brave.bm.roots.toolbar!, 'A', 'https://a.example');
    await brave.sync();
    await zen.sync();
    for (let i = 0; i < 3; i++) {
      expect(await brave.sync()).toMatchObject({ pulled: 0, pushed: 0 });
      expect(await zen.sync()).toMatchObject({ pulled: 0, pushed: 0 });
    }
    expect(brave.bm.outline(brave.bm.roots.toolbar!)).toEqual(['A -> https://a.example']);
    expect(zen.bm.outline(zen.bm.roots.toolbar!)).toEqual(['A -> https://a.example']);
  });

  it('propagates edits, moves and deletions in both directions', async () => {
    const { brave, zen } = await pair();
    const f = await brave.bm.add(brave.bm.roots.toolbar!, 'Folder');
    await brave.bm.add(f.id, 'Inside', 'https://inside.example');
    await brave.bm.add(brave.bm.roots.toolbar!, 'Loose', 'https://loose.example');
    await brave.sync();
    await zen.sync();

    // Firefox renames and moves.
    const loose = zen.bm.find('Loose');
    await zen.bm.update(loose, { title: 'Loose (renamed)', url: 'https://loose.example/v2' });
    await zen.bm.move(loose, { parentId: zen.bm.find('Folder'), index: 0 });
    await zen.sync();
    await brave.sync();
    expect(brave.bm.outline(brave.bm.roots.toolbar!)).toEqual([
      'Folder/',
      'Folder/Loose (renamed) -> https://loose.example/v2',
      'Folder/Inside -> https://inside.example',
    ]);

    // Chrome deletes the whole folder.
    await brave.bm.removeTree(brave.bm.find('Folder'));
    expect((await brave.sync()).pushed).toBe(3); // folder + both children tombstoned
    await zen.sync();
    expect(zen.bm.outline(zen.bm.roots.toolbar!)).toEqual([]);
  });

  it('first sync adopts identical existing bookmarks instead of duplicating them', async () => {
    const brave = await new Browser('chromium').connect('signUp');
    const zen = new Browser('firefox');
    await brave.bm.add(brave.bm.roots.toolbar!, 'Shared', 'https://shared.example');
    await zen.bm.add(zen.bm.roots.toolbar!, 'Shared', 'https://shared.example');
    await zen.bm.add(zen.bm.roots.toolbar!, 'Only on Firefox', 'https://ff.example');
    await brave.sync();
    await zen.connect('signIn');
    await zen.sync();
    await brave.sync();
    const expected = ['Shared -> https://shared.example', 'Only on Firefox -> https://ff.example'];
    expect(zen.bm.outline(zen.bm.roots.toolbar!)).toEqual(expected);
    expect(brave.bm.outline(brave.bm.roots.toolbar!)).toEqual(expected);
  });

  it('places items from a root the other browser lacks (Firefox menu) into Other bookmarks', async () => {
    const { brave, zen } = await pair();
    await zen.bm.add(zen.bm.roots.menu!, 'From menu', 'https://menu.example');
    await zen.sync();
    await brave.sync();
    expect(brave.bm.outline(brave.bm.roots.other!)).toEqual(['From menu -> https://menu.example']);
    // ...and Chrome doesn't push it back as a move.
    expect((await brave.sync()).pushed).toBe(0);
  });

  describe('conflicts (last-write-wins per record)', () => {
    async function setup() {
      const p = await pair();
      await p.brave.bm.add(p.brave.bm.roots.toolbar!, 'Doc', 'https://doc.example');
      await p.brave.sync();
      await p.zen.sync();
      return p;
    }

    it('later edit wins even when it syncs second', async () => {
      const { brave, zen } = await setup();
      const t = Date.now();
      const c = brave.bm.find('Doc');
      await brave.bm.update(c, { title: 'Chrome edit' });
      await brave.touch(c, t);
      const f = zen.bm.find('Doc');
      await zen.bm.update(f, { title: 'Firefox edit (later)' });
      await zen.touch(f, t + 50);

      await brave.sync();
      const r = await zen.sync(); // sees chrome's older edit, keeps its own, pushes
      expect(r.pushed).toBe(1);
      await brave.sync();
      expect(brave.bm.outline(brave.bm.roots.toolbar!)).toEqual(['Firefox edit (later) -> https://doc.example']);
      expect(zen.bm.outline(zen.bm.roots.toolbar!)).toEqual(['Firefox edit (later) -> https://doc.example']);
    });

    it('later edit wins when it syncs first', async () => {
      const { brave, zen } = await setup();
      const t = Date.now();
      const c = brave.bm.find('Doc');
      await brave.bm.update(c, { title: 'Chrome edit (later)' });
      await brave.touch(c, t + 50);
      const f = zen.bm.find('Doc');
      await zen.bm.update(f, { title: 'Firefox edit' });
      await zen.touch(f, t);

      await brave.sync();
      await zen.sync(); // its own edit is older: remote wins locally
      await brave.sync();
      expect(brave.bm.outline(brave.bm.roots.toolbar!)).toEqual(['Chrome edit (later) -> https://doc.example']);
      expect(zen.bm.outline(zen.bm.roots.toolbar!)).toEqual(['Chrome edit (later) -> https://doc.example']);
    });

    it('an edit newer than a remote delete resurrects the bookmark', async () => {
      const { brave, zen } = await setup();
      const t = Date.now();
      const c = brave.bm.find('Doc');
      await brave.bm.removeTree(c);
      await brave.touch(c, t);
      const f = zen.bm.find('Doc');
      await zen.bm.update(f, { title: 'Still needed' });
      await zen.touch(f, t + 50);
      await brave.sync();
      await zen.sync();
      await brave.sync();
      expect(brave.bm.outline(brave.bm.roots.toolbar!)).toEqual(['Still needed -> https://doc.example']);
    });

    it("corrects a device's skewed clock using server time", async () => {
      const { brave, zen } = await setup();
      brave.clockSkew = 8 * 60_000; // 8 minutes fast: within server tolerance, but would win every conflict
      await brave.sync(); // learns the offset
      const c = brave.bm.find('Doc');
      await brave.bm.update(c, { title: 'skewed' });
      await brave.sync();
      const f = zen.bm.find('Doc');
      await zen.bm.update(f, { title: 'honest, later' });
      await zen.touch(f, Date.now() + 10);
      await zen.sync();
      await brave.sync();
      expect(brave.bm.outline(brave.bm.roots.toolbar!)).toEqual(['honest, later -> https://doc.example']);
    });
  });

  describe('moves within a folder (order preserved exactly)', () => {
    async function folderOf(b: Browser, names: string[]) {
      const f = await b.bm.add(b.bm.roots.toolbar!, 'Ordered');
      for (const n of names) await b.bm.add(f.id, n, `https://${n.toLowerCase()}.example`);
      return f.id;
    }
    const order = (b: Browser) => b.bm.outline(b.bm.find('Ordered')).map((l) => l.split(' ->')[0]);

    it('moving an item down in Zen lands in the same slot in Brave (Chromium index quirk)', async () => {
      const { brave, zen } = await pair();
      await folderOf(brave, ['A', 'B', 'C', 'D']);
      await brave.sync();
      await zen.sync();
      await zen.bm.move(zen.bm.find('A'), { index: 2 }); // B C A D
      await zen.sync();
      await brave.sync();
      expect(order(brave)).toEqual(['B', 'C', 'A', 'D']);
      expect(order(zen)).toEqual(['B', 'C', 'A', 'D']);
    });

    it('a lone downward move is applied at the exact index on Chromium', async () => {
      // Real reorders usually resolve as upward moves because siblings are applied in index
      // order, so drive the adapter directly with a single record that moves A down two slots.
      const { brave } = await pair();
      const f = await folderOf(brave, ['A', 'B', 'C', 'D']);
      await brave.sync();
      const state = (await brave.kv.get<{ local2sync: Record<string, string> }>('bm.state'))!;
      const aId = state.local2sync[brave.bm.find('A')]!;
      const parent = state.local2sync[f]!;
      const clock = await makeEnv(brave.kv, brave.vaultKey, brave.config.deviceId);
      const adapter = new BookmarkAdapter(brave.bm, brave.kv, clock.env, { moveIndexBeforeRemoval: true });
      await adapter.begin();
      await adapter.applyRemote([
        { id: aId, modifiedAt: Date.now() + 1000, deviceId: 'zzz', payload: { kind: 'bookmark', title: 'A', url: 'https://a.example', parent, index: 2 } },
      ]);
      expect(order(brave)).toEqual(['B', 'C', 'A', 'D']);
    });

    it('without the Chromium index compensation the same move lands one slot too high', async () => {
      const { brave } = await pair();
      const f = await folderOf(brave, ['A', 'B', 'C', 'D']);
      await brave.sync();
      const state = (await brave.kv.get<{ local2sync: Record<string, string> }>('bm.state'))!;
      const clock = await makeEnv(brave.kv, brave.vaultKey, brave.config.deviceId);
      const adapter = new BookmarkAdapter(brave.bm, brave.kv, clock.env, { moveIndexBeforeRemoval: false });
      await adapter.begin();
      await adapter.applyRemote([
        {
          id: state.local2sync[brave.bm.find('A')]!,
          modifiedAt: Date.now() + 1000,
          deviceId: 'zzz',
          payload: { kind: 'bookmark', title: 'A', url: 'https://a.example', parent: state.local2sync[f]!, index: 2 },
        },
      ]);
      expect(order(brave)).toEqual(['B', 'A', 'C', 'D']); // proves the fake reproduces Chromium's behaviour
    });

    it('moving an item up in Brave lands in the same slot in Zen', async () => {
      const { brave, zen } = await pair();
      await folderOf(brave, ['A', 'B', 'C', 'D']);
      await brave.sync();
      await zen.sync();
      await brave.bm.move(brave.bm.find('D'), { index: 1 }); // A D B C
      await brave.sync();
      await zen.sync();
      expect(order(zen)).toEqual(['A', 'D', 'B', 'C']);
      expect((await brave.sync()).pushed).toBe(0);
    });

    it('moves a bookmark between nested folders', async () => {
      const { brave, zen } = await pair();
      const outer = await zen.bm.add(zen.bm.roots.toolbar!, 'Outer');
      const inner = await zen.bm.add(outer.id, 'Inner');
      const bm = await zen.bm.add(zen.bm.roots.other!, 'Mover', 'https://mover.example');
      await zen.sync();
      await brave.sync();
      await zen.bm.move(bm.id, { parentId: inner.id });
      await zen.sync();
      await brave.sync();
      expect(brave.bm.outline(brave.bm.roots.toolbar!)).toEqual(['Outer/', 'Outer/Inner/', 'Outer/Inner/Mover -> https://mover.example']);
      expect(brave.bm.outline(brave.bm.roots.other!)).toEqual([]);
    });
  });

  describe('duplicate URLs within a folder', () => {
    it('first sync adopts a same-URL bookmark even when the titles differ', async () => {
      const brave = await new Browser('chromium').connect('signUp');
      await brave.bm.add(brave.bm.roots.toolbar!, 'GitHub', 'https://github.com/');
      await brave.sync();
      const zen = new Browser('firefox');
      await zen.bm.add(zen.bm.roots.toolbar!, 'GitHub - Home', 'https://github.com/');
      await zen.connect('signIn');
      await zen.sync();
      await brave.sync();
      expect(zen.bm.outline(zen.bm.roots.toolbar!)).toEqual(['GitHub -> https://github.com/']);
      expect(brave.bm.outline(brave.bm.roots.toolbar!)).toEqual(['GitHub -> https://github.com/']);
    });

    it('the same URL added in both browsers before syncing ends up once in both', async () => {
      const { brave, zen } = await pair();
      await brave.bm.add(brave.bm.roots.toolbar!, 'Docs (brave)', 'https://docs.example');
      await zen.bm.add(zen.bm.roots.toolbar!, 'Docs (zen)', 'https://docs.example');
      for (let i = 0; i < 2; i++) {
        await brave.sync();
        await zen.sync();
      }
      const b = brave.bm.outline(brave.bm.roots.toolbar!);
      const z = zen.bm.outline(zen.bm.roots.toolbar!);
      expect(b).toHaveLength(1);
      expect(z).toEqual(b);
      expect((await brave.sync()).pushed).toBe(0);
      expect((await zen.sync()).pushed).toBe(0);
    });

    it('collapses local duplicates in one folder but keeps the same URL in different folders', async () => {
      const { brave, zen } = await pair();
      const f = await brave.bm.add(brave.bm.roots.other!, 'Reading');
      await brave.bm.add(f.id, 'Article', 'https://a.example/post');
      await brave.bm.add(f.id, 'Article again', 'https://a.example/post');
      await brave.bm.add(brave.bm.roots.toolbar!, 'Article', 'https://a.example/post');
      await brave.sync();
      await zen.sync();
      const expected = ['Reading/', 'Reading/Article -> https://a.example/post'];
      expect(brave.bm.outline(brave.bm.roots.other!)).toEqual(expected);
      expect(zen.bm.outline(zen.bm.roots.other!)).toEqual(expected);
      expect(zen.bm.outline(zen.bm.roots.toolbar!)).toEqual(['Article -> https://a.example/post']);
    });

    it('a duplicate created by a move is collapsed on both sides', async () => {
      const { brave, zen } = await pair();
      const f = await zen.bm.add(zen.bm.roots.toolbar!, 'F');
      await zen.bm.add(f.id, 'One', 'https://dup.example');
      const loose = await zen.bm.add(zen.bm.roots.toolbar!, 'Two', 'https://dup.example');
      await zen.sync();
      await brave.sync();
      await zen.bm.move(loose.id, { parentId: f.id });
      await zen.sync();
      await brave.sync();
      expect(zen.bm.outline(zen.bm.roots.toolbar!)).toHaveLength(2);
      expect(brave.bm.outline(brave.bm.roots.toolbar!)).toEqual(zen.bm.outline(zen.bm.roots.toolbar!));
    });
  });

  it('ignores records the server tampered with', async () => {
    const { brave, zen } = await pair();
    await brave.bm.add(brave.bm.roots.toolbar!, 'Real', 'https://real.example');
    await brave.sync();
    await db.query(`update records set ciphertext = 'v1.' || substr(ciphertext, 5) || 'AA'`);
    const r = await zen.sync();
    expect(r).toMatchObject({ pulled: 0, undecryptable: 1 });
    expect(zen.bm.outline(zen.bm.roots.toolbar!)).toEqual([]);
  });

  it('server-side swap of two ciphertexts is detected (AAD binds record id)', async () => {
    const { brave, zen } = await pair();
    await brave.bm.add(brave.bm.roots.toolbar!, 'One', 'https://1.example');
    await brave.bm.add(brave.bm.roots.toolbar!, 'Two', 'https://2.example');
    await brave.sync();
    await db.query(`
      with r as (select id, ciphertext, row_number() over (order by seq) n from records)
      update records set ciphertext = (select ciphertext from r where r.n = 3 - (select n from r r2 where r2.id = records.id))`);
    expect((await zen.sync()).undecryptable).toBe(2);
  });

  it('sign-out and back in re-syncs without duplicates', async () => {
    const { brave, zen } = await pair();
    await brave.bm.add(brave.bm.roots.toolbar!, 'Keep', 'https://keep.example');
    await brave.sync();
    await zen.sync();
    await resetSyncState(zen.kv);
    await zen.connect('signIn');
    expect((await zen.sync()).pushed).toBe(0);
    expect(zen.bm.outline(zen.bm.roots.toolbar!)).toEqual(['Keep -> https://keep.example']);
    expect(await brave.sync()).toMatchObject({ pulled: 0, pushed: 0 }); // no echo of its own write either
  });

  it('unlock re-derives the vault key offline and rejects a wrong password', async () => {
    const { brave } = await pair();
    expect(oc.toB64(await unlock(brave.config, 'correct horse battery staple'))).toBe(oc.toB64(brave.vaultKey));
    await expect(unlock(brave.config, 'nope nope nope')).rejects.toThrow('Wrong master password');
  });

  it('handles many bookmarks across multiple pull pages and push chunks', async () => {
    const { brave, zen } = await pair();
    const f = await brave.bm.add(brave.bm.roots.other!, 'Bulk');
    for (let i = 0; i < 650; i++) await brave.bm.add(f.id, `b${i}`, `https://bulk.example/${i}`);
    expect((await brave.sync()).pushed).toBe(651);
    expect((await zen.sync()).pulled).toBe(651);
    const out = zen.bm.outline(zen.bm.roots.other!);
    expect(out).toHaveLength(651);
    expect(out[1]).toBe('Bulk/b0 -> https://bulk.example/0');
    expect(out[650]).toBe('Bulk/b649 -> https://bulk.example/649');
  });
});
