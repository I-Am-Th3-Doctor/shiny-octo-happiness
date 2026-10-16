import { beforeAll, describe, expect, it } from 'vitest';
import {
  CryptoError,
  KDF_PARAMS_V1,
  type KdfParams,
  type RecordMeta,
  decryptRecord,
  deriveLoginId,
  deriveMasterKeys,
  encryptRecord,
  fingerprint,
  fromB64,
  generateId,
  generateVaultKey,
  ready,
  toB64,
  unwrapVaultKey,
  wrapVaultKey,
} from '../src/index.ts';

// Fast params for the bulk of the suite; production params are exercised in the KAT.
const FAST: KdfParams = { alg: 'argon2id13', opsLimit: 1, memLimitBytes: 8192 };
const fast = (pw: string, user: string) => deriveMasterKeys(pw, user, FAST, { unsafeAllowWeakParams: true });

const meta: RecordMeta = { collection: 'bookmarks', id: 'rec-1', modifiedAt: 1_700_000_000_000, deviceId: 'dev-a' };

beforeAll(async () => {
  await ready();
});

describe('key derivation', () => {
  it('matches a known-answer vector cross-checked against argon2-cffi + hashlib.blake2b', () => {
    // If this ever changes, every existing account becomes undecryptable. Do not "fix" the vector.
    const k = deriveMasterKeys('correct horse battery staple', '  Alice@Example.com ', KDF_PARAMS_V1);
    expect(toB64(k.authKey)).toBe('Uh52zbgWqWc9PLLzUs5U8U4DLcy3eLyyiQ8P6RZunK0');
    expect(toB64(k.kek)).toBe('tbZAnUlKRlYdRiJXVbY05Voz1W5bV-b913ZIcdbPoV8');
    expect(deriveLoginId('alice@example.com')).toBe('ULnocqyhoi0DEFQqIFENozmq5LrhjZ_De0Jm-ibg_b8');
  });

  it('is deterministic and normalizes the username', () => {
    const a = fast('hunter2hunter2', 'Bob@x.io');
    const b = fast('hunter2hunter2', ' bob@X.IO');
    expect(toB64(a.kek)).toBe(toB64(b.kek));
    expect(toB64(a.authKey)).toBe(toB64(b.authKey));
  });

  it('separates authKey from kek (server credential cannot decrypt)', () => {
    const k = fast('hunter2hunter2', 'bob@x.io');
    expect(toB64(k.authKey)).not.toBe(toB64(k.kek));
    expect(k.authKey).toHaveLength(32);
    expect(k.kek).toHaveLength(32);
  });

  it('salts per user and depends on the password', () => {
    const base = toB64(fast('hunter2hunter2', 'bob@x.io').kek);
    expect(toB64(fast('hunter2hunter2', 'carol@x.io').kek)).not.toBe(base);
    expect(toB64(fast('hunter2hunter3', 'bob@x.io').kek)).not.toBe(base);
  });

  it('refuses weak KDF params unless explicitly allowed', () => {
    expect(() => deriveMasterKeys('hunter2hunter2', 'bob@x.io', FAST)).toThrow(CryptoError);
    expect(() =>
      deriveMasterKeys('hunter2hunter2', 'bob@x.io', { ...KDF_PARAMS_V1, alg: 'scrypt' as 'argon2id13' }),
    ).toThrow(/unsupported KDF/);
  });

  it('rejects short passwords and usernames', () => {
    expect(() => fast('short', 'bob@x.io')).toThrow(/at least 8/);
    expect(() => fast('hunter2hunter2', 'ab')).toThrow(/username/);
  });

  it('login id does not contain the username', () => {
    const id = deriveLoginId('alice@example.com');
    expect(id).not.toContain('alice');
    expect(fromB64(id)).toHaveLength(32);
  });
});

describe('vault key wrapping', () => {
  it('round-trips and fails with the wrong kek', () => {
    const vk = generateVaultKey();
    const { kek } = fast('hunter2hunter2', 'bob@x.io');
    const wrapped = wrapVaultKey(vk, kek);
    expect(toB64(unwrapVaultKey(wrapped, kek))).toBe(toB64(vk));
    const other = fast('wrong-password', 'bob@x.io').kek;
    expect(() => unwrapVaultKey(wrapped, other)).toThrow('decryption failed');
  });

  it('cannot be confused with a record envelope (domain-separated AAD)', () => {
    const k = generateVaultKey();
    const wrapped = wrapVaultKey(generateVaultKey(), k);
    expect(() => decryptRecord(k, meta, wrapped)).toThrow('decryption failed');
  });
});

