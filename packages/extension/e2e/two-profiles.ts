/**
 * Phase 1 success criterion, automated:
 *   install the built extension in two separate Chromium profiles, create an account in one,
 *   sign in on the other, add a bookmark in the first, and see it appear in the second
 *   within 30 seconds — with no manual "Sync now" on the receiving side.
 *
 * Usage: pnpm e2e   (builds first). Env: CHROMIUM_PATH to override the browser binary, HEADED=1 to watch.
 */
import { authenticate, closeProfile, launchProfile, log, openExtensionPage, startBackend, waitFor } from './helpers.ts';

const DEADLINE_MS = 30_000;
const URL_UNDER_TEST = `https://example.org/omnisync-e2e-${Date.now()}`;
const TITLE = 'OmniSync E2E bookmark';

async function main() {
  const backend = await startBackend();
  const { db, url: serverUrl } = backend;
  log(`backend on ${serverUrl}`);

  const a = await launchProfile('profile-a');
  const b = await launchProfile('profile-b');
  log(`extension loaded in two profiles (id ${a.extId})`);

  try {
    const popupA = await openExtensionPage(a, 'popup.html');
    log(`profile A: account created in ${await authenticate(popupA, serverUrl, 'sign-up', 'Profile A')} ms (Argon2id 64 MiB in the service worker)`);
    const popupB = await openExtensionPage(b, 'popup.html');
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

    // Folder structure, moves and in-folder order.
    const titlesIn = (p: typeof popupA, folder: string) =>
      p.evaluate(async (name) => {
        const [f] = (await chrome.bookmarks.search({ title: name })).filter((n) => !n.url);
        return f ? (await chrome.bookmarks.getChildren(f.id)).map((n) => n.title).join(',') : null;
      }, folder);
    await popupA.evaluate(async () => {
      const f = await chrome.bookmarks.create({ parentId: '1', title: 'Ordered' });
      const sub = await chrome.bookmarks.create({ parentId: f.id, title: 'Sub' });
      for (const t of ['A', 'B', 'C', 'D']) await chrome.bookmarks.create({ parentId: f.id, title: t, url: `https://${t.toLowerCase()}.example/` });
      await chrome.bookmarks.create({ parentId: sub.id, title: 'Deep', url: 'https://deep.example/' });
    });
    await waitFor('folder tree in B', async () => (await titlesIn(popupB, 'Ordered')) === 'Sub,A,B,C,D' && (await titlesIn(popupB, 'Sub')) === 'Deep');
    log('profile B: nested folder structure arrived intact');
    await popupB.evaluate(async () => {
      const [a] = await chrome.bookmarks.search({ url: 'https://a.example/' });
      await chrome.bookmarks.move(a!.id, { parentId: a!.parentId, index: 4 }); // Chromium: lands at 3 → Sub,B,C,A,D
      const [deep] = await chrome.bookmarks.search({ url: 'https://deep.example/' });
      await chrome.bookmarks.move(deep!.id, { parentId: '2' }); // out of the folder into Other bookmarks
    });
    await waitFor('reorder + move in A', async () => (await titlesIn(popupA, 'Ordered')) === 'Sub,B,C,A,D' && (await titlesIn(popupA, 'Sub')) === '');
    log('profile A: in-folder reorder and cross-folder move from B arrived in the exact order');

    // Duplicate URL in the same folder collapses to one, in both profiles.
    await popupA.evaluate(async () => {
      const [b] = await chrome.bookmarks.search({ url: 'https://b.example/' });
      await chrome.bookmarks.create({ parentId: b!.parentId, title: 'B again', url: 'https://b.example/' });
    });
    await waitFor('dedupe in both', async () => (await titlesIn(popupA, 'Ordered')) === 'Sub,B,C,A,D' && (await titlesIn(popupB, 'Ordered')) === 'Sub,B,C,A,D');
    log('duplicate URL added in the same folder was collapsed to one in both profiles');

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
    await closeProfile(a);
    await closeProfile(b);
    await backend.close();
  }
}

main().catch((e) => {
  console.error('[e2e] FAIL:', e instanceof Error ? e.message : e);
  process.exit(1);
});
