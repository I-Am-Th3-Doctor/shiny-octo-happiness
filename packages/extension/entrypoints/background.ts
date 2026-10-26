/**
 * Background (MV3 service worker in Brave, MV3 event page in Zen).
 *
 * The worker can be terminated at any moment, so nothing important lives only in memory:
 *   - account config, sync cursor, bookmark baselines, encrypted password vault → storage.local
 *   - the vault key and not-yet-saved captured logins → storage.session
 *     (RAM-only, wiped on browser exit, not readable by content scripts)
 *   - periodic sync → alarms (survive worker restarts)
 * Every listener is registered synchronously at top level so events wake the worker.
 */
import * as oc from '@omnisync/crypto';
import { MAX_WAIT_SECONDS } from '@omnisync/protocol';
import { browser } from 'wxt/browser';
import { defineBackground } from 'wxt/utils/define-background';
import { signIn, signUp, unlock, type AccountConfig } from '../src/account.ts';
import { ApiClient, ApiError } from '../src/api.ts';
import { BookmarkAdapter, DIRTY_PREFIX, type BookmarksApi } from '../src/bookmarks/engine.ts';
import { areaKV } from '../src/kv.ts';
import type {
  ContentRequest,
  FillCommand,
  LoginSummary,
  PageRequest,
  PendingAction,
  PendingPrompt,
  QueryResult,
  Response,
  Status,
} from '../src/messages.ts';
import { Mutex } from '../src/mutex.ts';
import { type LoginEntry, loginKey, normalizeOrigin, originMatches } from '../src/passwords/model.ts';
import { PasswordVault } from '../src/passwords/vault.ts';
import { CURSOR_KEY, makeEnv, resetSyncState, runSync } from '../src/sync.ts';
import type { CollectionAdapter } from '../src/sync-types.ts';

const ALARM = 'omnisync-sync';
const PERIOD_MIN = 0.5; // Chrome's minimum alarm period
/** Each alarm wakes the worker for this long, holding a long-poll open so remote changes arrive in ~1s. */
const WATCH_WINDOW_MS = 27_000;
const DEBOUNCE_MS = 1000;
const CONFIG_KEY = 'account';
const STATUS_KEY = 'status';
const VAULT_KEY = 'vaultKey';
const PENDING_PREFIX = 'pwpending.';
const PENDING_TTL_MS = 5 * 60_000;

interface Pending {
  origin: string;
  username: string;
  password: string;
  at: number;
}

class LockedError extends Error {
  constructor() {
    super('OmniSync is locked');
  }
}

const summary = ({ id, origin, username, title, notes, modifiedAt }: LoginEntry): LoginSummary => ({
  id,
  origin,
  username,
  modifiedAt,
  ...(title && { title }),
  ...(notes && { notes }),
});

