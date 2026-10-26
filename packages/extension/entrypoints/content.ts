/**
 * Runs inside web pages. Two jobs:
 *  1. Capture: when a login form is submitted, hand the username/password to the background,
 *     which offers "Save password?" (here, after the next page load, or in the toolbar popup).
 *  2. Autofill: when a login field is focused, show a small OmniSync dropdown of the logins saved
 *     for this site; picking one fills the form.
 *
 * Capture does not use webRequest: on Chromium MV3 webRequest can only observe requests, and a
 * login is often sent as JSON from JavaScript anyway. Watching the form in the page works in both
 * Brave and Zen.
 *
 * This script never sees the vault or the key. It receives one password only after a real
 * (isTrusted) click by the user on an entry, and only for the site the browser says it's on.
 * All UI lives in a closed shadow root so the page can't read or restyle it.
 */
import { browser } from 'wxt/browser';
import { defineContentScript } from 'wxt/utils/define-content-script';
import type { ContentRequest, FillCommand, PendingAction, PendingPrompt, QueryResult, Response } from '../src/messages.ts';
import { extractCredentials, fillLogin, findFields, isPasswordField, isUsernameLike, scopeOf } from '../src/passwords/forms.ts';
import { originMatches } from '../src/passwords/model.ts';

const Z = '2147483647';
const SUBMIT_TEXT = /log\s?in|sign\s?in|continue|next|submit|enter|anmelden|connexion|iniciar|accedi/i;

/** A click that plausibly submits a login (not e.g. a "show password" eye button). */
function isSubmitLike(el: Element): boolean {
  if (el instanceof HTMLInputElement) return el.type === 'submit' || SUBMIT_TEXT.test(el.value);
  if (el instanceof HTMLButtonElement && el.type === 'submit' && el.form) return true;
  return SUBMIT_TEXT.test(el.textContent ?? '');
}
const isTop = window.top === window;

async function send<T>(msg: ContentRequest): Promise<T> {
  const r = (await browser.runtime.sendMessage(msg)) as Response<T> | undefined;
  if (!r) throw new Error('no response');
  if (!r.ok) throw new Error(r.error);
  return r.data;
}

function shadowHost(): { host: HTMLElement; root: ShadowRoot } {
  const host = document.createElement('omnisync-ui');
  host.style.cssText = `all: initial; position: fixed; z-index: ${Z};`;
  const root = host.attachShadow({ mode: 'closed' });
  const style = document.createElement('style');
  style.textContent = `
    :host { all: initial; }
    * { box-sizing: border-box; font: 13px/1.35 system-ui, -apple-system, sans-serif; }
    .card { background: #fff; color: #1d1d1f; border: 1px solid #d0d0d7; border-radius: 8px;
            box-shadow: 0 6px 24px rgba(0,0,0,.18); overflow: hidden; }
    .brand { font-weight: 600; font-size: 12px; color: #3b6cf6; padding: 8px 12px 2px; }
    .msg { padding: 2px 12px 10px; }
    .row { display: flex; gap: 6px; padding: 0 12px 12px; flex-wrap: wrap; }
    button { cursor: pointer; border-radius: 5px; padding: 5px 10px; border: 1px solid #c8c8d0; background: #fff; color: inherit; }
    button.primary { background: #3b6cf6; border-color: #3b6cf6; color: #fff; }
    .item { display: block; width: 100%; text-align: left; border: 0; border-radius: 0; padding: 8px 12px; }
    .item:hover, .item:focus { background: #eef2ff; outline: none; }
    .sub { color: #6b6b75; font-size: 11px; }
    @media (prefers-color-scheme: dark) {
      .card { background: #26262b; color: #ececf1; border-color: #3a3a42; }
      button { background: #26262b; border-color: #4a4a55; }
      .item:hover, .item:focus { background: #33334a; }
      .sub { color: #a0a0aa; }
    }`;
  root.append(style);
  return { host, root };
}

