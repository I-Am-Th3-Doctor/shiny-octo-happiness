/**
 * Login-form detection for the content script. Heuristic by nature: sites build login forms
 * in endless ways (no <form>, multi-step, custom buttons), so this favours common patterns and
 * never guesses a password field that isn't type="password".
 */

const USERNAME_HINT = /user|e-?mail|login|account|ident|phone|mobile|handle/i;
const TEXTISH = new Set(['text', 'email', 'tel', '']);

export function isVisible(el: HTMLElement): boolean {
  if (el.hidden || el.getClientRects().length === 0) return false;
  const s = getComputedStyle(el);
  return s.visibility !== 'hidden' && s.display !== 'none';
}

export function isPasswordField(el: unknown): el is HTMLInputElement {
  return el instanceof HTMLInputElement && el.type === 'password' && !el.disabled;
}

export function isUsernameLike(el: unknown): el is HTMLInputElement {
  if (!(el instanceof HTMLInputElement) || el.disabled || !TEXTISH.has(el.type.toLowerCase())) return false;
  const ac = el.autocomplete.toLowerCase();
  if (ac.includes('username') || ac.includes('email')) return true;
  if (el.type === 'email') return true;
  const hints = [el.name, el.id, el.placeholder, el.getAttribute('aria-label') ?? ''].join(' ');
  return USERNAME_HINT.test(hints);
}

/** The element that groups a login: its <form>, or the nearest ancestor that contains a password field. */
export function scopeOf(el: Element): Element {
  const form = el.closest('form');
  if (form) return form;
  let node: Element | null = el;
  for (let i = 0; node && i < 8; i++, node = node.parentElement) {
    if (node.querySelector('input[type="password"]')) return node;
  }
  return el.ownerDocument.body;
}

export interface LoginFields {
  username?: HTMLInputElement;
  passwords: HTMLInputElement[];
}

export function findFields(scope: Element): LoginFields {
  const inputs = [...scope.querySelectorAll('input')];
  const passwords = inputs.filter((i): i is HTMLInputElement => isPasswordField(i) && isVisible(i));
  const firstPw = passwords[0] ? inputs.indexOf(passwords[0]) : inputs.length;
  // The username is normally the last visible text-ish field before the first password field.
  const before = inputs.slice(0, firstPw).filter((i) => TEXTISH.has(i.type.toLowerCase()) && !i.disabled && isVisible(i));
  const username =
    [...before].reverse().find(isUsernameLike) ??
    before.at(-1) ??
    // Multi-step logins (Google, Microsoft) often keep the username in a hidden autocomplete=username field.
    inputs.find((i) => i.autocomplete.toLowerCase().includes('username') && i.value);
  return { ...(username && { username }), passwords };
}

/** What the user just submitted, or null if there's no filled password in scope. */
export function extractCredentials(scope: Element): { username: string; password: string } | null {
  const { username, passwords } = findFields(scope);
  const filled = passwords.filter((p) => p.value);
  if (!filled.length) return null;
  // Change-password and sign-up forms have 2–3 password fields; the last one holds the new password.
  const newPw = filled.find((p) => p.autocomplete === 'new-password');
  const password = (filled.length > 1 ? (newPw ?? filled.at(-1)!) : filled[0]!).value;
  return { username: username?.value.trim() ?? '', password };
}

/** Set a value the way frameworks (React, Vue) notice: native setter + input/change events. */
export function setValue(el: HTMLInputElement, value: string) {
  const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set;
  el.focus();
  if (setter) setter.call(el, value);
  else el.value = value;
  el.dispatchEvent(new Event('input', { bubbles: true }));
  el.dispatchEvent(new Event('change', { bubbles: true }));
}

export function fillLogin(scope: Element, username: string, password: string): boolean {
  const { username: u, passwords } = findFields(scope);
  if (!passwords.length && !u) return false;
  if (u && username) setValue(u, username);
  if (passwords[0]) setValue(passwords[0], password);
  return true;
}
