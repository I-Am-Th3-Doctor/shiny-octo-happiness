/**
 * Background (MV3 service worker on Chromium, event page on Firefox).
 *
 * The worker can be terminated at any moment, so nothing important lives only in memory:
 *   - account config, sync cursor and per-bookmark baselines → storage.local
 *   - the vault key → storage.session (RAM-only, wiped on browser exit, inaccessible to content scripts)
 *   - periodic sync → chrome.alarms (survives worker restarts)
 * Every listener is registered synchronously at top level so events wake the worker.
 */
import * as oc from '@omnisync/crypto';
import { browser } from 'wxt/browser';
import { defineBackground } from 'wxt/utils/define-background';
import { signIn, signUp, unlock, type AccountConfig } from '../src/account.ts';
import { ApiClient, ApiError } from '../src/api.ts';
import { BookmarkAdapter, DIRTY_PREFIX, type BookmarksApi } from '../src/bookmarks/engine.ts';
import { areaKV } from '../src/kv.ts';
import type { Request, Response, Status } from '../src/messages.ts';
import { MAX_WAIT_SECONDS } from '@omnisync/protocol';
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

export default defineBackground(() => {
  const local = areaKV(browser.storage.local);
  const session = areaKV(browser.storage.session);
  const bookmarks = browser.bookmarks as unknown as BookmarksApi | undefined; // absent on Firefox for Android

  let running: Promise<void> | null = null;
  let rerun = false;
  let debounce: ReturnType<typeof setTimeout> | undefined;

  const getConfig = () => local.get<AccountConfig>(CONFIG_KEY);
  const getVaultKey = async () => {
    const k = await session.get<string>(VAULT_KEY);
    return k ? oc.fromB64(k) : undefined;
  };

  async function status(): Promise<Status> {
    const [config, key, saved] = await Promise.all([getConfig(), session.get<string>(VAULT_KEY), local.get<Partial<Status>>(STATUS_KEY)]);
    return {
      ...saved,
      state: !config ? 'signed-out' : key ? 'ready' : 'locked',
      serverUrl: config?.serverUrl,
      username: config?.username,
      deviceId: config?.deviceId,
      bookmarksSupported: !!bookmarks,
      syncing: running !== null,
    };
  }

  function adapters(env: Awaited<ReturnType<typeof makeEnv>>['env']): CollectionAdapter<any>[] {
    return bookmarks ? [new BookmarkAdapter(bookmarks, local, env)] : [];
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
        adapters: adapters(clock.env),
        clock,
      });
      await local.set({ [STATUS_KEY]: { lastSyncAt: Date.now(), lastReport: report, lastError: undefined } });
    } catch (e) {
      // Messages are ours or HTTP status text — never payload content.
      const msg = e instanceof ApiError && e.status === 401 ? 'Session expired — sign in again' : e instanceof Error ? e.message : 'sync failed';
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
        await doSync();
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

  browser.alarms.onAlarm.addListener((a) => {
    if (a.name === ALARM) void watchWindow();
  });
  browser.runtime.onInstalled.addListener(() => void ensureAlarm());
  browser.runtime.onStartup.addListener(() => void ensureAlarm());
  void ensureAlarm();

  async function handle(msg: Request): Promise<Status> {
    switch (msg.type) {
      case 'status':
        return status();
      case 'signUp':
      case 'signIn': {
        const previous = await getConfig();
        const { config, vaultKey } = await (msg.type === 'signUp' ? signUp : signIn)(msg, {
          previousDeviceId: previous?.loginId === oc.deriveLoginId(msg.username) ? previous.deviceId : undefined,
        });
        if (previous && previous.loginId !== config.loginId) await resetSyncState(local);
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
        if (config) await new ApiClient(config.serverUrl, config.token).logout().catch(() => {});
        await session.remove(VAULT_KEY);
        // Drop sync metadata (local bookmarks are kept). A later sign-in re-adopts them without duplicates.
        await resetSyncState(local);
        await local.remove([CONFIG_KEY, STATUS_KEY]);
        return status();
      }
      case 'syncNow':
        await syncNow();
        return status();
    }
  }

  browser.runtime.onMessage.addListener((msg: Request, sender, sendResponse: (r: Response) => void) => {
    // Only our own extension pages may drive the background (there are no content scripts in phase 1).
    if (sender.id !== browser.runtime.id || !sender.url?.startsWith(browser.runtime.getURL('/'))) return false;
    oc.ready()
      .then(() => handle(msg))
      .then(
        (status) => sendResponse({ ok: true, status }),
        (e: unknown) => sendResponse({ ok: false, error: e instanceof Error ? e.message : 'failed' }),
      );
    return true;
  });
});
