/**
 * Import from each browser's native password export. Extensions cannot read the browser's own
 * password store, so the onboarding path is: export CSV from the browser → import here → delete the CSV.
 *
 *   Brave / Chrome:  brave://password-manager/settings → Export passwords
 *                    columns: name,url,username,password,note
 *   Zen / Firefox:   about:logins → ⋯ → Export Passwords
 *                    columns: url,username,password,httpRealm,formActionOrigin,guid,timeCreated,timeLastUsed,timePasswordChanged
 */
import { normalizeOrigin } from './model.ts';

/** RFC 4180 CSV: quoted fields, "" escapes, commas and newlines inside quotes, CRLF or LF, optional BOM. */
export function parseCsv(text: string): string[][] {
  const rows: string[][] = [];
  let row: string[] = [];
  let field = '';
  let quoted = false;
  let i = text.charCodeAt(0) === 0xfeff ? 1 : 0;
  for (; i < text.length; i++) {
    const c = text[i]!;
    if (quoted) {
      if (c === '"') {
        if (text[i + 1] === '"') {
          field += '"';
          i++;
        } else quoted = false;
      } else field += c;
    } else if (c === '"' && field === '') quoted = true;
    else if (c === ',') {
      row.push(field);
      field = '';
    } else if (c === '\n' || c === '\r') {
      if (c === '\r' && text[i + 1] === '\n') i++;
      row.push(field);
      rows.push(row);
      row = [];
      field = '';
    } else field += c;
  }
  if (quoted) throw new Error('CSV ends inside a quoted field');
  if (field !== '' || row.length) {
    row.push(field);
    rows.push(row);
  }
  return rows.filter((r) => r.some((f) => f !== ''));
}

export interface ImportedLogin {
  origin: string;
  username: string;
  password: string;
  title?: string;
  notes?: string;
}

export interface CsvImportResult {
  format: 'chromium' | 'firefox' | 'generic';
  logins: ImportedLogin[];
  skipped: { row: number; reason: string }[];
}

const COLUMN_ALIASES: Record<keyof ImportedLogin | 'url', string[]> = {
  url: ['url', 'origin', 'website', 'login_uri', 'uri'],
  origin: [],
  username: ['username', 'login_username', 'user', 'login', 'email'],
  password: ['password', 'login_password'],
  title: ['name', 'title'],
  notes: ['note', 'notes'],
};

export function parsePasswordCsv(text: string): CsvImportResult {
  const rows = parseCsv(text);
  const header = (rows.shift() ?? []).map((h) => h.trim().toLowerCase());
  const col = (k: keyof typeof COLUMN_ALIASES) => header.findIndex((h) => COLUMN_ALIASES[k].includes(h));
  const [iUrl, iUser, iPass, iTitle, iNotes] = [col('url'), col('username'), col('password'), col('title'), col('notes')];
  if (iUrl < 0 || iPass < 0) throw new Error('Not a password export: needs "url" and "password" columns');
  const format = header.includes('formactionorigin') || header.includes('httprealm') ? 'firefox' : header[0] === 'name' ? 'chromium' : 'generic';

  const logins: ImportedLogin[] = [];
  const skipped: CsvImportResult['skipped'] = [];
  rows.forEach((r, n) => {
    const rowNo = n + 2; // 1-based, after the header
    const origin = normalizeOrigin(r[iUrl] ?? '');
    const password = r[iPass] ?? '';
    if (!origin) return skipped.push({ row: rowNo, reason: 'not a website (e.g. an Android app entry)' });
    if (!password) return skipped.push({ row: rowNo, reason: 'empty password' });
    logins.push({
      origin,
      username: iUser >= 0 ? (r[iUser] ?? '') : '',
      password,
      ...(iTitle >= 0 && r[iTitle] && { title: r[iTitle] }),
      ...(iNotes >= 0 && r[iNotes] && { notes: r[iNotes] }),
    });
  });
  return { format, logins, skipped };
}
