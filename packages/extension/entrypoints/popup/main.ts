import { browser } from 'wxt/browser';
import type { LoginSummary, PageRequest, PendingAction, PendingPrompt, Response, Status } from '../../src/messages.ts';

const $ = <T extends HTMLElement = HTMLElement>(id: string) => document.getElementById(id) as T;
const input = (id: string) => $<HTMLInputElement>(id);

async function send<T = Status>(msg: PageRequest): Promise<T> {
  const r = (await browser.runtime.sendMessage(msg)) as Response<T>;
  if (!r.ok) throw new Error(r.error);
  return r.data;
}

let tabId: number | undefined;

function showError(msg?: string) {
  $('error').hidden = !msg;
  $('error').textContent = msg ?? '';
}

async function render(s: Status) {
  for (const v of ['signed-out', 'locked', 'ready']) $(`view-${v}`).hidden = s.state !== v;
  $('badge').textContent = s.syncing ? 'syncing…' : s.state;
  showError(s.lastError);
  if (s.state !== 'ready') return;
  $('who').textContent = s.username ?? '';
  $('where').textContent = s.serverUrl ?? '';
  const r = s.lastReport;
  $('last-sync').textContent = s.lastSyncAt
    ? `${new Date(s.lastSyncAt).toLocaleTimeString()}${r ? ` (↓${r.pulled} ↑${r.pushed}${r.conflicts ? `, ${r.conflicts} conflicts` : ''})` : ''}`
    : 'never';
  $('unsupported').hidden = s.bookmarksSupported;
  $('site-access').hidden = s.siteAccess;
  await renderSite();
}

async function renderSite() {
  if (tabId === undefined) return;
  const { logins, pending } = await send<{ logins: LoginSummary[]; pending: PendingPrompt | null }>({ type: 'pw.forTab', tabId });
  $('pending').hidden = !pending;
  if (pending) {
    const site = new URL(pending.origin).host;
    $('pending-text').textContent = `${pending.kind === 'update' ? 'Update' : 'Save'} the password${pending.username ? ` for ${pending.username}` : ''} on ${site}?`;
    $('pending-save').textContent = pending.kind === 'update' ? 'Update' : 'Save';
  }
  const list = $('site-logins');
  list.replaceChildren(
    ...logins.map((l) => {
      const li = document.createElement('li');
      const who = document.createElement('span');
      who.className = 'who';
      who.textContent = l.username || '(no username)';
      who.title = l.title ?? l.origin;
      const fill = document.createElement('button');
      fill.className = 'small';
      fill.textContent = 'Fill';
      fill.addEventListener('click', () =>
        send<null>({ type: 'pw.fillTab', tabId: tabId!, id: l.id }).then(() => window.close(), (e) => showError(String(e.message ?? e))),
      );
      li.append(who, fill);
      return li;
    }),
  );
  $('site-empty').hidden = logins.length > 0;
}

async function run(fn: () => Promise<Status>) {
  document.querySelectorAll('button').forEach((b) => (b.disabled = true));
  try {
    await render(await fn());
  } catch (e) {
    showError(e instanceof Error ? e.message : String(e));
  } finally {
    document.querySelectorAll('button').forEach((b) => (b.disabled = false));
  }
}

const isLocal = (url: URL) => url.hostname === 'localhost' || url.hostname === '127.0.0.1';

/**
 * Must run synchronously inside the click handler: permission prompts need a user gesture.
 * Also asked for localhost: Zen/Firefox MV3 treats host permissions as opt-in (Brave resolves instantly).
 */
function requestServerPermission(serverUrl: string): Promise<boolean> {
  const url = new URL(serverUrl);
  if (url.protocol !== 'https:' && !isLocal(url)) throw new Error('Use https:// for remote sync servers');
  return browser.permissions.request({ origins: [`${url.protocol}//${url.hostname}/*`] });
}

function authenticate(type: 'signIn' | 'signUp') {
  const form = $<HTMLFormElement>('auth-form');
  if (!form.reportValidity()) return;
  const serverUrl = input('server').value.trim();
  let granted: Promise<boolean>;
  try {
    granted = requestServerPermission(serverUrl);
  } catch (e) {
    return showError(e instanceof Error ? e.message : String(e));
  }
  void run(async () => {
    if (!(await granted)) throw new Error('OmniSync needs permission to reach your sync server');
    const status = await send({ type, serverUrl, username: input('username').value, password: input('password').value, deviceName: input('device-name').value });
    input('password').value = '';
    return status;
  });
}

const resolve = (action: PendingAction) => () =>
  void run(async () => {
    await send<null>({ type: 'pw.resolvePending', tabId: tabId!, action });
    return send({ type: 'status' });
  });

$('auth-form').addEventListener('submit', (e) => {
  e.preventDefault();
  authenticate('signIn');
});
$('sign-up').addEventListener('click', () => authenticate('signUp'));
$('unlock-form').addEventListener('submit', (e) => {
  e.preventDefault();
  void run(async () => {
    const s = await send({ type: 'unlock', password: input('unlock-password').value });
    input('unlock-password').value = '';
    return s;
  });
});
$('grant-access').addEventListener('click', () => {
  void browser.permissions.request({ origins: ['<all_urls>'] }).then(() => run(() => send({ type: 'status' })));
});
$('pending-save').addEventListener('click', resolve('save'));
$('pending-never').addEventListener('click', resolve('never'));
$('pending-dismiss').addEventListener('click', resolve('dismiss'));
$('sync-now').addEventListener('click', () => void run(() => send({ type: 'syncNow' })));
$('open-vault').addEventListener('click', () => void browser.tabs.create({ url: browser.runtime.getURL('/vault.html') }).then(() => window.close()));
$('lock').addEventListener('click', () => void run(() => send({ type: 'lock' })));
$('sign-out').addEventListener('click', () => void run(() => send({ type: 'signOut' })));
$('sign-out-locked').addEventListener('click', () => void run(() => send({ type: 'signOut' })));

const ua = navigator.userAgent;
input('device-name').value = /Firefox|Zen/i.test(ua) ? 'Zen' : 'Brave';

void (async () => {
  const [tab] = await browser.tabs.query({ active: true, currentWindow: true }).catch(() => []);
  tabId = tab?.id;
  await run(() => send({ type: 'status' }));
  // Opening the popup is a good moment to pull.
  void send({ type: 'syncNow' }).then(render).catch(() => {});
})();
