/**
 * @omnisync/crypto — the only place OmniSync touches key material.
 *
 * Key hierarchy
 * -------------
 *   master password ──Argon2id(salt = H(username))──▶ masterKey (32B, never stored)
 *     ├─ KDF(ctx "os-auth_", 1) ─▶ authKey  → sent to the server as a login credential
 *     └─ KDF(ctx "os-kek__", 2) ─▶ kek      → wraps/unwraps the vault key, never leaves the client
 *   vaultKey (32B random, generated once per account)
 *     └─ encrypts every record with XChaCha20-Poly1305; stored on the server only wrapped by kek
 *
 * The server learns authKey (a one-way function of the password, useless for
 * decryption because kek is an independent KDF output) and ciphertext. Nothing else.
 *
 * Envelope format: "v1." + base64url(nonce[24] || ciphertext+tag)
 * Record AAD binds ciphertext to (collection, id, modifiedAt, deviceId), so the
 * server cannot swap blobs between records or rewrite their LWW metadata.
 */
import sodium from 'libsodium-wrappers-sumo';

export const ENVELOPE_VERSION = 'v1';
const PAD_BLOCK = 128;
const KEY_BYTES = 32;

export interface KdfParams {
  alg: 'argon2id13';
  opsLimit: number;
  memLimitBytes: number;
}

/** Production KDF parameters. 64 MiB / t=3 exceeds OWASP's Argon2id minimum and stays usable on phones. */
export const KDF_PARAMS_V1: KdfParams = Object.freeze({
  alg: 'argon2id13',
  opsLimit: 3,
  memLimitBytes: 64 * 1024 * 1024,
});

/** Floor the client will accept from anywhere (e.g. a server-supplied prelogin response). */
const MIN_OPS = 2;
const MIN_MEM = 19 * 1024 * 1024;

export interface MasterKeys {
  /** Login credential sent to the server. */
  authKey: Uint8Array;
  /** Key-encryption key. Wraps the vault key. Never leaves the device. */
  kek: Uint8Array;
}

export class CryptoError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'CryptoError';
  }
}

let readyPromise: Promise<void> | null = null;
/** Must be awaited once before any other call. Safe to call repeatedly. */
export function ready(): Promise<void> {
  readyPromise ??= sodium.ready;
  return readyPromise;
}

const enc = new TextEncoder();
const dec = new TextDecoder('utf-8', { fatal: true });

export function normalizeUsername(username: string): string {
  const u = username.normalize('NFKC').trim().toLowerCase();
  if (u.length < 3 || u.length > 254) throw new CryptoError('username must be 3-254 characters');
  return u;
}

/** Deterministic per-user Argon2id salt so every device derives the same keys without a server round trip. */
export function deriveSalt(username: string): Uint8Array {
  return sodium.crypto_generichash(
    sodium.crypto_pwhash_SALTBYTES,
    enc.encode(`omnisync/v1/salt\0${normalizeUsername(username)}`),
    null,
  );
}

/**
 * Opaque account identifier sent to the server instead of the username,
 * so the server never learns the user's email/handle.
 */
export function deriveLoginId(username: string): string {
  const h = sodium.crypto_generichash(32, enc.encode(`omnisync/v1/login\0${normalizeUsername(username)}`), null);
  return sodium.to_base64(h, sodium.base64_variants.URLSAFE_NO_PADDING);
}

export function assertKdfParams(p: KdfParams): void {
  if (p.alg !== 'argon2id13') throw new CryptoError('unsupported KDF');
  if (!Number.isInteger(p.opsLimit) || p.opsLimit < MIN_OPS) throw new CryptoError('KDF opsLimit too low');
  if (!Number.isInteger(p.memLimitBytes) || p.memLimitBytes < MIN_MEM) throw new CryptoError('KDF memLimit too low');
}

/**
 * Argon2id(password, H(username)) → masterKey → {authKey, kek}.
 * `params` is overridable only so tests can run fast; callers in production pass KDF_PARAMS_V1.
 */
export function deriveMasterKeys(
  password: string,
  username: string,
  params: KdfParams = KDF_PARAMS_V1,
  { unsafeAllowWeakParams = false } = {},
): MasterKeys {
  if (!unsafeAllowWeakParams) assertKdfParams(params);
  if (password.length < 8) throw new CryptoError('master password must be at least 8 characters');
  const pw = enc.encode(password.normalize('NFKC'));
  const masterKey = sodium.crypto_pwhash(
    KEY_BYTES,
    pw,
    deriveSalt(username),
    params.opsLimit,
    params.memLimitBytes,
    sodium.crypto_pwhash_ALG_ARGON2ID13,
  );
  try {
    return {
      authKey: sodium.crypto_kdf_derive_from_key(KEY_BYTES, 1, 'os-auth_', masterKey),
      kek: sodium.crypto_kdf_derive_from_key(KEY_BYTES, 2, 'os-kek__', masterKey),
    };
  } finally {
    sodium.memzero(masterKey);
    sodium.memzero(pw);
  }
}

export function generateVaultKey(): Uint8Array {
  return sodium.crypto_aead_xchacha20poly1305_ietf_keygen();
}

export function generateId(): string {
  return sodium.to_base64(sodium.randombytes_buf(16), sodium.base64_variants.URLSAFE_NO_PADDING);
}

