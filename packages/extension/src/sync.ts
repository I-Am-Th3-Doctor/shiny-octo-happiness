import * as oc from '@omnisync/crypto';
import type { PushRecord, WireRecord } from '@omnisync/protocol';
import type { ApiClient } from './api.ts';
import type { KV } from './kv.ts';
import type { CollectionAdapter, DecryptedRecord, Outgoing, SyncEnv } from './sync-types.ts';

export const CURSOR_KEY = 'sync.cursor';
const OFFSET_KEY = 'sync.clockOffset';
const PUSH_CHUNK = 200;
/** Every adapter keeps its local metadata under one of these prefixes. */
const SYNC_KEY_PREFIXES = ['sync.', 'bm.', 'pw.'];

export interface SyncReport {
  pulled: number;
  pushed: number;
  conflicts: number;
  undecryptable: number;
}

export interface SyncDeps {
  api: ApiClient;
  kv: KV;
  vaultKey: Uint8Array;
  deviceId: string;
  adapters: CollectionAdapter<any>[];
  clock: Awaited<ReturnType<typeof makeEnv>>;
  /** For tests. */
  localNow?: () => number;
}

/** Wall clock corrected by the offset to the server's clock, so a skewed device can't dominate LWW. */
export async function makeEnv(kv: KV, vaultKey: Uint8Array, deviceId: string, localNow = Date.now) {
  let offset = (await kv.get<number>(OFFSET_KEY)) ?? 0;
  const env: SyncEnv = {
    deviceId,
    now: () => Math.round(localNow() + offset),
    newId: oc.generateId,
    fingerprint: (v) => oc.fingerprint(vaultKey, v),
  };
  const observeServerTime = async (serverTime: number, sentAt: number) => {
    // Halve the round trip as a latency estimate.
    offset = serverTime - (sentAt + localNow()) / 2;
    await kv.set({ [OFFSET_KEY]: offset });
  };
  return { env, observeServerTime };
}

function decryptAll(vaultKey: Uint8Array, records: WireRecord[]) {
  const ok: (DecryptedRecord<unknown> & { collection: string })[] = [];
  let bad = 0;
  for (const r of records) {
    try {
      ok.push({
        collection: r.collection,
        id: r.id,
        modifiedAt: r.modifiedAt,
        deviceId: r.deviceId,
        payload: oc.decryptRecord(vaultKey, r, r.ciphertext),
        ciphertext: r.ciphertext,
      });
    } catch {
      bad++; // tampered or foreign ciphertext: never applied, never logged
    }
  }
  return { ok, bad };
}

/** One full pull → apply → diff → push cycle across all adapters. */
export async function runSync(deps: SyncDeps): Promise<SyncReport> {
  const { api, kv, vaultKey, deviceId, adapters } = deps;
  const localNow = deps.localNow ?? Date.now;
  const { observeServerTime } = deps.clock;
  const byCollection = new Map(adapters.map((a) => [a.collection as string, a]));
  const report: SyncReport = { pulled: 0, pushed: 0, conflicts: 0, undecryptable: 0 };

  for (const a of adapters) await a.begin();

  const dispatch = async (records: (DecryptedRecord<unknown> & { collection: string })[]) => {
    for (const a of adapters) {
      const mine = records.filter((r) => r.collection === a.collection);
      await a.applyRemote(mine);
    }
  };

  // Pull. The cursor only advances after the page is applied, so a killed worker re-pulls (idempotently).
  let cursor = (await kv.get<number>(CURSOR_KEY)) ?? 0;
  for (;;) {
    const sentAt = localNow();
    const page = await api.pull(cursor);
    await observeServerTime(page.serverTime, sentAt);
    const { ok, bad } = decryptAll(vaultKey, page.records);
    report.pulled += ok.length;
    report.undecryptable += bad;
    await dispatch(ok.filter((r) => byCollection.has(r.collection)));
    cursor = page.cursor;
    await kv.set({ [CURSOR_KEY]: cursor });
    if (!page.hasMore) break;
  }
  // Adapters with deferred records still get a chance to apply them.
  await dispatch([]);

  // Push. A 'stale' result means someone wrote a newer version meanwhile: apply it and diff again.
  for (let round = 0; round < 3; round++) {
    let stale = 0;
    for (const a of adapters) {
      const out = await a.collectChanges();
      for (let i = 0; i < out.length; i += PUSH_CHUNK) {
        const chunk = out.slice(i, i + PUSH_CHUNK);
        const wire: PushRecord[] = chunk.map((o) => ({
          collection: a.collection,
          id: o.id,
          modifiedAt: o.modifiedAt,
          ciphertext:
            o.ciphertext ??
            oc.encryptRecord(vaultKey, { collection: a.collection, id: o.id, modifiedAt: o.modifiedAt, deviceId }, o.payload),
        }));
        const sentAt = localNow();
        const res = await api.push(wire);
        await observeServerTime(res.serverTime, sentAt);
        const accepted: Outgoing<unknown>[] = [];
        const currents: WireRecord[] = [];
        const seqs: number[] = [];
        res.results.forEach((r, j) => {
          if (r.status === 'ok') {
            accepted.push(chunk[j]!);
            if (r.seq > cursor) seqs.push(r.seq);
          } else currents.push(r.current);
        });
        await a.acknowledge(accepted);
        // Seqs are per-user and gapless: if ours are exactly cursor+1..cursor+n, nobody else wrote
        // in between, so skip re-downloading our own writes.
        seqs.sort((x, y) => x - y);
        if (seqs.length && seqs[0] === cursor + 1 && seqs.at(-1) === cursor + seqs.length) {
          cursor = seqs.at(-1)!;
          await kv.set({ [CURSOR_KEY]: cursor });
        }
        report.pushed += accepted.length;
        if (currents.length) {
          stale += currents.length;
          report.conflicts += currents.length;
          const { ok, bad } = decryptAll(vaultKey, currents);
          report.undecryptable += bad;
          await a.applyRemote(ok);
        }
      }
    }
    if (stale === 0) break;
  }

  for (const a of adapters) await a.finish();
  return report;
}

/** Forget all sync metadata (sign-out / account switch). Local browser data is untouched. */
export async function resetSyncState(kv: KV) {
  const keys = Object.keys(await kv.getAll()).filter((k) => SYNC_KEY_PREFIXES.some((p) => k.startsWith(p)));
  if (keys.length) await kv.remove(keys);
}
