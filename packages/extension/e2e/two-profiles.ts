/**
 * Phase 1 success criterion, automated:
 *   install the built extension in two separate Chromium profiles, create an account in one,
 *   sign in on the other, add a bookmark in the first, and see it appear in the second
 *   within 30 seconds — with no manual "Sync now" on the receiving side.
 *
 * Usage: pnpm e2e   (builds first). Env: CHROMIUM_PATH to override the browser binary, HEADED=1 to watch.
 */
import { existsSync, mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { serve } from '@hono/node-server';
import { chromium, type BrowserContext, type Page } from 'playwright-core';
import { createApp } from '@omnisync/backend';
import { createPgliteDb, migrate } from '@omnisync/backend/db';

const EXT = resolve(import.meta.dirname, '../.output/chrome-mv3');
const DEADLINE_MS = 30_000;
const URL_UNDER_TEST = `https://example.org/omnisync-e2e-${Date.now()}`;
const TITLE = 'OmniSync E2E bookmark';

function chromiumPath() {
  const candidates = [process.env.CHROMIUM_PATH, '/opt/pw-browsers/chromium-1194/chrome-linux/chrome'];
  return candidates.find((p) => p && existsSync(p));
}

async function launchProfile(name: string): Promise<{ ctx: BrowserContext; extId: string; dir: string }> {
  const dir = mkdtempSync(join(tmpdir(), `omnisync-${name}-`));
  const ctx = await chromium.launchPersistentContext(dir, {
    executablePath: chromiumPath(),
    headless: process.env.HEADED !== '1',
    args: [`--disable-extensions-except=${EXT}`, `--load-extension=${EXT}`],
  });
  const sw = ctx.serviceWorkers()[0] ?? (await ctx.waitForEvent('serviceworker'));
  return { ctx, extId: new URL(sw.url()).host, dir };
}

async function openPopup(ctx: BrowserContext, extId: string): Promise<Page> {
  const page = await ctx.newPage();
  await page.goto(`chrome-extension://${extId}/popup.html`);
  return page;
}

async function authenticate(page: Page, server: string, mode: 'sign-up' | 'sign-in', device: string) {
  await page.waitForSelector('#view-signed-out:not([hidden])');
  await page.fill('#server', server);
  await page.fill('#username', 'e2e-user@example.com');
  await page.fill('#password', 'a long and correct master password');
  await page.fill('#device-name', device);
  const t0 = Date.now();
  await page.click(`#${mode}`);
  await page.waitForSelector('#view-ready:not([hidden])', { timeout: 60_000 });
  return Date.now() - t0;
}

const log = (s: string) => console.log(`[e2e] ${s}`);

async function main() {
  if (!existsSync(join(EXT, 'manifest.json'))) throw new Error('Build the extension first (pnpm build)');

  const db = await createPgliteDb();
  await migrate(db);
  const app = createApp({ db });
  const server = await new Promise<ReturnType<typeof serve>>((res) => {
    const s = serve({ fetch: app.fetch, port: 0, hostname: '127.0.0.1' }, () => res(s));
  });
  const addr = server.address();
  const serverUrl = `http://127.0.0.1:${typeof addr === 'object' && addr ? addr.port : 0}`;
  log(`backend on ${serverUrl}`);

  const a = await launchProfile('profile-a');
  const b = await launchProfile('profile-b');
  log(`extension loaded in two profiles (id ${a.extId})`);

  try {
    const popupA = await openPopup(a.ctx, a.extId);
    log(`profile A: account created in ${await authenticate(popupA, serverUrl, 'sign-up', 'Profile A')} ms (Argon2id 64 MiB in the service worker)`);
    const popupB = await openPopup(b.ctx, b.extId);
    log(`profile B: signed in in ${await authenticate(popupB, serverUrl, 'sign-in', 'Profile B')} ms`);
    // Let both finish their initial sync so the timer below measures only propagation.
    await new Promise((r) => setTimeout(r, 3000));

    // Add a bookmark in A exactly as a user would land it in the Bookmarks bar.
    const t0 = Date.now();
    await popupA.evaluate(
      ({ title, url }) => chrome.bookmarks.create({ parentId: '1', title, url }),
      { title: TITLE, url: URL_UNDER_TEST },
    );
    log('profile A: bookmark added; waiting for profile B (no manual sync there)…');

    let found: chrome.bookmarks.BookmarkTreeNode | undefined;
    while (!found && Date.now() - t0 < DEADLINE_MS + 5000) {
      [found] = await popupB.evaluate((url) => chrome.bookmarks.search({ url }), URL_UNDER_TEST);
      if (!found) await new Promise((r) => setTimeout(r, 250));
    }
    const elapsed = Date.now() - t0;
    if (!found) throw new Error(`bookmark did not reach profile B within ${elapsed} ms`);
    if (found.title !== TITLE || found.parentId !== '1') throw new Error(`wrong bookmark in B: ${JSON.stringify(found)}`);
    log(`profile B: bookmark arrived in Bookmarks bar after ${(elapsed / 1000).toFixed(1)} s`);
    if (elapsed > DEADLINE_MS) throw new Error(`too slow: ${elapsed} ms > ${DEADLINE_MS} ms`);

    // And back: rename in B, observe in A.
    const t1 = Date.now();
    await popupB.evaluate(({ id }) => chrome.bookmarks.update(id, { title: 'Renamed in B' }), { id: found.id });
    let renamed = false;
    while (!renamed && Date.now() - t1 < DEADLINE_MS + 5000) {
      const [n] = await popupA.evaluate((url) => chrome.bookmarks.search({ url }), URL_UNDER_TEST);
      renamed = n?.title === 'Renamed in B';
      if (!renamed) await new Promise((r) => setTimeout(r, 250));
    }
    if (!renamed) throw new Error('rename did not propagate B → A');
    log(`profile A: rename from B arrived after ${((Date.now() - t1) / 1000).toFixed(1)} s`);

    // Steady state: idle past the post-sign-in window so only alarm-driven windows are active,
    // then delete in A and expect it gone from B.
    log('idling 40 s so both service workers fall back to alarm-driven sync…');
    await new Promise((r) => setTimeout(r, 40_000));
    const t2 = Date.now();
    await popupA.evaluate(async (url) => {
      const [n] = await chrome.bookmarks.search({ url });
      if (n) await chrome.bookmarks.remove(n.id);
    }, URL_UNDER_TEST);
    let gone = false;
    while (!gone && Date.now() - t2 < DEADLINE_MS + 5000) {
      gone = (await popupB.evaluate((url) => chrome.bookmarks.search({ url }), URL_UNDER_TEST)).length === 0;
      if (!gone) await new Promise((r) => setTimeout(r, 250));
    }
    const elapsedDel = Date.now() - t2;
    if (!gone || elapsedDel > DEADLINE_MS) throw new Error(`deletion did not reach B within 30 s (${elapsedDel} ms)`);
    log(`profile B: deletion from A arrived after ${(elapsedDel / 1000).toFixed(1)} s (steady state)`);

    // The server must hold nothing but ciphertext.
    const dump = JSON.stringify(await db.query('select * from records')) + JSON.stringify(await db.query('select * from users'));
    for (const needle of [URL_UNDER_TEST, 'example.org', TITLE, 'Renamed', 'e2e-user']) {
      if (dump.includes(needle)) throw new Error(`plaintext "${needle}" found in server database`);
    }
    const n = (await db.query<{ n: number }>('select count(*)::int n from records'))[0]!.n;
    log(`server database holds ${n} records, all ciphertext (no URL, title, or username found)`);
    log('PASS');
  } finally {
    await a.ctx.close();
    await b.ctx.close();
    server.close();
    await db.close();
    rmSync(a.dir, { recursive: true, force: true });
    rmSync(b.dir, { recursive: true, force: true });
  }
}

main().catch((e) => {
  console.error('[e2e] FAIL:', e instanceof Error ? e.message : e);
  process.exit(1);
});
