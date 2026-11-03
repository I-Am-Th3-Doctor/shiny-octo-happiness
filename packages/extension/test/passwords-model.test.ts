import { describe, expect, it } from 'vitest';
import { parseCsv, parsePasswordCsv } from '../src/passwords/csv.ts';
import { loginMatchesPage, normalizeOrigin, originMatches, registrableDomain, registrableDomains, siteMatch } from '../src/passwords/model.ts';

describe('normalizeOrigin', () => {
  it.each([
    ['https://Example.com/login?next=/', 'https://example.com'],
    ['https://example.com:443/', 'https://example.com'],
    ['http://localhost:8080/x', 'http://localhost:8080'],
    ['example.com', 'https://example.com'],
    ['android://hash@com.example.app/', null],
    ['chrome://settings', null],
    ['', null],
  ])('%s → %s', (input, expected) => expect(normalizeOrigin(input)).toBe(expected));
});

describe('originMatches (what is allowed to autofill where)', () => {
  it.each([
    ['https://example.com', 'https://example.com/login', true],
    ['https://example.com', 'https://www.example.com/', true],
    ['https://www.example.com', 'https://example.com/', true],
    ['http://example.com', 'https://example.com/', true], // upgraded site
    ['https://example.com', 'http://example.com/', false], // never downgrade to cleartext
    ['https://accounts.example.com', 'https://example.com/', false],
    ['https://example.com', 'https://evil.example.com/', false],
    ['https://example.com', 'https://example.com.evil.net/', false],
    ['https://example.com', 'https://example.com:8443/', false],
    ['https://example.com', 'not a url', false],
  ])('%s on %s → %s', (saved, page, ok) => expect(originMatches(saved, page)).toBe(ok));
});

describe('registrableDomain (eTLD+1)', () => {
  it.each([
    ['https://example.com', 'example.com'],
    ['https://www.example.com/login?x=1', 'example.com'],
    ['login.accounts.example.com', 'example.com'],
    ['http://example.com:8080', 'example.com'],
    ['EXAMPLE.COM.', 'example.com'],
    ['shop.example.co.uk', 'example.co.uk'],
    ['example.co.uk', 'example.co.uk'],
    ['a.b.example.com.au', 'example.com.au'],
    ['www.example.co.jp', 'example.co.jp'],
    ['alice.github.io', 'alice.github.io'], // shared hosting: every subdomain is its own owner
    ['co.uk', null], // a bare public suffix is nobody's domain
    ['github.io', null],
    ['127.0.0.1', '127.0.0.1'], // IPs are never grouped
    ['http://10.0.0.1:8080/x', '10.0.0.1'],
    ['localhost', 'localhost'],
    ['http://[::1]:8787/', '[::1]'],
    ['', null],
    ['not a url at all', null],
  ])('%s → %s', (input, expected) => expect(registrableDomain(input)).toBe(expected));

  it('returns the domain for both the stored site and the tab hostname', () => {
    expect(registrableDomains('https://www.example.com', 'login.example.com')).toEqual({ saved: 'example.com', page: 'example.com' });
  });
});

