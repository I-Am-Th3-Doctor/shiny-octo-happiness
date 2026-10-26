import { describe, expect, it } from 'vitest';
import { parseCsv, parsePasswordCsv } from '../src/passwords/csv.ts';
import { normalizeOrigin, originMatches } from '../src/passwords/model.ts';

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
