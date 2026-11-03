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

// ---------------------------------------------------------------------------
// Registrable domain (eTLD+1)

/**
 * Public suffixes made of more than one label, i.e. places where many unrelated owners register
 * names side by side. A subset of the Public Suffix List: the common country second levels, plus
 * the big shared-hosting suffixes, where "alice.github.io" and "bob.github.io" belong to different
 * people and must never share logins. Anything not listed is treated as a one-label suffix
 * (".com", ".org", ".de" …), so the registrable domain is the last two labels.
 */
const MULTI_LABEL_SUFFIXES = new Set([
  // United Kingdom
  'co.uk', 'org.uk', 'me.uk', 'ltd.uk', 'plc.uk', 'net.uk', 'ac.uk', 'gov.uk', 'nhs.uk', 'police.uk', 'sch.uk',
  // Australia / New Zealand
  'com.au', 'net.au', 'org.au', 'edu.au', 'gov.au', 'asn.au', 'id.au',
  'co.nz', 'net.nz', 'org.nz', 'ac.nz', 'govt.nz', 'geek.nz', 'school.nz',
  // Japan / Korea / China / Taiwan / Hong Kong
  'co.jp', 'ne.jp', 'or.jp', 'ac.jp', 'go.jp', 'ad.jp', 'ed.jp', 'gr.jp', 'lg.jp',
  'co.kr', 'or.kr', 'ne.kr', 'ac.kr', 'go.kr', 're.kr',
  'com.cn', 'net.cn', 'org.cn', 'gov.cn', 'edu.cn',
  'com.tw', 'net.tw', 'org.tw', 'edu.tw', 'gov.tw', 'idv.tw',
  'com.hk', 'net.hk', 'org.hk', 'edu.hk', 'gov.hk', 'idv.hk',
  // Rest of Asia
  'co.in', 'net.in', 'org.in', 'firm.in', 'gen.in', 'ind.in', 'ac.in', 'edu.in', 'gov.in', 'res.in',
  'com.sg', 'net.sg', 'org.sg', 'edu.sg', 'gov.sg',
  'com.my', 'net.my', 'org.my', 'edu.my', 'gov.my',
  'co.id', 'or.id', 'ac.id', 'go.id', 'web.id',
  'co.th', 'in.th', 'ac.th', 'go.th', 'or.th',
  'com.ph', 'net.ph', 'org.ph', 'edu.ph', 'gov.ph',
  'com.vn', 'net.vn', 'org.vn', 'edu.vn', 'gov.vn',
  'com.pk', 'net.pk', 'org.pk', 'edu.pk', 'gov.pk',
  'com.bd', 'com.np', 'com.lk',
  'co.il', 'org.il', 'net.il', 'ac.il', 'gov.il',
  'com.tr', 'net.tr', 'org.tr', 'edu.tr', 'gov.tr',
  'com.sa', 'net.sa', 'org.sa', 'com.eg', 'com.qa', 'com.kw', 'com.om', 'com.bh',
  // Americas
  'com.br', 'net.br', 'org.br', 'gov.br', 'edu.br',
  'com.mx', 'net.mx', 'org.mx', 'gob.mx', 'edu.mx',
  'com.ar', 'net.ar', 'org.ar', 'gob.ar', 'edu.ar',
  'com.co', 'net.co', 'org.co', 'gov.co', 'edu.co',
  'com.pe', 'com.ve', 'com.uy', 'com.ec', 'com.bo', 'com.py', 'com.gt', 'com.do', 'com.pr',
  'co.cr',
  // Africa / Europe
  'co.za', 'org.za', 'net.za', 'gov.za', 'ac.za', 'web.za',
  'co.ke', 'or.ke', 'ac.ke', 'com.ng', 'org.ng', 'co.tz', 'co.ug', 'com.gh',
  'com.ua', 'net.ua', 'org.ua', 'com.pl', 'net.pl', 'org.pl', 'com.ru', 'com.gr', 'com.cy', 'com.mt',
  // Shared hosting: every subdomain is a different owner
  'github.io', 'gitlab.io', 'bitbucket.io', 'pages.dev', 'workers.dev', 'netlify.app', 'vercel.app',
  'herokuapp.com', 'appspot.com', 'web.app', 'firebaseapp.com', 'blogspot.com',
  'azurewebsites.net', 'cloudapp.net', 'cloudfront.net', 'fly.dev', 'onrender.com', 'glitch.me',
  'repl.co', 'replit.dev', 'ngrok.io', 'ngrok-free.app', 'readthedocs.io', 'surge.sh', 'neocities.org',
  'myshopify.com', 'wixsite.com', 'webflow.io', 'carrd.co',
]);