export function wipe(key: Uint8Array): void {
  sodium.memzero(key);
}

// ---------------------------------------------------------------------------
// Envelopes

function seal(key: Uint8Array, plaintext: Uint8Array, aad: Uint8Array): string {
  if (key.length !== KEY_BYTES) throw new CryptoError('bad key length');
  const nonce = sodium.randombytes_buf(sodium.crypto_aead_xchacha20poly1305_ietf_NPUBBYTES);
  const padded = sodium.pad(plaintext, PAD_BLOCK);
  const ct = sodium.crypto_aead_xchacha20poly1305_ietf_encrypt(padded, aad, null, nonce, key);
  sodium.memzero(padded);
  const out = new Uint8Array(nonce.length + ct.length);
  out.set(nonce, 0);
  out.set(ct, nonce.length);
  return `${ENVELOPE_VERSION}.${sodium.to_base64(out, sodium.base64_variants.URLSAFE_NO_PADDING)}`;
}

function open(key: Uint8Array, envelope: string, aad: Uint8Array): Uint8Array {
  if (key.length !== KEY_BYTES) throw new CryptoError('bad key length');
  const dot = envelope.indexOf('.');
  if (dot < 0 || envelope.slice(0, dot) !== ENVELOPE_VERSION) throw new CryptoError('unsupported envelope version');
  let raw: Uint8Array;
  try {
    raw = sodium.from_base64(envelope.slice(dot + 1), sodium.base64_variants.URLSAFE_NO_PADDING);
  } catch {
    throw new CryptoError('malformed envelope');
  }
  const nb = sodium.crypto_aead_xchacha20poly1305_ietf_NPUBBYTES;
  if (raw.length < nb + sodium.crypto_aead_xchacha20poly1305_ietf_ABYTES) throw new CryptoError('malformed envelope');
  let padded: Uint8Array;
  try {
    padded = sodium.crypto_aead_xchacha20poly1305_ietf_decrypt(null, raw.subarray(nb), aad, raw.subarray(0, nb), key);
  } catch {
    // Wrong key, tampered ciphertext, or AAD mismatch — deliberately indistinguishable.
    throw new CryptoError('decryption failed');
  }
  try {
    return sodium.unpad(padded, PAD_BLOCK);
  } catch {
    throw new CryptoError('bad padding');
  }
}

const VAULT_KEY_AAD = enc.encode('omnisync/v1/vault-key');

export function wrapVaultKey(vaultKey: Uint8Array, kek: Uint8Array): string {
  if (vaultKey.length !== KEY_BYTES) throw new CryptoError('bad vault key length');
  return seal(kek, vaultKey, VAULT_KEY_AAD);
}

export function unwrapVaultKey(wrapped: string, kek: Uint8Array): Uint8Array {
  const k = open(kek, wrapped, VAULT_KEY_AAD);
  if (k.length !== KEY_BYTES) throw new CryptoError('bad vault key length');
  return k;
}

export interface RecordMeta {
  collection: string;
  id: string;
  /** Client wall-clock ms; part of LWW ordering and authenticated via AAD. */
  modifiedAt: number;
  deviceId: string;
}

function recordAad(m: RecordMeta): Uint8Array {
  if (!Number.isSafeInteger(m.modifiedAt) || m.modifiedAt < 0) throw new CryptoError('bad modifiedAt');
  for (const s of [m.collection, m.id, m.deviceId]) {
    if (typeof s !== 'string' || s.length === 0 || s.includes('\0')) throw new CryptoError('bad record metadata');
  }
  return enc.encode(`omnisync/v1/record\0${m.collection}\0${m.id}\0${m.modifiedAt}\0${m.deviceId}`);
}

export function encryptRecord(vaultKey: Uint8Array, meta: RecordMeta, payload: unknown): string {
  const pt = enc.encode(JSON.stringify(payload));
  try {
    return seal(vaultKey, pt, recordAad(meta));
  } finally {
    sodium.memzero(pt);
  }
}

export function decryptRecord<T = unknown>(vaultKey: Uint8Array, meta: RecordMeta, envelope: string): T {
  const pt = open(vaultKey, envelope, recordAad(meta));
  try {
    return JSON.parse(dec.decode(pt)) as T;
  } catch {
    throw new CryptoError('record payload is not valid JSON');
  } finally {
    sodium.memzero(pt);
  }
}

// ---------------------------------------------------------------------------
// Encoding helpers (keys cross the popup ↔ background boundary and storage as base64)

export function toB64(bytes: Uint8Array): string {
  return sodium.to_base64(bytes, sodium.base64_variants.URLSAFE_NO_PADDING);
}

export function fromB64(s: string): Uint8Array {
  return sodium.from_base64(s, sodium.base64_variants.URLSAFE_NO_PADDING);
}

/** Keyed fingerprint of local state (change detection), so local sync metadata never stores record contents. */
export function fingerprint(key: Uint8Array, value: unknown): string {
  const sub = sodium.crypto_kdf_derive_from_key(32, 3, 'os-fprnt', key);
  const h = sodium.crypto_generichash(16, enc.encode(JSON.stringify(value)), sub);
  sodium.memzero(sub);
  return toB64(h);
}
