import type { Collection } from '@omnisync/protocol';

export interface DecryptedRecord<P> {
  id: string;
  modifiedAt: number;
  deviceId: string;
  payload: P;
  /** The envelope as received, for adapters that keep an encrypted local replica (passwords). */
  ciphertext?: string;
}

export interface Outgoing<P> {
  id: string;
  modifiedAt: number;
  payload: P;
  /** Fingerprint to record as the new baseline once the server accepts this write. */
  fp: string;
  /** Already-encrypted envelope (same id/modifiedAt/deviceId). If absent, the orchestrator encrypts `payload`. */
  ciphertext?: string;
}

/** What an adapter needs from its environment; all of it is injectable for tests. */
export interface SyncEnv {
  deviceId: string;
  /** Server-corrected wall clock (ms). */
  now(): number;
  newId(): string;
  fingerprint(value: unknown): string;
}

/** One per synced data type (bookmarks now; passwords, tabs, history later). Local metadata keys must use a prefix listed in sync.ts. */
export interface CollectionAdapter<P> {
  readonly collection: Collection;
  begin(): Promise<void>;
  applyRemote(records: DecryptedRecord<P>[]): Promise<void>;
  collectChanges(): Promise<Outgoing<P>[]>;
  acknowledge(accepted: Outgoing<P>[]): Promise<void>;
  finish(): Promise<void>;
}