export default defineBackground(() => {
  const local = areaKV(browser.storage.local);
  const session = areaKV(browser.storage.session);
  const bookmarks = browser.bookmarks as unknown as BookmarksApi | undefined;
  // Sync runs and vault writes both read-modify-write storage.local: one queue for all of them.
  const mutex = new Mutex();

  let running: Promise<void> | null = null;
  let rerun = false;
  let debounce: ReturnType<typeof setTimeout> | undefined;

  const getConfig = () => local.get<AccountConfig>(CONFIG_KEY);
  const getVaultKey = async () => {
    const k = await session.get<string>(VAULT_KEY);
    return k ? oc.fromB64(k) : undefined;
  };
  const hasSiteAccess = () => browser.permissions.contains({ origins: ['<all_urls>'] }).catch(() => false);

  async function state(): Promise<Status['state']> {
    const [config, key] = await Promise.all([getConfig(), session.get<string>(VAULT_KEY)]);
    return !config ? 'signed-out' : key ? 'ready' : 'locked';
  }

  async function status(): Promise<Status> {
    const [config, saved, st, siteAccess] = await Promise.all([getConfig(), local.get<Partial<Status>>(STATUS_KEY), state(), hasSiteAccess()]);
    return {
      ...saved,
      state: st,
      serverUrl: config?.serverUrl,
      username: config?.username,
      deviceId: config?.deviceId,
      bookmarksSupported: !!bookmarks,
      syncing: running !== null,
      siteAccess,
    };
  }

  function adapters(env: Awaited<ReturnType<typeof makeEnv>>['env'], vaultKey: Uint8Array): CollectionAdapter<any>[] {
    const list: CollectionAdapter<any>[] = [new PasswordVault(local, env, vaultKey)];
    if (bookmarks) {
      // Brave (Chromium) and Zen (Firefox) disagree on how bookmarks.move() counts the target index.
      list.unshift(new BookmarkAdapter(bookmarks, local, env, { moveIndexBeforeRemoval: !import.meta.env.FIREFOX }));
    }
    return list;
  }

  /** Run `fn` with an open vault. Throws LockedError when the vault key isn't in memory. */
  function withVault<T>(fn: (v: PasswordVault) => Promise<T>): Promise<T> {
    return mutex.run(async () => {
      const config = await getConfig();
      if (!config) throw new Error('Not signed in');
      const key = await getVaultKey();
      if (!key) throw new LockedError();
      try {
        const { env } = await makeEnv(local, key, config.deviceId);
        const vault = new PasswordVault(local, env, key);
        await vault.begin();
        return await fn(vault);
      } finally {
        oc.wipe(key);
      }
    });
  }

  async function doSync() {
    const config = await getConfig();
    const vaultKey = await getVaultKey();
    if (!config || !vaultKey) return;
    const clock = await makeEnv(local, vaultKey, config.deviceId);
    try {
      const report = await runSync({
        api: new ApiClient(config.serverUrl, config.token),
        kv: local,
        vaultKey,
        deviceId: config.deviceId,
        adapters: adapters(clock.env, vaultKey),
        clock,
      });
      await local.set({ [STATUS_KEY]: { lastSyncAt: Date.now(), lastReport: report, lastError: undefined } });
    } catch (e) {
      // Messages are ours or HTTP status text, never payload content.
      const msg = e instanceof ApiError && e.status === 401 ? 'Session expired, sign in again' : e instanceof Error ? e.message : 'sync failed';
      await local.set({ [STATUS_KEY]: { ...(await local.get<object>(STATUS_KEY)), lastError: msg } });
    } finally {
      oc.wipe(vaultKey);
    }
  }

  /** Single-flight: at most one sync runs; requests during a run coalesce into one follow-up. */
  function syncNow(): Promise<void> {
    if (running) {
      rerun = true;
      return running;
    }
    running = (async () => {
      await oc.ready();
      do {
        rerun = false;
        await mutex.run(doSync);
      } while (rerun);
    })().finally(() => {
      running = null;
    });
    return running;
  }

  /**
   * Sync, then long-poll the server for the rest of the alarm window and sync again whenever it
   * reports new data. Each request stays under the ~30s a pending fetch may keep an MV3 worker
   * alive, and the window ends before the next alarm, so the worker is never pinned awake.
   */
  let watching = false;
  async function watchWindow() {
    if (watching) return;
    watching = true;
    const deadline = Date.now() + WATCH_WINDOW_MS;
    try {
      await syncNow();
      for (;;) {
        const timeout = Math.min(MAX_WAIT_SECONDS, Math.floor((deadline - Date.now()) / 1000));
        const config = await getConfig();
        if (timeout < 2 || !config || !(await session.get(VAULT_KEY))) break;
        const before = (await local.get<number>(CURSOR_KEY)) ?? 0;
        const { changed } = await new ApiClient(config.serverUrl, config.token).waitForChanges(before, timeout);
        if (!changed) continue;
        await syncNow();
        if (((await local.get<number>(CURSOR_KEY)) ?? 0) <= before) break; // sync isn't making progress; wait for the next alarm
      }
    } catch {
      // Offline or server down: the next alarm retries.
    } finally {
      watching = false;
    }
  }

  function scheduleSync() {
    clearTimeout(debounce);
    debounce = setTimeout(() => void syncNow(), DEBOUNCE_MS);
  }

  async function ensureAlarm() {
    if (!(await browser.alarms.get(ALARM))) await browser.alarms.create(ALARM, { periodInMinutes: PERIOD_MIN, delayInMinutes: PERIOD_MIN });
  }

  // ---- captured logins waiting for "Save password?"

  async function pendingPrompt(tabId: number): Promise<PendingPrompt | null> {
    const key = PENDING_PREFIX + tabId;
    const p = await session.get<Pending>(key);
    if (!p) return null;
    if (Date.now() - p.at > PENDING_TTL_MS) {
      await session.remove(key);
      return null;
    }
    if ((await state()) !== 'ready') return { origin: p.origin, username: p.username, kind: 'save', locked: true };
    const verdict = await withVault(async (v) => {
      if (await v.isNever(p.origin)) return 'drop' as const;
      const existing = v.entries().find((e) => loginKey(e) === loginKey(p));
      if (existing?.password === p.password) return 'drop' as const;
      return existing ? ('update' as const) : ('save' as const);
    });
    if (verdict === 'drop') {
      await session.remove(key);
      return null;
    }
    return { origin: p.origin, username: p.username, kind: verdict, locked: false };
  }

  async function resolvePending(tabId: number, action: PendingAction) {
    const key = PENDING_PREFIX + tabId;
    const p = await session.get<Pending>(key);
    if (!p) return;
    if (action === 'save') {
      await withVault((v) => v.upsert({ origin: p.origin, username: p.username, password: p.password }));
      scheduleSync();
    } else if (action === 'never') {
      await withVault((v) => v.addNever(p.origin));
    }
    await session.remove(key);
  }

  // ---- event wiring (synchronous, top level)

  if (bookmarks) {
    const onLocalEdit = (id: string) => {
      // Edit time for LWW. Applying remote changes also fires these; that's harmless because the
      // stamp only matters when the node's content differs from the last-synced baseline.
      void local.set({ [DIRTY_PREFIX + id]: Date.now() });
      scheduleSync();
    };
    browser.bookmarks.onCreated.addListener((id) => onLocalEdit(id));
    browser.bookmarks.onChanged.addListener((id) => onLocalEdit(id));
    browser.bookmarks.onMoved.addListener((id) => onLocalEdit(id));
    browser.bookmarks.onRemoved.addListener((id) => onLocalEdit(id));
    browser.bookmarks.onChildrenReordered?.addListener((id) => onLocalEdit(id));
  }

  browser.tabs.onRemoved.addListener((tabId) => void session.remove(PENDING_PREFIX + tabId));
  browser.alarms.onAlarm.addListener((a) => {
    if (a.name === ALARM) void watchWindow();
  });
  browser.runtime.onInstalled.addListener(() => void ensureAlarm());
  browser.runtime.onStartup.addListener(() => void ensureAlarm());
  void ensureAlarm();

  // ---- requests from our own extension pages (popup, vault)

  async function handlePage(msg: PageRequest): Promise<unknown> {
    switch (msg.type) {
      case 'status':
        return status();
      case 'signUp':
      case 'signIn': {
        const previous = await getConfig();
        const { config, vaultKey } = await (msg.type === 'signUp' ? signUp : signIn)(msg, {
          previousDeviceId: previous?.loginId === oc.deriveLoginId(msg.username) ? previous.deviceId : undefined,
        });
        if (previous && previous.loginId !== config.loginId) await mutex.run(() => resetSyncState(local));
        await local.set({ [CONFIG_KEY]: config });
        await session.set({ [VAULT_KEY]: oc.toB64(vaultKey) });
        oc.wipe(vaultKey);
        await ensureAlarm();
        void watchWindow();
        return status();
      }
      case 'unlock': {
        const config = await getConfig();
        if (!config) throw new Error('Not signed in');
        const vaultKey = await unlock(config, msg.password);
        await session.set({ [VAULT_KEY]: oc.toB64(vaultKey) });
        oc.wipe(vaultKey);
        void watchWindow();
        return status();
      }
      case 'lock':
        await session.remove(VAULT_KEY);
        return status();
      case 'signOut': {
        const config = await getConfig();
        // Push anything unsynced first: signing out deletes the local (encrypted) vault copy.
        if ((await state()) === 'ready') await syncNow();
        const unsynced = Object.keys((await local.get<{ dirty: object }>('pw.state'))?.dirty ?? {}).length;
        if (unsynced) throw new Error(`${unsynced} password change(s) haven't reached the server yet. Reconnect and try again.`);
        if (config) await new ApiClient(config.serverUrl, config.token).logout().catch(() => {});
        await session.remove(VAULT_KEY);
        // Bookmarks stay in the browser; a later sign-in re-adopts them without duplicates.
        await mutex.run(() => resetSyncState(local));
        await local.remove([CONFIG_KEY, STATUS_KEY]);
        return status();
      }
      case 'syncNow':
        await syncNow();
        return status();
      case 'pw.list':
        return withVault(async (v) => v.entries().map(summary));
      case 'pw.reveal':
        return withVault(async (v) => v.get(msg.id)?.password ?? null);
      case 'pw.save': {
        const r = await withVault((v) => v.upsert(msg.login));
        scheduleSync();
        return r;
      }
      case 'pw.delete':
        await withVault((v) => v.remove(msg.id));
        scheduleSync();
        return null;
      case 'pw.import': {
        const r = await withVault((v) => v.importMany(msg.logins));
        scheduleSync();
        return r;
      }
      case 'pw.forTab': {
        const tab = await browser.tabs.get(msg.tabId);
        const pending = await pendingPrompt(msg.tabId);
        if (!tab.url || !normalizeOrigin(tab.url) || (await state()) !== 'ready') return { logins: [], pending, url: tab.url ?? null };
        const url = tab.url;
        return { logins: await withVault(async (v) => v.forPage(url).map(summary)), pending, url };
      }
      case 'pw.fillTab': {
        const tab = await browser.tabs.get(msg.tabId);
        const entry = await withVault(async (v) => v.get(msg.id));
        if (!entry || !tab.url || !originMatches(entry.origin, tab.url)) throw new Error('That login is not for this site');
        const cmd: FillCommand = { type: 'cs.doFill', origin: entry.origin, username: entry.username, password: entry.password };
        await browser.tabs.sendMessage(msg.tabId, cmd, { frameId: 0 });
        return null;
      }
      case 'pw.resolvePending':
        await resolvePending(msg.tabId, msg.action);
        return null;
    }
  }

  // ---- requests from content scripts inside web pages (narrow on purpose)

  async function handleContent(msg: ContentRequest, pageUrl: string, tabId: number): Promise<unknown> {
    switch (msg.type) {
      case 'cs.query': {
        const st = await state();
        if (st !== 'ready') return { state: st, logins: [] } satisfies QueryResult;
        const logins = await withVault(async (v) => v.forPage(pageUrl).map(({ id, username, title }) => ({ id, username, ...(title && { title }) })));
        return { state: st, logins } satisfies QueryResult;
      }
      case 'cs.fill': {
        // The site is the browser-reported URL of the frame that asked, so a page can only ever
        // receive credentials saved for itself.
        const entry = await withVault(async (v) => v.get(msg.id));
        if (!entry || !originMatches(entry.origin, pageUrl)) throw new Error('That login is not for this site');
        return { username: entry.username, password: entry.password };
      }
      case 'cs.captured': {
        const origin = normalizeOrigin(pageUrl);
        if (!origin || typeof msg.password !== 'string' || !msg.password || msg.password.length > 4096) return null;
        const pending: Pending = { origin, username: String(msg.username ?? '').slice(0, 512), password: msg.password, at: Date.now() };
        await session.set({ [PENDING_PREFIX + tabId]: pending });
        return pendingPrompt(tabId);
      }
      case 'cs.pending':
        return pendingPrompt(tabId);
      case 'cs.resolvePending':
        await resolvePending(tabId, msg.action);
        return null;
    }
  }

  browser.runtime.onMessage.addListener((msg: PageRequest | ContentRequest, sender, sendResponse: (r: Response) => void) => {
    if (sender.id !== browser.runtime.id || typeof msg?.type !== 'string') return false;
    const fromExtensionPage = !!sender.url?.startsWith(browser.runtime.getURL('/')) && !sender.tab?.url?.startsWith('http');
    const fromContentScript = !!sender.tab?.id && /^https?:/.test(sender.url ?? '');
    let work: () => Promise<unknown>;
    if (fromExtensionPage && !msg.type.startsWith('cs.')) work = () => handlePage(msg as PageRequest);
    else if (fromContentScript && msg.type.startsWith('cs.')) work = () => handleContent(msg as ContentRequest, sender.url!, sender.tab!.id!);
    else return false;
    oc.ready()
      .then(work)
      .then(
        (data) => sendResponse({ ok: true, data }),
        (e: unknown) => sendResponse({ ok: false, error: e instanceof Error ? e.message : 'failed' }),
      );
    return true;
  });
});