describe('record encryption', () => {
  const payload = { kind: 'bookmark', title: 'Secret plans', url: 'https://example.com/very/secret' };

  it('round-trips', () => {
    const k = generateVaultKey();
    const env = encryptRecord(k, meta, payload);
    expect(env.startsWith('v1.')).toBe(true);
    expect(decryptRecord(k, meta, env)).toEqual(payload);
  });

  it('never contains plaintext', () => {
    const env = encryptRecord(generateVaultKey(), meta, payload);
    const raw = new TextDecoder('latin1').decode(fromB64(env.slice(3)));
    expect(env).not.toContain('example.com');
    expect(raw).not.toContain('example.com');
    expect(raw).not.toContain('Secret');
  });

  it('uses a fresh nonce every time', () => {
    const k = generateVaultKey();
    const seen = new Set<string>();
    for (let i = 0; i < 200; i++) seen.add(encryptRecord(k, meta, payload).slice(3, 35));
    expect(seen.size).toBe(200);
  });

  it('fails with the wrong key', () => {
    const env = encryptRecord(generateVaultKey(), meta, payload);
    expect(() => decryptRecord(generateVaultKey(), meta, env)).toThrow('decryption failed');
  });

  it('detects any single-byte tampering', () => {
    const k = generateVaultKey();
    const raw = fromB64(encryptRecord(k, meta, payload).slice(3));
    for (const pos of [0, 23, 24, raw.length - 17, raw.length - 1]) {
      const t = raw.slice();
      t[pos]! ^= 0x01;
      expect(() => decryptRecord(k, meta, `v1.${toB64(t)}`)).toThrow('decryption failed');
    }
  });

  it.each([
    ['collection', { ...meta, collection: 'passwords' }],
    ['id (blob swapped onto another record)', { ...meta, id: 'rec-2' }],
    ['modifiedAt (server rewrote LWW timestamp)', { ...meta, modifiedAt: meta.modifiedAt + 1 }],
    ['deviceId (server rewrote tiebreak)', { ...meta, deviceId: 'dev-b' }],
  ])('binds metadata via AAD: %s', (_name, forged) => {
    const k = generateVaultKey();
    const env = encryptRecord(k, meta, payload);
    expect(() => decryptRecord(k, forged, env)).toThrow('decryption failed');
  });

  it('rejects unknown envelope versions and garbage', () => {
    const k = generateVaultKey();
    const env = encryptRecord(k, meta, payload);
    expect(() => decryptRecord(k, meta, `v2.${env.slice(3)}`)).toThrow('unsupported envelope version');
    expect(() => decryptRecord(k, meta, 'v1.!!!')).toThrow(CryptoError);
    expect(() => decryptRecord(k, meta, 'v1.AAAA')).toThrow('malformed envelope');
  });

  it('pads to hide exact payload length', () => {
    const k = generateVaultKey();
    const a = encryptRecord(k, meta, { u: 'a' });
    const b = encryptRecord(k, meta, { u: 'a'.repeat(100) });
    expect(a.length).toBe(b.length);
  });

  it('rejects metadata that could make AAD ambiguous', () => {
    const k = generateVaultKey();
    expect(() => encryptRecord(k, { ...meta, id: 'a\0b' }, payload)).toThrow(CryptoError);
    expect(() => encryptRecord(k, { ...meta, modifiedAt: 1.5 }, payload)).toThrow(CryptoError);
  });
});

describe('helpers', () => {
  it('generateId is unique and url-safe', () => {
    const ids = new Set(Array.from({ length: 500 }, generateId));
    expect(ids.size).toBe(500);
    for (const id of ids) expect(id).toMatch(/^[A-Za-z0-9_-]{22}$/);
  });

  it('fingerprint is keyed and stable', () => {
    const k1 = generateVaultKey();
    const k2 = generateVaultKey();
    expect(fingerprint(k1, { a: 1 })).toBe(fingerprint(k1, { a: 1 }));
    expect(fingerprint(k1, { a: 1 })).not.toBe(fingerprint(k1, { a: 2 }));
    expect(fingerprint(k1, { a: 1 })).not.toBe(fingerprint(k2, { a: 1 }));
  });
});