export default defineContentScript({
  matches: ['http://*/*', 'https://*/*'],
  allFrames: true,
  runAt: 'document_idle',
  main() {
    // ---------------------------------------------------------------- save prompt

    let banner: HTMLElement | null = null;
    const closeBanner = () => {
      banner?.remove();
      banner = null;
    };

    function showBanner(p: PendingPrompt) {
      if (!isTop) return; // keep the prompt out of embedded frames
      closeBanner();
      const { host, root } = shadowHost();
      host.style.top = '16px';
      host.style.right = '16px';
      const card = document.createElement('div');
      card.className = 'card';
      card.style.width = '320px';
      const site = new URL(p.origin).host;
      const who = p.username ? ` for ${p.username}` : '';
      const text = () =>
        p.locked
          ? `OmniSync is locked. Unlock it from the toolbar, then click Save to keep this password for ${site}.`
          : p.kind === 'update'
            ? `Update the saved password${who} on ${site}?`
            : `Save this password${who} on ${site}?`;
      const msg = document.createElement('div');
      msg.className = 'msg';
      msg.textContent = text();
      const brand = document.createElement('div');
      brand.className = 'brand';
      brand.textContent = 'OmniSync';
      const row = document.createElement('div');
      row.className = 'row';
      const button = (label: string, action: PendingAction, primary = false) => {
        const b = document.createElement('button');
        b.textContent = label;
        if (primary) b.className = 'primary';
        b.addEventListener('click', async (e) => {
          if (!e.isTrusted) return;
          try {
            await send({ type: 'cs.resolvePending', action });
            closeBanner();
          } catch (err) {
            p.locked = /locked/i.test(String(err));
            msg.textContent = p.locked ? text() : `Couldn't save: ${err instanceof Error ? err.message : err}`;
          }
        });
        return b;
      };
      row.append(button(p.kind === 'update' ? 'Update' : 'Save', 'save', true), button('Never for this site', 'never'), button('Not now', 'dismiss'));
      card.append(brand, msg, row);
      root.append(card);
      document.documentElement.append(host);
      banner = host;
    }

    // ---------------------------------------------------------------- capture

    let lastCapture = '';
    async function capture(scope: Element) {
      const creds = extractCredentials(scope);
      if (!creds) return;
      const key = `${creds.username}\n${creds.password}`;
      if (key === lastCapture) return; // submit + click + Enter can all fire for one login
      lastCapture = key;
      setTimeout(() => {
        if (lastCapture === key) lastCapture = '';
      }, 5000);
      try {
        const prompt = await send<PendingPrompt | null>({ type: 'cs.captured', ...creds });
        if (prompt) showBanner(prompt);
      } catch {
        /* extension reloaded or vault unavailable: nothing to do */
      }
    }

    document.addEventListener('submit', (e) => e.target instanceof Element && void capture(e.target), true);
    document.addEventListener(
      'click',
      (e) => {
        if (!e.isTrusted || !(e.target instanceof Element)) return;
        const btn = e.target.closest('button, input[type="submit"], input[type="button"], [role="button"]');
        if (btn && !btn.closest('omnisync-ui') && isSubmitLike(btn)) void capture(scopeOf(btn));
      },
      true,
    );
    document.addEventListener(
      'keydown',
      (e) => {
        if (e.isTrusted && e.key === 'Enter' && e.target instanceof HTMLInputElement) void capture(scopeOf(e.target));
      },
      true,
    );

    // A login usually navigates away; the new page picks up the waiting prompt.
    if (isTop) void send<PendingPrompt | null>({ type: 'cs.pending' }).then((p) => p && showBanner(p), () => {});

    // ---------------------------------------------------------------- autofill dropdown

    let dropdown: HTMLElement | null = null;
    let anchor: HTMLInputElement | null = null;
    let cache: { at: number; result: QueryResult } | null = null;

    const closeDropdown = () => {
      dropdown?.remove();
      dropdown = null;
      anchor = null;
    };

    async function query(): Promise<QueryResult> {
      if (cache && Date.now() - cache.at < 10_000) return cache.result;
      const result = await send<QueryResult>({ type: 'cs.query' });
      cache = { at: Date.now(), result };
      return result;
    }

    function position() {
      if (!dropdown || !anchor) return;
      const r = anchor.getBoundingClientRect();
      if (r.bottom < 0 || r.top > innerHeight) return closeDropdown();
      dropdown.style.left = `${Math.max(4, r.left)}px`;
      dropdown.style.top = `${r.bottom + 4}px`;
      dropdown.style.minWidth = `${Math.max(220, r.width)}px`;
    }

    async function openDropdown(field: HTMLInputElement) {
      let res: QueryResult;
      try {
        res = await query();
      } catch {
        return;
      }
      if (document.activeElement !== field) return;
      if (res.state === 'signed-out' || (res.state === 'ready' && res.logins.length === 0)) return;
      closeDropdown();
      const { host, root } = shadowHost();
      const card = document.createElement('div');
      card.className = 'card';
      card.setAttribute('role', 'listbox');
      // Keep focus in the page field while clicking our UI.
      card.addEventListener('mousedown', (e) => e.preventDefault());
      const brand = document.createElement('div');
      brand.className = 'brand';
      brand.textContent = 'OmniSync';
      card.append(brand);
      if (res.state === 'locked') {
        const m = document.createElement('div');
        m.className = 'msg';
        m.textContent = 'Locked. Click the OmniSync toolbar icon to unlock, then focus this field again.';
        card.append(m);
      }
      for (const l of res.logins) {
        const item = document.createElement('button');
        item.className = 'item';
        item.setAttribute('role', 'option');
        item.dataset.omnisyncLogin = l.id;
        const name = document.createElement('div');
        name.textContent = l.username || '(no username)';
        item.append(name);
        if (l.title) {
          const sub = document.createElement('div');
          sub.className = 'sub';
          sub.textContent = l.title;
          item.append(sub);
        }
        item.addEventListener('click', async (e) => {
          if (!e.isTrusted) return; // only a real user click releases a password
          const scope = scopeOf(field);
          closeDropdown();
          try {
            const creds = await send<{ username: string; password: string }>({ type: 'cs.fill', id: l.id });
            fillLogin(scope, creds.username, creds.password);
          } catch {
            /* locked or not for this site */
          }
        });
        card.append(item);
      }
      root.append(card);
      document.documentElement.append(host);
      dropdown = host;
      anchor = field;
      position();
    }

    document.addEventListener(
      'focusin',
      (e) => {
        const t = e.target;
        if (isPasswordField(t) || (isUsernameLike(t) && findFields(scopeOf(t)).passwords.length > 0)) void openDropdown(t);
      },
      true,
    );
    document.addEventListener(
      'focusout',
      () =>
        setTimeout(() => {
          if (document.activeElement !== anchor) closeDropdown();
        }, 150),
      true,
    );
    document.addEventListener('keydown', (e) => e.key === 'Escape' && closeDropdown(), true);
    addEventListener('scroll', position, true);
    addEventListener('resize', position);

    // ---------------------------------------------------------------- fill from the toolbar popup

    if (isTop) {
      browser.runtime.onMessage.addListener((msg: FillCommand) => {
        if (msg?.type !== 'cs.doFill' || !originMatches(msg.origin, location.href)) return;
        const focused = document.activeElement instanceof Element ? document.activeElement : null;
        const scope =
          (focused && findFields(scopeOf(focused)).passwords.length ? scopeOf(focused) : null) ??
          [...document.querySelectorAll('input[type="password"]')].map(scopeOf).find((s) => findFields(s).passwords.length) ??
          document.body;
        fillLogin(scope, msg.username, msg.password);
      });
    }
  },
});
