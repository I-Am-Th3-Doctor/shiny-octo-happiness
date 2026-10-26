/** Shared plumbing for the real-browser E2E tests (Chromium, i.e. the engine Brave is built on). */
import { existsSync, mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { serve } from '@hono/node-server';
import { chromium, type BrowserContext, type Page } from 'playwright-core';
import { createApp } from '@omnisync/backend';
import { createPgliteDb, migrate, type Db } from '@omnisync/backend/db';

export const EXT = resolve(import.meta.dirname, '../.output/chrome-mv3');
export const MASTER_PASSWORD = 'a long and correct master password';

export const log = (s: string) => console.log(`[e2e] ${s}`);
export const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

export async function waitFor<T>(what: string, fn: () => Promise<T | undefined | null | false>, timeoutMs = 30_000): Promise<{ value: T; ms: number }> {
  const t0 = Date.now();
  for (;;) {
    const value = await fn().catch(() => undefined);
    if (value) return { value, ms: Date.now() - t0 };
    if (Date.now() - t0 > timeoutMs) throw new Error(`timed out after ${timeoutMs} ms waiting for ${what}`);
    await sleep(250);
  }
}

function chromiumPath() {
  return [process.env.CHROMIUM_PATH, '/opt/pw-browsers/chromium-1194/chrome-linux/chrome'].find((p) => p && existsSync(p));
}

export interface Profile {
  ctx: BrowserContext;
  extId: string;
  dir: string;
}

export async function launchProfile(name: string): Promise<Profile> {
  if (!existsSync(join(EXT, 'manifest.json'))) throw new Error('Build the extension first (pnpm build:brave)');
  const dir = mkdtempSync(join(tmpdir(), `omnisync-${name}-`));
  const ctx = await chromium.launchPersistentContext(dir, {
    executablePath: chromiumPath(),
    headless: process.env.HEADED !== '1',
    args: [`--disable-extensions-except=${EXT}`, `--load-extension=${EXT}`],
  });
  const sw = ctx.serviceWorkers()[0] ?? (await ctx.waitForEvent('serviceworker'));
  return { ctx, extId: new URL(sw.url()).host, dir };
}

export async function closeProfile(p: Profile) {
  await p.ctx.close();
  rmSync(p.dir, { recursive: true, force: true });
}

export async function openExtensionPage(p: Profile, page: 'popup.html' | 'vault.html'): Promise<Page> {
  const tab = await p.ctx.newPage();
  await tab.goto(`chrome-extension://${p.extId}/${page}`);
  return tab;
}

export async function authenticate(page: Page, server: string, mode: 'sign-up' | 'sign-in', device: string) {
  await page.waitForSelector('#view-signed-out:not([hidden])');
  await page.fill('#server', server);
  await page.fill('#username', 'e2e-user@example.com');
  await page.fill('#password', MASTER_PASSWORD);
  await page.fill('#device-name', device);
  const t0 = Date.now();
  await page.click(`#${mode}`);
  await page.waitForSelector('#view-ready:not([hidden])', { timeout: 60_000 });
  return Date.now() - t0;
}

export async function listen(fetch: (r: Request) => Response | Promise<Response>, hostname = '127.0.0.1') {
  const server = await new Promise<ReturnType<typeof serve>>((res) => {
    const s = serve({ fetch, port: 0, hostname }, () => res(s));
  });
  const addr = server.address();
  return { server, port: typeof addr === 'object' && addr ? addr.port : 0 };
}

export async function startBackend(): Promise<{ db: Db; url: string; close: () => Promise<void> }> {
  const db = await createPgliteDb();
  await migrate(db);
  const { server, port } = await listen(createApp({ db }).fetch);
  return {
    db,
    url: `http://127.0.0.1:${port}`,
    close: async () => {
      server.close();
      await db.close();
    },
  };
}
