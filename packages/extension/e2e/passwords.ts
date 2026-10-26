/**
 * Password vault, end to end in two real Chromium profiles (A and B) with the built extension:
 *   capture on a classic <form> login → "Save password?" prompt on the next page → saved in A
 *   → synced to B → B's autofill dropdown fills the form → popup "Fill" works → a different
 *   site gets nothing → a JavaScript-only (no <form>) login is captured → update flow →
 *   CSV import (Brave export format) → no plaintext password on the server or in local storage.
 *
 * OmniSync's in-page UI lives in a closed shadow root (the page can't read it, and neither can
 * Playwright's selectors), so the test clicks it by position, exactly like a user would.
 */
import type { Page } from 'playwright-core';
import { authenticate, closeProfile, launchProfile, listen, log, openExtensionPage, sleep, startBackend, waitFor, type Profile } from './helpers.ts';

const EMAIL = 'alice@example.com';
const PASSWORD = 'Tr0ub4dor&3-e2e-secret';
const NEW_PASSWORD = 'n3w-Pa55word-after-update';

const page = (body: string) =>
  new Response(`<!doctype html><html><head><meta charset="utf-8"><title>Test site</title></head><body>${body}</body></html>`, {
    headers: { 'content-type': 'text/html; charset=utf-8' },
  });

/** A stand-in website with two kinds of login. */
function site(req: Request) {
  const { pathname } = new URL(req.url);
  if (pathname === '/login')
    return page(`<h1>Sign in</h1>
      <form action="/welcome" method="post">
        <input id="email" name="email" type="email" autocomplete="username" placeholder="Email">
        <input id="pw" name="pw" type="password" autocomplete="current-password" placeholder="Password">
        <button type="submit">Sign in</button>
      </form>`);
  if (pathname === '/welcome') return page('<h1>Welcome back</h1>');
  if (pathname === '/spa')
    return page(`<h1>App</h1>
      <div id="box"><input id="user" placeholder="Username"><input id="pass" type="password">
      <div role="button" id="go" tabindex="0">Log in</div></div>
      <script>document.getElementById('go').onclick = () => setTimeout(() => { document.getElementById('box').textContent = 'Signed in'; }, 300);</script>`);
  return new Response('not found', { status: 404 });
}

/** Click the primary ("Save"/"Update") button of OmniSync's in-page prompt. */
async function clickBannerButton(p: Page, which: 'primary' | 'never' = 'primary') {
  const host = p.locator('omnisync-ui').last();
  await host.waitFor({ state: 'attached', timeout: 10_000 });
  await sleep(150);
  const box = (await host.boundingBox())!;
  const x = which === 'primary' ? box.x + 30 : box.x + 120;
  await p.mouse.click(x, box.y + box.height - 26);
}

/** Click the last entry of OmniSync's autofill dropdown under the focused field. */
async function clickDropdownLast(p: Page) {
  const host = p.locator('omnisync-ui').last();
  await host.waitFor({ state: 'attached', timeout: 10_000 });
  await sleep(150);
  const box = (await host.boundingBox())!;
  await p.mouse.click(box.x + 40, box.y + box.height - 15);
}

async function vaultUsernames(p: Profile): Promise<string[]> {
  const v = await openExtensionPage(p, 'vault.html');
  try {
    await v.waitForSelector('#open:not([hidden])', { timeout: 5000 });
    return await v.$$eval('#rows tr td:nth-child(2)', (tds) => tds.map((t) => t.textContent ?? ''));
  } finally {
    await v.close();
  }
}

/** Ask the background directly, from an extension page, as the popup and vault page do. */
async function ext<T>(p: Profile, msg: unknown): Promise<T> {
  const v = await openExtensionPage(p, 'vault.html');
  try {
    const r = await v.evaluate((m) => chrome.runtime.sendMessage(m), msg);
    if (!r.ok) throw new Error(r.error);
    return r.data as T;
  } finally {
    await v.close();
  }
}