describe('which saved logins belong on a page (siteMatch)', () => {
  it.each([
    // 1. saved https://example.com is listed on www., a login. subdomain, and http
    ['https://example.com', 'https://www.example.com/', 'exact'],
    ['https://example.com', 'https://login.example.com/signin', 'domain'],
    ['https://example.com', 'http://example.com/', 'domain'],
    // 2. saved www. is listed on the apex
    ['https://www.example.com', 'https://example.com/', 'exact'],
    // exact origin still wins over domain
    ['https://example.com', 'https://example.com/login', 'exact'],
    ['http://example.com', 'https://example.com/', 'exact'],
    // bare hostnames and stored values in other shapes work too
    ['example.com', 'https://accounts.example.com/', 'domain'],
    ['https://shop.example.co.uk', 'https://www.example.co.uk/', 'domain'],
  ])('%s on %s → %s', (saved, page, expected) => expect(siteMatch(saved, page)).toBe(expected));

  it.each([
    // 3. never across registrable domains
    ['example.com', 'https://notexample.com/'],
    ['example.com', 'https://example.com.evil.com/'],
    ['example.com', 'https://evil.com/'],
    ['https://example.com', 'https://www.example.com.evil.com/login'],
    ['https://example.com', 'https://example.co/'],
    ['https://example.com', 'https://example.org/'],
    ['https://notexample.com', 'https://example.com/'],
    // public suffixes are not a shared domain
    ['https://shop.example.co.uk', 'https://other.co.uk/'],
    ['https://alice.github.io', 'https://bob.github.io/'],
    ['https://example.com.au', 'https://evil.com.au/'],
    // IPs and localhost only ever match themselves
    ['http://127.0.0.1:8080', 'http://127.0.0.2:8080/'],
    ['http://10.0.0.1', 'http://0.0.1/'],
    ['http://localhost:3000', 'http://127.0.0.1:3000/'],
    // only http(s) pages, and garbage never matches
    ['https://example.com', 'ftp://example.com/'],
    ['https://example.com', 'not a url'],
    ['', 'https://example.com/'],
    ['co.uk', 'https://example.co.uk/'],
  ])('%s is NOT listed on %s', (saved, page) => {
    expect(siteMatch(saved, page)).toBeNull();
    expect(loginMatchesPage(saved, page)).toBe(false);
  });
});

describe('parseCsv', () => {
  it('handles quotes, escaped quotes, embedded commas/newlines, CRLF and BOM', () => {
    const text = '﻿a,b,c\r\n"x, y","say ""hi""","line1\nline2"\r\n,,\r\nlast,row,here';
    expect(parseCsv(text)).toEqual([
      ['a', 'b', 'c'],
      ['x, y', 'say "hi"', 'line1\nline2'],
      ['last', 'row', 'here'],
    ]);
  });
  it('rejects an unterminated quote', () => {
    expect(() => parseCsv('a,"b\n')).toThrow(/quoted/);
  });
});

describe('parsePasswordCsv', () => {
  it('reads a Brave/Chrome export', () => {
    const csv = [
      'name,url,username,password,note',
      'github.com,https://github.com/session,octo,"p@ss,word",',
      'Bank,https://bank.example/login,me@x.io,s3cret,"PIN is ""1234"""',
      'Some App,android://abc@com.example.app/,u,p,',
      'empty,https://empty.example,u,,',
    ].join('\n');
    const r = parsePasswordCsv(csv);
    expect(r.format).toBe('chromium');
    expect(r.logins).toEqual([
      { origin: 'https://github.com', username: 'octo', password: 'p@ss,word', title: 'github.com' },
      { origin: 'https://bank.example', username: 'me@x.io', password: 's3cret', title: 'Bank', notes: 'PIN is "1234"' },
    ]);
    expect(r.skipped).toEqual([
      { row: 4, reason: 'not a website (e.g. an Android app entry)' },
      { row: 5, reason: 'empty password' },
    ]);
  });

  it('reads a Zen/Firefox export', () => {
    const csv = [
      '"url","username","password","httpRealm","formActionOrigin","guid","timeCreated","timeLastUsed","timePasswordChanged"',
      '"https://www.mozilla.org","fox","tail","","https://www.mozilla.org","{1}","1700000000000","1700000000000","1700000000000"',
      '"https://router.local","admin","admin","Router Login","","{2}","1","1","1"',
    ].join('\r\n');
    const r = parsePasswordCsv(csv);
    expect(r.format).toBe('firefox');
    expect(r.logins).toEqual([
      { origin: 'https://www.mozilla.org', username: 'fox', password: 'tail' },
      { origin: 'https://router.local', username: 'admin', password: 'admin' },
    ]);
  });

  it('refuses a CSV that is not a password export', () => {
    expect(() => parsePasswordCsv('title,url\nx,https://x.com')).toThrow(/password/);
  });
});
