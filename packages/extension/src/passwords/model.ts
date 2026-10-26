/** Password vault data model and site matching. Pure functions, no browser APIs. */

export interface Login {
  /** Normalized origin, e.g. "https://accounts.example.com" (scheme + host + port, no path). */
  origin: string;
  username: string;
  password: string;
  title?: string;
  notes?: string;
  createdAt: number;
}

export type PwPayload = ({ deleted?: false } & Login) | { deleted: true };

export interface LoginEntry extends Login {
  id: string;
  modifiedAt: number;
}

/** "https://Example.com:443/login?x" → "https://example.com". Only http(s) sites can hold passwords. */
export function normalizeOrigin(url: string): string | null {
  let u: URL;
  try {
    u = new URL(url.trim());
  } catch {
    try {
      u = new URL(`https://${url.trim()}`); // bare hostnames in hand-made CSVs
    } catch {
      return null;
    }
  }
  if (u.protocol !== 'https:' && u.protocol !== 'http:') return null;
  if (!u.hostname) return null;
  return `${u.protocol}//${u.host}`.toLowerCase();
}

const stripWww = (host: string) => host.replace(/^www\./, '');

/**
 * Does a saved login belong on this page? Deliberately strict, because a lax rule is how
 * password managers leak credentials:
 *  - the host must be identical (a "www." prefix is ignored, nothing else is);
 *    accounts.example.com does NOT fill on evil.example.com or on example.com.
 *  - an https login never fills on an http page (that would send it in the clear);
 *    an http login may fill on the https version of the same site.
 */
export function originMatches(saved: string, page: string): boolean {
  let a: URL;
  let b: URL;
  try {
    a = new URL(saved);
    b = new URL(page);
  } catch {
    return false;
  }
  if (stripWww(a.host) !== stripWww(b.host)) return false;
  if (a.protocol === b.protocol) return true;
  return a.protocol === 'http:' && b.protocol === 'https:';
}

/** Two logins are "the same account" if they share site and username; the vault keeps one. */
export const loginKey = (l: Pick<Login, 'origin' | 'username'>) => `${l.origin}\n${l.username}`;