async function main() {
  const backend = await startBackend();
  const web = await listen(site);
  const SITE = `http://127.0.0.1:${web.port}`;
  const OTHER_SITE = `http://localhost:${web.port}`; // same server, different host = different site
  log(`backend ${backend.url}, test site ${SITE}`);

  const a = await launchProfile('pw-a');
  const b = await launchProfile('pw-b');
  try {
    await authenticate(await openExtensionPage(a, 'popup.html'), backend.url, 'sign-up', 'A');
    await authenticate(await openExtensionPage(b, 'popup.html'), backend.url, 'sign-in', 'B');

    // 1. Capture on a classic form login, prompt shown after navigation, save.
    const pa = await a.ctx.newPage();
    await pa.goto(`${SITE}/login`);
    await pa.fill('#email', EMAIL);
    await pa.fill('#pw', PASSWORD);
    await pa.click('button[type=submit]');
    await pa.waitForURL('**/welcome');
    await clickBannerButton(pa);
    await waitFor('login saved in A', async () => (await vaultUsernames(a)).includes(EMAIL), 10_000);
    log('A: form login captured, "Save password?" shown after navigation, saved to vault');

    // 2. Sync to B.
    const { ms } = await waitFor('login synced to B', async () => (await vaultUsernames(b)).includes(EMAIL));
    log(`B: login arrived via sync after ${(ms / 1000).toFixed(1)} s`);

    // 3. Autofill dropdown in B.
    const pb = await b.ctx.newPage();
    await pb.goto(`${SITE}/login`);
    await pb.click('#email');
    await clickDropdownLast(pb);
    await waitFor('fields filled by dropdown', async () => (await pb.inputValue('#pw')) === PASSWORD && (await pb.inputValue('#email')) === EMAIL, 5000);
    log('B: autofill dropdown filled username + password');

    // 4. Fill from the toolbar popup (same message the popup's Fill button sends).
    await pb.close();
    const pb2 = await b.ctx.newPage();
    await pb2.goto(`${SITE}/login`);
    const tabId = await (async () => {
      const v = await openExtensionPage(b, 'vault.html');
      const id = await v.evaluate(async (u) => (await chrome.tabs.query({ url: `${u}/login` }))[0]?.id, SITE);
      await v.close();
      return id!;
    })();
    const forTab = await ext<{ logins: { id: string; username: string }[] }>(b, { type: 'pw.forTab', tabId });
    if (forTab.logins.length !== 1 || forTab.logins[0]!.username !== EMAIL) throw new Error(`popup list wrong: ${JSON.stringify(forTab)}`);
    await ext(b, { type: 'pw.fillTab', tabId, id: forTab.logins[0]!.id });
    await waitFor('fields filled from popup', async () => (await pb2.inputValue('#pw')) === PASSWORD, 5000);
    log('B: popup "Fill" filled the page');

    // 5. A different site gets nothing.
    const evil = await b.ctx.newPage();
    await evil.goto(`${OTHER_SITE}/login`);
    await evil.click('#email');
    await sleep(1500);
    if ((await evil.locator('omnisync-ui').count()) > 0) throw new Error('dropdown shown on a different site');
    const evilTab = await (async () => {
      const v = await openExtensionPage(b, 'vault.html');
      const id = await v.evaluate(async (u) => (await chrome.tabs.query({ url: `${u}/login` }))[0]?.id, OTHER_SITE);
      await v.close();
      return id!;
    })();
    const refused = await ext(b, { type: 'pw.fillTab', tabId: evilTab, id: forTab.logins[0]!.id }).then(() => false, (e: Error) => /not for this site/.test(e.message));
    if (!refused) throw new Error('fill was allowed on a different site');
    if ((await evil.inputValue('#pw')) !== '') throw new Error('password leaked into a different site');
    log('B: different site (localhost vs 127.0.0.1): no dropdown, fill refused');

    // 6. JavaScript-only login (no <form>, role=button): prompt appears on the same page.
    await pa.goto(`${SITE}/spa`);
    await pa.fill('#user', 'second-account');
    await pa.fill('#pass', 'spa-password-123');
    await pa.click('#go');
    await clickBannerButton(pa);
    await waitFor('SPA login saved', async () => (await vaultUsernames(a)).includes('second-account'), 10_000);
    log('A: JavaScript login without a <form> captured and saved');

    // 7. Update: same account, new password → "Update" prompt → B gets the new password.
    await pa.goto(`${SITE}/login`);
    await pa.fill('#email', EMAIL);
    await pa.fill('#pw', NEW_PASSWORD);
    await pa.click('button[type=submit]');
    await pa.waitForURL('**/welcome');
    await clickBannerButton(pa);
    const id = forTab.logins[0]!.id;
    await waitFor('updated password in B', async () => (await ext<string>(b, { type: 'pw.reveal', id })) === NEW_PASSWORD);
    if ((await vaultUsernames(b)).filter((u) => u === EMAIL).length !== 1) throw new Error('update created a duplicate');
    log('A→B: password change captured as an update (no duplicate) and synced');

    // 8. CSV import from a Brave export in A, appears in B.
    const vaultA = await openExtensionPage(a, 'vault.html');
    await vaultA.waitForSelector('#open:not([hidden])');
    const csv = 'name,url,username,password,note\ngithub.com,https://github.com/session,octocat,gh-csv-secret,\nApp,android://x@com.app/,u,p,\n';
    await vaultA.setInputFiles('#csv', { name: 'Brave Passwords.csv', mimeType: 'text/csv', buffer: Buffer.from(csv) });
    await vaultA.waitForFunction(() => document.getElementById('import-result')?.textContent?.includes('added'));
    log(`A: ${await vaultA.textContent('#import-result')}`);
    await waitFor('CSV import synced to B', async () => (await vaultUsernames(b)).includes('octocat'));
    log('B: imported login arrived');

    // 9. Nothing readable on the server or in either browser's extension storage.
    const server = JSON.stringify(await backend.db.query('select * from records'));
    const storage = await vaultA.evaluate(async () => JSON.stringify(await chrome.storage.local.get(null)));
    for (const secret of [PASSWORD, NEW_PASSWORD, 'spa-password-123', 'gh-csv-secret', EMAIL, 'octocat']) {
      if (server.includes(secret)) throw new Error(`plaintext "${secret}" on the server`);
      if (storage.includes(secret)) throw new Error(`plaintext "${secret}" in extension storage`);
    }
    log('server database and extension storage contain no plaintext passwords or usernames');
    log('PASS');
  } finally {
    await closeProfile(a);
    await closeProfile(b);
    web.server.close();
    await backend.close();
  }
}

main().catch((e) => {
  console.error('[e2e] FAIL:', e instanceof Error ? e.message : e);
  process.exit(1);
});
