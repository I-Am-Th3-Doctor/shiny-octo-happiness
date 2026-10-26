/**
 * OmniSync's own password vault (independent of the browsers' built-in password managers).
 *
 * Storage: the local copy is kept exactly as the server stores it, as encrypted envelopes.
 * Each login is sealed with the vault key, so passwords are never written to disk in plaintext,
 * not even in the extension's private storage. They're decrypted in memory only while the
 * vault is unlocked, and only for as long as an operation needs them.
 *
 * Sync: one record per login, last-write-wins per login. Deleting writes a tombstone so the
 * deletion propagates. Two logins for the same site and username are the same account: when
 * both browsers create one independently, the newer version is kept and the other tombstoned.
 */
import * as oc from '@omnisync/crypto';
import { compareVersions, type Version } from '@omnisync/protocol';
import type { KV } from '../kv.ts';
import type { CollectionAdapter, DecryptedRecord, Outgoing, SyncEnv } from '../sync-types.ts';
import { type Login, type LoginEntry, type PwPayload, loginKey, normalizeOrigin, originMatches } from './model.ts';

const STATE_KEY = 'pw.state';
const NEVER_KEY = 'pw.never';
const COLLECTION = 'passwords';

interface StoredRecord extends Version {
  ciphertext: string;
}

interface VaultState {
  v: 1;
  records: Record<string, StoredRecord>;
  /** Locally written records not yet accepted by the server. */
  dirty: Record<string, true>;
}

export interface LoginInput {
  id?: string;
  origin: string;
  username: string;
  password: string;
  title?: string;
  notes?: string;
}

export type UpsertStatus = 'added' | 'updated' | 'unchanged';

export class PasswordVault implements CollectionAdapter<PwPayload> {
  readonly collection = COLLECTION;
  private state: VaultState = { v: 1, records: {}, dirty: {} };

  constructor(
    private readonly kv: KV,
    private readonly env: SyncEnv,
    private readonly vaultKey: Uint8Array,
  ) {}

  private seal(id: string, v: Version, payload: PwPayload): string {
    return oc.encryptRecord(this.vaultKey, { collection: COLLECTION, id, ...v }, payload);
  }

  private open(id: string, r: StoredRecord): PwPayload {
    return oc.decryptRecord<PwPayload>(this.vaultKey, { collection: COLLECTION, id, modifiedAt: r.modifiedAt, deviceId: r.deviceId }, r.ciphertext);
  }

  async begin(): Promise<void> {
    this.state = (await this.kv.get<VaultState>(STATE_KEY)) ?? { v: 1, records: {}, dirty: {} };
  }

  private save() {
    return this.kv.set({ [STATE_KEY]: this.state });
  }

  /** Decrypt every live login. Records that fail to decrypt (foreign key, tampering) are skipped. */
  entries(): LoginEntry[] {
    const out: LoginEntry[] = [];
    for (const [id, r] of Object.entries(this.state.records)) {
      try {
        const p = this.open(id, r);
        if (!p.deleted) out.push({ id, modifiedAt: r.modifiedAt, ...p });
      } catch {
        /* skip */
      }
    }
    return out.sort((a, b) => a.origin.localeCompare(b.origin) || a.username.localeCompare(b.username));
  }

  forPage(pageUrl: string): LoginEntry[] {
    return this.entries().filter((e) => originMatches(e.origin, pageUrl));
  }

  get(id: string): LoginEntry | undefined {
    const r = this.state.records[id];
    if (!r) return undefined;
    const p = this.open(id, r);
    return p.deleted ? undefined : { id, modifiedAt: r.modifiedAt, ...p };
  }

  private write(id: string, payload: PwPayload) {
    const prev = this.state.records[id];
    const v: Version = { modifiedAt: Math.max(this.env.now(), (prev?.modifiedAt ?? 0) + 1), deviceId: this.env.deviceId };
    this.state.records[id] = { ...v, ciphertext: this.seal(id, v, payload) };
    this.state.dirty[id] = true;
  }

