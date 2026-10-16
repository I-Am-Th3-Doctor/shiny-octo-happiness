import { browser } from 'wxt/browser';
import type { Request, Response, Status } from '../../src/messages.ts';

const $ = <T extends HTMLElement = HTMLElement>(id: string) => document.getElementById(id) as T;
const input = (id: string) => $<HTMLInputElement>(id);

async function send(msg: Request): Promise<Status> {
  const r = (await browser.runtime.sendMessage(msg)) as Response;
  if (!r.ok) throw new Error(r.error);
  return r.status;
}

function render(s: Status) {
  for (const v of ['signed-out', 'locked', 'ready']) $(`view-${v}`).hidden = s.state !== v;
  $('badge').textContent = s.syncing ? 'syncing…' : s.state;
  if (s.state === 'ready') {
    $('who').textContent = s.username ?? '';
    $('where').textContent = s.serverUrl ?? '';
    const r = s.lastReport;
    $('last-sync').textContent = s.lastSyncAt
      ? `${new Date(s.lastSyncAt).toLocaleTimeString()}${r ? ` (↓${r.pulled} ↑${r.pushed}${r.conflicts ? `, ${r.conflicts} conflicts` : ''})` : ''}`
      : 'never';
    $('unsupported').hidden = s.bookmarksSupported;
  }
  showError(s.lastError);
}

function showError(msg?: string) {
  $('error').hidden = !msg;
  $('error').textContent = msg ?? '';
}

async function run(_btn: HTMLElement | null, fn: () => Promise<Status>) {
  document.querySelectorAll('button').forEach((b) => (b.disabled = true));
  try {
    render(await fn());
  } catch (e) {
    showError(e instanceof Error ? e.message : String(e));
  } finally {
    document.querySelectorAll('button').forEach((b) => (b.disabled = false));
  }
}

function isLocal(url: URL) {
  return url.hostname === 'localhost' || url.hostname === '127.0.0.1';
}

/**
 * Must run synchronously inside the click handler: permission prompts need a user gesture.
 * Also asked for localhost: Firefox MV3 treats host_permissions as opt-in (Chrome resolves instantly).
 */
function requestServerPermission(serverUrl: string): Promise<boolean> {
  const url = new URL(serverUrl);
  if (url.protocol !== 'https:' && !isLocal(url)) throw new Error('Use https:// for remote sync servers');
  return browser.permissions.request({ origins: [`${url.protocol}//${url.hostname}/*`] });
}

function authenticate(type: 'signIn' | 'signUp', btn: HTMLElement) {
  const form = $<HTMLFormElement>('auth-form');
  if (!form.reportValidity()) return;
  const serverUrl = input('server').value.trim();
  let granted: Promise<boolean>;
  try {
    granted = requestServerPermission(serverUrl);
  } catch (e) {
    return showError(e instanceof Error ? e.message : String(e));
  }
  void run(btn, async () => {
    if (!(await granted)) throw new Error('OmniSync needs permission to reach your sync server');
    const status = await send({
      type,
      serverUrl,
      username: input('username').value,
      password: input('password').value,
      deviceName: input('device-name').value,
    });
    input('password').value = '';
    return status;
  });
}

$('auth-form').addEventListener('submit', (e) => {
  e.preventDefault();
  authenticate('signIn', $('sign-in'));
});
$('sign-up').addEventListener('click', () => authenticate('signUp', $('sign-up')));
$('unlock-form').addEventListener('submit', (e) => {
  e.preventDefault();
  void run(null, async () => {
    const s = await send({ type: 'unlock', password: input('unlock-password').value });
    input('unlock-password').value = '';
    return s;
  });
});
$('sync-now').addEventListener('click', () => void run($('sync-now'), () => send({ type: 'syncNow' })));
$('lock').addEventListener('click', () => void run(null, () => send({ type: 'lock' })));
$('sign-out').addEventListener('click', () => void run(null, () => send({ type: 'signOut' })));
$('sign-out-locked').addEventListener('click', () => void run(null, () => send({ type: 'signOut' })));

input('device-name').value = /Android/i.test(navigator.userAgent)
  ? 'Android'
  : `${/Firefox/i.test(navigator.userAgent) ? 'Firefox' : 'Chromium'} on ${navigator.platform || 'desktop'}`;

void send({ type: 'status' }).then(render, (e) => showError(String(e)));
// Opening the popup is a good moment to pull.
void send({ type: 'syncNow' }).then(render).catch(() => {});
