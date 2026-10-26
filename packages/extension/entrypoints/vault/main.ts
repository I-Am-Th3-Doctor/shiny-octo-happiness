import { browser } from 'wxt/browser';
import type { LoginInput, LoginSummary, PageRequest, Response, Status } from '../../src/messages.ts';
import { parsePasswordCsv } from '../../src/passwords/csv.ts';

const $ = <T extends HTMLElement = HTMLElement>(id: string) => document.getElementById(id) as T;
const val = (id: string) => $<HTMLInputElement>(id).value;

async function send<T>(msg: PageRequest): Promise<T> {
  const r = (await browser.runtime.sendMessage(msg)) as Response<T>;
  if (!r.ok) throw new Error(r.error);
  return r.data;
}

function showError(e?: unknown) {
  const msg = e === undefined ? '' : e instanceof Error ? e.message : String(e);
  $('error').hidden = !msg;
  $('error').textContent = msg;
}

let logins: LoginSummary[] = [];

async function refresh() {
  showError();
  const s = await send<Status>({ type: 'status' });
  $('locked').hidden = s.state === 'ready';
  $('open').hidden = s.state !== 'ready';
  $('unlock-form').hidden = s.state !== 'locked';
  $('locked-text').textContent = s.state === 'signed-out' ? 'Sign in from the OmniSync toolbar button first.' : 'The vault is locked.';
  if (s.state !== 'ready') return;
  $('site-access').hidden = s.siteAccess;
  logins = await send<LoginSummary[]>({ type: 'pw.list' });
  renderRows();
}

function renderRows() {
  const q = val('search').trim().toLowerCase();
  const shown = logins.filter((l) => !q || `${l.origin} ${l.username} ${l.title ?? ''}`.toLowerCase().includes(q));
  $('count').textContent = `(${logins.length})`;
  $('empty').hidden = logins.length > 0;
  $('rows').replaceChildren(...shown.map(row));
}

function row(l: LoginSummary): HTMLTableRowElement {
  const tr = document.createElement('tr');
  const cell = (text: string, title = text) => {
    const td = document.createElement('td');
    td.textContent = text;
    td.title = title;
    return td;
  };
  const site = cell(l.title ? `${l.title} (${new URL(l.origin).host})` : new URL(l.origin).host, l.origin);
  const pw = cell('••••••••', '');
  pw.className = 'pw';
  const actions = document.createElement('td');
  const btn = (label: string, fn: () => Promise<void>) => {
    const b = document.createElement('button');
    b.textContent = label;
    b.addEventListener('click', () => fn().catch(showError));
    actions.append(b, ' ');
    return b;
  };
  const show = btn('Show', async () => {
    if (pw.textContent !== '••••••••') {
      pw.textContent = '••••••••';
      show.textContent = 'Show';
      return;
    }
    pw.textContent = (await send<string | null>({ type: 'pw.reveal', id: l.id })) ?? '';
    show.textContent = 'Hide';
  });
  btn('Copy', async () => {
    await navigator.clipboard.writeText((await send<string | null>({ type: 'pw.reveal', id: l.id })) ?? '');
  });
  btn('Edit', async () => {
    $<HTMLInputElement>('f-id').value = l.id;
    $<HTMLInputElement>('f-origin').value = l.origin;
    $<HTMLInputElement>('f-username').value = l.username;
    $<HTMLInputElement>('f-password').value = (await send<string | null>({ type: 'pw.reveal', id: l.id })) ?? '';
    $<HTMLInputElement>('f-title').value = l.title ?? '';
    $<HTMLTextAreaElement>('f-notes').value = l.notes ?? '';
    $('form-title').textContent = 'Edit login';
    $('f-cancel').hidden = false;
    $('f-origin').scrollIntoView({ behavior: 'smooth', block: 'center' });
  });
  btn('Delete', async () => {
    if (!confirm(`Delete the login for ${l.username || 'this site'} on ${new URL(l.origin).host}? This deletes it in every synced browser.`)) return;
    await send<null>({ type: 'pw.delete', id: l.id });
    await refresh();
  });
  tr.append(site, cell(l.username || '(none)'), pw, actions);
  return tr;
}

function resetForm() {
  $<HTMLFormElement>('login-form').reset();
  $<HTMLInputElement>('f-id').value = '';
  $('form-title').textContent = 'Add a login';
  $('f-cancel').hidden = true;
}

$('login-form').addEventListener('submit', (e) => {
  e.preventDefault();
  const login: LoginInput = {
    ...(val('f-id') && { id: val('f-id') }),
    origin: val('f-origin'),
    username: val('f-username'),
    password: val('f-password'),
    title: val('f-title'),
    notes: $<HTMLTextAreaElement>('f-notes').value,
  };
  send({ type: 'pw.save', login })
    .then(() => {
      resetForm();
      return refresh();
    })
    .catch(showError);
});
$('f-cancel').addEventListener('click', resetForm);
$('search').addEventListener('input', renderRows);

$('csv').addEventListener('change', async () => {
  const file = $<HTMLInputElement>('csv').files?.[0];
  if (!file) return;
  const out = $('import-result');
  try {
    const parsed = parsePasswordCsv(await file.text());
    const counts = await send<{ added: number; updated: number; unchanged: number; failed: number }>({ type: 'pw.import', logins: parsed.logins });
    const source = { chromium: 'Brave/Chrome', firefox: 'Zen/Firefox', generic: 'CSV' }[parsed.format];
    out.textContent =
      `Imported from ${source} export: ${counts.added} added, ${counts.updated} updated, ${counts.unchanged} already present` +
      (parsed.skipped.length ? `, ${parsed.skipped.length} skipped (not websites or empty passwords)` : '') +
      (counts.failed ? `, ${counts.failed} failed` : '') +
      '.';
    await refresh();
  } catch (e) {
    showError(e);
  } finally {
    $<HTMLInputElement>('csv').value = '';
  }
});

$('unlock-form').addEventListener('submit', (e) => {
  e.preventDefault();
  send({ type: 'unlock', password: val('unlock-password') })
    .then(() => {
      $<HTMLInputElement>('unlock-password').value = '';
      return refresh();
    })
    .catch(showError);
});
$('grant-access').addEventListener('click', () => {
  void browser.permissions.request({ origins: ['<all_urls>'] }).then(refresh, showError);
});

void refresh().catch(showError);