  /** Add or update a login. Without an id, an existing login for the same site + username is updated. */
  async upsert(input: LoginInput, { save = true } = {}): Promise<{ id: string; status: UpsertStatus }> {
    const origin = normalizeOrigin(input.origin);
    if (!origin) throw new Error('A login needs an http(s) website address');
    if (!input.password) throw new Error('Password is empty');
    const username = input.username.trim();
    const existing = input.id
      ? this.get(input.id)
      : this.entries().find((e) => loginKey(e) === loginKey({ origin, username }));
    const next: Login = {
      origin,
      username,
      password: input.password,
      createdAt: existing?.createdAt ?? this.env.now(),
      ...((input.title ?? existing?.title) && { title: input.title ?? existing?.title }),
      ...((input.notes ?? existing?.notes) && { notes: input.notes ?? existing?.notes }),
    };
    let status: UpsertStatus = 'added';
    const id = existing?.id ?? this.env.newId();
    if (existing) {
      const same = (['origin', 'username', 'password', 'title', 'notes'] as const).every((k) => (existing[k] ?? '') === (next[k] ?? ''));
      if (same) return { id, status: 'unchanged' };
      status = 'updated';
    }
    this.write(id, next);
    if (save) await this.save();
    return { id, status };
  }

  async remove(id: string): Promise<void> {
    if (!this.get(id)) return;
    this.write(id, { deleted: true });
    await this.save();
  }

  async importMany(logins: LoginInput[]) {
    const counts = { added: 0, updated: 0, unchanged: 0, failed: 0 };
    for (const l of logins) {
      try {
        counts[(await this.upsert(l, { save: false })).status]++;
      } catch {
        counts.failed++;
      }
    }
    await this.save();
    return counts;
  }

  // ---- "never save for this site" (stored as keyed fingerprints, not readable site names)

  async isNever(origin: string): Promise<boolean> {
    const list = (await this.kv.get<string[]>(NEVER_KEY)) ?? [];
    return list.includes(this.env.fingerprint(['never', origin]));
  }

  async addNever(origin: string): Promise<void> {
    const list = (await this.kv.get<string[]>(NEVER_KEY)) ?? [];
    const fp = this.env.fingerprint(['never', origin]);
    if (!list.includes(fp)) await this.kv.set({ [NEVER_KEY]: [...list, fp] });
  }

  // ---- CollectionAdapter

  async applyRemote(records: DecryptedRecord<PwPayload>[]): Promise<void> {
    let changed = false;
    for (const r of records) {
      const cur = this.state.records[r.id];
      if (cur && compareVersions(r, cur) <= 0) continue; // ours is the same or newer
      const v: Version = { modifiedAt: r.modifiedAt, deviceId: r.deviceId };
      this.state.records[r.id] = { ...v, ciphertext: r.ciphertext ?? this.seal(r.id, v, r.payload) };
      delete this.state.dirty[r.id]; // a newer remote version supersedes an unpushed local one
      changed = true;
    }
    if (changed) await this.save();
  }

  /** Same site + username stored twice (created independently on two browsers): keep the newest. */
  private dedupe() {
    const groups = new Map<string, LoginEntry[]>();
    for (const e of this.entries()) groups.set(loginKey(e), [...(groups.get(loginKey(e)) ?? []), e]);
    for (const g of groups.values()) {
      if (g.length < 2) continue;
      const version = (e: LoginEntry) => this.state.records[e.id]!;
      g.sort((a, b) => compareVersions(version(b), version(a)) || (a.id < b.id ? -1 : 1));
      for (const loser of g.slice(1)) this.write(loser.id, { deleted: true });
    }
  }

  async collectChanges(): Promise<Outgoing<PwPayload>[]> {
    this.dedupe();
    await this.save();
    const out: Outgoing<PwPayload>[] = [];
    for (const id of Object.keys(this.state.dirty)) {
      const r = this.state.records[id];
      if (!r || r.deviceId !== this.env.deviceId) {
        delete this.state.dirty[id];
        continue;
      }
      // The stored envelope is already bound to (id, modifiedAt, deviceId): push it as-is.
      out.push({ id, modifiedAt: r.modifiedAt, payload: this.open(id, r), fp: '', ciphertext: r.ciphertext });
    }
    return out;
  }

  async acknowledge(accepted: Outgoing<PwPayload>[]): Promise<void> {
    for (const o of accepted) if (this.state.records[o.id]?.modifiedAt === o.modifiedAt) delete this.state.dirty[o.id];
    await this.save();
  }

  async finish(): Promise<void> {}
}