const IPV4 = /^\d{1,3}(\.\d{1,3}){3}$/;

/** Hostname from a stored site value: a full URL, an origin, or a bare hostname. */
function hostnameOf(site: string): string | null {
  const s = site.trim();
  if (!s) return null;
  let host: string;
  try {
    host = new URL(s.includes('://') ? s : `https://${s}`).hostname;
  } catch {
    return null;
  }
  return host.toLowerCase().replace(/\.$/, '') || null;
}

/**
 * The registrable domain ("eTLD+1") of a site, e.g. login.example.co.uk → example.co.uk.
 * IP addresses and single-label hosts (localhost) have none to group by, so they return
 * themselves and only ever equal themselves. A bare public suffix ("co.uk", "github.io")
 * returns null: it is nobody's domain, so it matches nothing.
 */
export function registrableDomain(siteOrHost: string): string | null {
  const host = hostnameOf(siteOrHost);
  if (!host) return null;
  if (IPV4.test(host) || host.startsWith('[') || host.includes(':') || !host.includes('.')) return host;
  const labels = host.split('.');
  if (labels.some((l) => l === '')) return null;
  // Longest listed multi-label suffix wins, compared label by label (never as a string suffix).
  let suffixLen = 1;
  for (let n = labels.length; n >= 2; n--) {
    if (MULTI_LABEL_SUFFIXES.has(labels.slice(-n).join('.'))) {
      suffixLen = n;
      break;
    }
  }
  if (labels.length <= suffixLen) return null;
  return labels.slice(-(suffixLen + 1)).join('.');
}

/** Registrable domains of a stored login's site and of the tab's hostname. */
export function registrableDomains(savedSite: string, tabHostname: string): { saved: string | null; page: string | null } {
  return { saved: registrableDomain(savedSite), page: registrableDomain(tabHostname) };
}

export type SiteMatch = 'exact' | 'domain';

/**
 * How a saved login relates to a page, or null if it doesn't belong there.
 *  - 'exact':  the same origin (originMatches, unchanged).
 *  - 'domain': a different host on the same registrable domain, e.g. a login saved on
 *              example.com shown on login.example.com, www.example.com or http://example.com.
 * Never matches across registrable domains: example.com never matches notexample.com,
 * example.com.evil.com or evil.com, because the comparison is on parsed domains, not strings.
 */
export function siteMatch(saved: string, pageUrl: string): SiteMatch | null {
  let page: URL;
  try {
    page = new URL(pageUrl);
  } catch {
    return null;
  }
  if (page.protocol !== 'https:' && page.protocol !== 'http:') return null;
  if (originMatches(saved, pageUrl)) return 'exact';
  const { saved: a, page: b } = registrableDomains(saved, page.hostname);
  return a !== null && a === b ? 'domain' : null;
}

export const loginMatchesPage = (saved: string, pageUrl: string) => siteMatch(saved, pageUrl) !== null;

/** Two logins are "the same account" if they share site and username; the vault keeps one. */
export const loginKey = (l: Pick<Login, 'origin' | 'username'>) => `${l.origin}\n${l.username}`;
