/** Wire protocol for the OmniSync REST API (v1). Everything the server sees is listed here. */

export const COLLECTIONS = ['bookmarks', 'passwords', 'tabs', 'history'] as const;
export type Collection = (typeof COLLECTIONS)[number];

export const LIMITS = {
  maxCiphertextBytes: 64 * 1024,
  maxPushRecords: 500,
  maxPullRecords: 1000,
  maxRecordIdLength: 64,
  /** Server rejects writes stamped further than this into the future (skewed or malicious clock). */
  maxClockSkewMs: 10 * 60 * 1000,
} as const;

/** The LWW version of a record: client timestamp, device id as tiebreak. */
export interface Version {
  modifiedAt: number;
  deviceId: string;
}

/**
 * Total order over versions: later modifiedAt wins; on an exact tie the
 * lexicographically greater deviceId wins. Returns >0 if a wins, <0 if b wins, 0 if identical.
 */
export function compareVersions(a: Version, b: Version): number {
  if (a.modifiedAt !== b.modifiedAt) return a.modifiedAt > b.modifiedAt ? 1 : -1;
  if (a.deviceId === b.deviceId) return 0;
  return a.deviceId > b.deviceId ? 1 : -1;
}

export interface KdfParamsWire {
  alg: 'argon2id13';
  opsLimit: number;
  memLimitBytes: number;
}

// POST /v1/accounts
export interface RegisterRequest {
  loginId: string;
  authKey: string;
  kdf: KdfParamsWire;
  wrappedVaultKey: string;
}
export interface RegisterResponse {
  userId: string;
}

// POST /v1/sessions
export interface LoginRequest {
  loginId: string;
  authKey: string;
}
export interface LoginResponse {
  token: string;
  userId: string;
  kdf: KdfParamsWire;
  wrappedVaultKey: string;
}

// POST /v1/devices — binds the current session to a device
export interface RegisterDeviceRequest {
  /** Reuse an existing device id after re-login; omitted for a brand-new device. */
  deviceId?: string;
  /** Device name, encrypted client-side (the server never sees "Jane's Pixel"). */
  nameCiphertext: string;
}
export interface RegisterDeviceResponse {
  deviceId: string;
}
export interface DeviceInfo {
  deviceId: string;
  nameCiphertext: string;
  createdAt: string;
  lastSeenAt: string;
}

/** A record as stored and served. `ciphertext` is an opaque @omnisync/crypto envelope. */
export interface WireRecord extends Version {
  collection: Collection;
  id: string;
  ciphertext: string;
  /** Server-assigned, per-user, strictly increasing. Used as the pull cursor. */
  seq: number;
}

// POST /v1/records
export interface PushRecord {
  collection: Collection;
  id: string;
  ciphertext: string;
  modifiedAt: number;
}
export interface PushRequest {
  records: PushRecord[];
}
export type PushResult =
  | { collection: Collection; id: string; status: 'ok'; seq: number }
  /** A newer version already exists; the client should adopt `current`. */
  | { collection: Collection; id: string; status: 'stale'; current: WireRecord };
export interface PushResponse {
  results: PushResult[];
  serverTime: number;
}

// GET /v1/records?since=<seq>&limit=<n>
export interface PullResponse {
  records: WireRecord[];
  cursor: number;
  hasMore: boolean;
  serverTime: number;
}

// GET /v1/records/wait?since=<seq>&timeout=<s> — long-poll: returns as soon as anything newer than `since` exists
export interface WaitResponse {
  changed: boolean;
  serverTime: number;
}
export const MAX_WAIT_SECONDS = 25; // below the ~30s an MV3 service worker tolerates for a pending fetch

export interface ErrorResponse {
  error: string;
}
