/**
 * Bookmark sync adapter.
 *
 * Design: snapshot-diff, not event replay. MV3 service workers are killed at
 * will, so an in-memory event queue would lose changes. Instead every sync:
 *   1. applies remote records (LWW against the last-synced baseline),
 *   2. walks the local tree and diffs each node's fingerprint against the
 *      baseline to find local creates/updates/deletes,
 *   3. pushes those and advances the baseline for accepted writes.
 * Bookmark event listeners only (a) stamp the local edit time used for LWW and
 * (b) schedule a sync. Missing either is harmless: the diff still catches the change.
 *
 * Each bookmark/folder has a random sync id (local ids differ per browser).
 * The built-in roots map to fixed ids so Chromium's "Bookmarks bar" and
 * Firefox's "Bookmarks Toolbar" are the same folder.
 */
import { compareVersions } from '@omnisync/protocol';
import type { KV } from '../kv.ts';
import type { CollectionAdapter, DecryptedRecord, Outgoing, SyncEnv } from '../sync-types.ts';

export interface BmNode {
  id: string;
  parentId?: string;
  index?: number;
  title: string;
  url?: string;
  type?: string; // Firefox: 'bookmark' | 'folder' | 'separator'
  folderType?: string; // Chrome 134+: 'bookmarks-bar' | 'other' | 'mobile' | 'managed'
  unmodifiable?: string;
  children?: BmNode[];
}

/** The subset of browser.bookmarks we use. */
export interface BookmarksApi {
  getTree(): Promise<BmNode[]>;
  create(d: { parentId: string; index?: number; title?: string; url?: string }): Promise<BmNode>;
  update(id: string, changes: { title?: string; url?: string }): Promise<BmNode>;
  move(id: string, dest: { parentId?: string; index?: number }): Promise<BmNode>;
  removeTree(id: string): Promise<void>;
}

export type BmPayload =
  | { deleted: true }
  | { deleted?: false; kind: 'folder' | 'bookmark'; title: string; url?: string; parent: string; index: number };
type LivePayload = Extract<BmPayload, { kind: string }>;

export const ROOTS = ['root_toolbar', 'root_other', 'root_mobile', 'root_menu'] as const;
type RootId = (typeof ROOTS)[number];
const isRoot = (id: string): id is RootId => (ROOTS as readonly string[]).includes(id);

const TOMBSTONE = 'tombstone';
const STATE_KEY = 'bm.state';
export const DIRTY_PREFIX = 'bm.dirty.';

interface Baseline {
  fp: string; // fingerprint of last-synced payload, or TOMBSTONE
  modifiedAt: number;
  deviceId: string;
}
interface State {
  v: 1;
  local2sync: Record<string, string>;
  baseline: Record<string, Baseline>;
  /** Remote records whose parent folder hasn't arrived yet. */
  pending: DecryptedRecord<BmPayload>[];
}

const emptyState = (): State => ({ v: 1, local2sync: {}, baseline: {}, pending: [] });

/** Map a browser's built-in folders to stable cross-browser root ids. */
export function detectRoots(tree: BmNode[]): Map<string, RootId> {
  const out = new Map<string, RootId>();
  const top = tree[0]?.children ?? [];
  const byFolderType: Record<string, RootId> = { 'bookmarks-bar': 'root_toolbar', other: 'root_other', mobile: 'root_mobile' };
  const byId: Record<string, RootId> = {
    // Firefox
    toolbar_____: 'root_toolbar',
    unfiled_____: 'root_other',
    mobile______: 'root_mobile',
    menu________: 'root_menu',
    // Chromium (pre-folderType)
    '1': 'root_toolbar',
    '2': 'root_other',
    '3': 'root_mobile',
  };
  const taken = new Set<RootId>();
  for (const n of top) {
    const r = (n.folderType && byFolderType[n.folderType]) || byId[n.id];
    if (r && !taken.has(r)) {
      out.set(n.id, r);
      taken.add(r);
    }
  }
  return out;
}

/** Where a remote item in a root this browser lacks (e.g. Firefox "Menu" on Chrome) should land. */
function rootFallback(r: RootId, available: Map<RootId, string>): string | undefined {
  return available.get(r) ?? available.get('root_other') ?? available.get('root_toolbar');
}

interface LocalIndex {
  nodes: Map<string, BmNode>;
  children: Map<string, string[]>;
  roots: Map<string, RootId>; // localId -> root
  rootLocal: Map<RootId, string>; // root -> localId
}

function indexTree(tree: BmNode[]): LocalIndex {
  const roots = detectRoots(tree);
  const nodes = new Map<string, BmNode>();
  const children = new Map<string, string[]>();
  const walk = (n: BmNode) => {
    if (n.unmodifiable) return; // managed (enterprise policy) bookmarks are read-only and not ours to sync
    nodes.set(n.id, n);
    const kids = (n.children ?? []).filter((c) => !c.unmodifiable && c.type !== 'separator');
    children.set(
      n.id,
      kids.map((c) => c.id),
    );
    kids.forEach(walk);
  };
  for (const top of tree[0]?.children ?? []) if (roots.has(top.id)) walk(top);
  return { nodes, children, roots, rootLocal: new Map([...roots].map(([l, r]) => [r, l])) };
}

export class BookmarkAdapter implements CollectionAdapter<BmPayload> {
  readonly collection = 'bookmarks' as const;
  private state: State = emptyState();
  private applied = new Set<string>();
  private diffStartedAt = 0;

  constructor(
    private readonly api: BookmarksApi,
    private readonly kv: KV,
    private readonly env: SyncEnv,
  ) {}

  async begin(): Promise<void> {
    this.state = (await this.kv.get<State>(STATE_KEY)) ?? emptyState();
    this.applied.clear();
  }

  private save() {
    return this.kv.set({ [STATE_KEY]: this.state });
  }

  private async dirtyTimes(): Promise<Map<string, number>> {
    const all = await this.kv.getAll();
    const m = new Map<string, number>();
    for (const [k, v] of Object.entries(all)) if (k.startsWith(DIRTY_PREFIX)) m.set(k.slice(DIRTY_PREFIX.length), v as number);
    return m;
  }

  private payloadOf(idx: LocalIndex, localId: string): LivePayload | null {
    const n = idx.nodes.get(localId);
    if (!n || !n.parentId) return null;
    const parent = idx.roots.get(n.parentId) ?? this.state.local2sync[n.parentId];
    if (!parent) return null;
    const siblings = idx.children.get(n.parentId) ?? [];
    return n.url !== undefined
      ? { kind: 'bookmark', title: n.title, url: n.url, parent, index: siblings.indexOf(localId) }
      : { kind: 'folder', title: n.title, parent, index: siblings.indexOf(localId) };
  }

  private sync2local(): Map<string, string> {
    return new Map(Object.entries(this.state.local2sync).map(([l, s]) => [s, l]));
  }

  // -------------------------------------------------------------------------
  // 1. Remote → local

  async applyRemote(incoming: DecryptedRecord<BmPayload>[]): Promise<void> {
    // Latest version per id across newly pulled + previously deferred records.
    const latest = new Map<string, DecryptedRecord<BmPayload>>();
    for (const r of [...this.state.pending, ...incoming]) {
      const cur = latest.get(r.id);
      if (!cur || compareVersions(r, cur) > 0) latest.set(r.id, r);
    }
    this.state.pending = [];
    if (latest.size === 0) return;

    const idx = indexTree(await this.api.getTree());
    const dirty = await this.dirtyTimes();
    const s2l = this.sync2local();
    const liveLocal = (syncId: string) => {
      const l = s2l.get(syncId);
      return l && idx.nodes.has(l) ? l : undefined;
    };

    const shouldApply = (r: DecryptedRecord<BmPayload>): boolean => {
      if (isRoot(r.id)) return false;
      const b = this.state.baseline[r.id];
      if (b && compareVersions(r, b) <= 0) return false; // already have this version (e.g. our own echo)
      const localId = s2l.get(r.id);
      const live = liveLocal(r.id);
      let changedLocally: boolean;
      if (live) {
        const p = this.payloadOf(idx, live);
        changedLocally = !b || !p || this.env.fingerprint(p) !== b.fp;
      } else {
        changedLocally = !!b && b.fp !== TOMBSTONE; // deleted locally since last sync
      }
      if (!changedLocally) return true;
      // Concurrent edit: LWW between the local edit time and the remote version.
      const localTime = (localId && dirty.get(localId)) || 0;
      return r.modifiedAt >= localTime;
    };

    const resolveParent = (parent: string): string | undefined => {
      if (isRoot(parent)) return rootFallback(parent, idx.rootLocal);
      return liveLocal(parent);
    };

    const live = [...latest.values()].filter((r) => !r.payload.deleted && shouldApply(r));
    const dels = [...latest.values()].filter((r) => r.payload.deleted && shouldApply(r));
    // Parents before children where possible; siblings in index order so positions land correctly.
    live.sort((a, b) => ((a.payload as LivePayload).index ?? 0) - ((b.payload as LivePayload).index ?? 0));

    let queue = live;
    for (let progress = true; progress && queue.length; ) {
      progress = false;
      const next: typeof queue = [];
      for (const r of queue) {
        const p = r.payload as LivePayload;
        const parentLocal = resolveParent(p.parent);
        if (!parentLocal) {
          next.push(r);
          continue;
        }
        try {
          await this.applyLive(r.id, p, parentLocal, idx, s2l, liveLocal(r.id));
          this.state.baseline[r.id] = { fp: '', modifiedAt: r.modifiedAt, deviceId: r.deviceId };
          this.applied.add(r.id);
          progress = true;
        } catch {
          next.push(r);
        }
      }
      queue = next;
    }
    for (const r of queue) {
      const parentBase = this.state.baseline[(r.payload as LivePayload).parent];
      if (parentBase?.fp === TOMBSTONE) {
        this.state.baseline[r.id] = { fp: TOMBSTONE, modifiedAt: r.modifiedAt, deviceId: r.deviceId };
      } else {
        this.state.pending.push(r); // parent not here yet; retry next sync
      }
    }

    for (const r of dels) {
      const l = liveLocal(r.id);
      if (l) {
        await this.api.removeTree(l).catch(() => {});
        idx.nodes.delete(l);
      }
      const old = s2l.get(r.id);
      if (old) delete this.state.local2sync[old];
      s2l.delete(r.id);
      this.state.baseline[r.id] = { fp: TOMBSTONE, modifiedAt: r.modifiedAt, deviceId: r.deviceId };
      this.applied.add(r.id);
    }

    // Baseline for applied records = what actually landed locally. Small differences (an index
    // clamped because a sibling is missing) are accepted rather than echoed back, which avoids
    // two browsers ping-ponging over positions.
    const after = indexTree(await this.api.getTree());
    for (const id of this.applied) {
      const l = this.sync2local().get(id);
      const b = this.state.baseline[id];
      if (!b || b.fp === TOMBSTONE) continue;
      const p = l ? this.payloadOf(after, l) : null;
      if (p) b.fp = this.env.fingerprint(p);
    }
    await this.save();
  }

  private async applyLive(
    syncId: string,
    p: LivePayload,
    parentLocal: string,
    idx: LocalIndex,
    s2l: Map<string, string>,
    existing: string | undefined,
  ) {
    const siblings = idx.children.get(parentLocal) ?? [];
    const index = Math.max(0, Math.min(p.index, siblings.length));
    let localId = existing;

    if (!localId) {
      // Adopt an identical unmapped local node (first sync on a browser that already has the same bookmarks).
      const mapped = new Set(Object.keys(this.state.local2sync));
      localId = siblings.find((sid) => {
        const n = idx.nodes.get(sid)!;
        return !mapped.has(sid) && n.title === p.title && (n.url ?? undefined) === (p.kind === 'bookmark' ? p.url : undefined);
      });
    }

    if (localId) {
      const n = idx.nodes.get(localId)!;
      const wantUrl = p.kind === 'bookmark' ? p.url : undefined;
      if (n.title !== p.title || (wantUrl !== undefined && n.url !== wantUrl)) {
        await this.api.update(localId, wantUrl === undefined ? { title: p.title } : { title: p.title, url: wantUrl });
        n.title = p.title;
        if (wantUrl !== undefined) n.url = wantUrl;
      }
      const curParent = n.parentId!;
      const curIndex = (idx.children.get(curParent) ?? []).indexOf(localId);
      if (curParent !== parentLocal || curIndex !== index) {
        await this.api.move(localId, { parentId: parentLocal, index });
        idx.children.set(curParent, (idx.children.get(curParent) ?? []).filter((x) => x !== localId));
        const dest = idx.children.get(parentLocal) ?? [];
        dest.splice(Math.min(index, dest.length), 0, localId);
        idx.children.set(parentLocal, dest);
        n.parentId = parentLocal;
      }
    } else {
      const created = await this.api.create({
        parentId: parentLocal,
        index,
        title: p.title,
        ...(p.kind === 'bookmark' && { url: p.url }),
      });
      localId = created.id;
      idx.nodes.set(localId, { ...created, parentId: parentLocal });
      idx.children.set(localId, []);
      siblings.splice(index, 0, localId);
      idx.children.set(parentLocal, siblings);
    }
    this.state.local2sync[localId] = syncId;
    s2l.set(syncId, localId);
  }

  // -------------------------------------------------------------------------
  // 2. Local → outgoing

  async collectChanges(): Promise<Outgoing<BmPayload>[]> {
    this.diffStartedAt = this.env.now();
    const idx = indexTree(await this.api.getTree());
    const dirty = await this.dirtyTimes();
    const out: Outgoing<BmPayload>[] = [];
    const present = new Set<string>();
    const stamp = (localId: string | undefined, b?: Baseline) =>
      Math.max((localId && dirty.get(localId)) || this.env.now(), (b?.modifiedAt ?? 0) + 1);

    // Assign sync ids top-down so every node's parent id is known when we reach it.
    const visit = (localId: string) => {
      for (const child of idx.children.get(localId) ?? []) {
        const syncId = (this.state.local2sync[child] ??= this.env.newId());
        present.add(syncId);
        const p = this.payloadOf(idx, child);
        if (p) {
          const b = this.state.baseline[syncId];
          const fp = this.env.fingerprint(p);
          if (!b || b.fp !== fp) out.push({ id: syncId, modifiedAt: stamp(child, b), payload: p, fp });
        }
        visit(child);
      }
    };
    for (const rootLocal of idx.roots.keys()) visit(rootLocal);

    // Deletions: synced before, gone now.
    const s2l = this.sync2local();
    const pendingIds = new Set(this.state.pending.map((r) => r.id));
    for (const [syncId, b] of Object.entries(this.state.baseline)) {
      if (b.fp === TOMBSTONE || present.has(syncId) || pendingIds.has(syncId) || isRoot(syncId)) continue;
      out.push({ id: syncId, modifiedAt: stamp(s2l.get(syncId), b), payload: { deleted: true }, fp: TOMBSTONE });
    }
    // Forget mappings for local nodes that no longer exist.
    for (const l of Object.keys(this.state.local2sync)) if (!idx.nodes.has(l)) delete this.state.local2sync[l];
    await this.save();
    return out;
  }

  async acknowledge(accepted: Outgoing<BmPayload>[]): Promise<void> {
    for (const o of accepted) {
      this.state.baseline[o.id] = { fp: o.fp, modifiedAt: o.modifiedAt, deviceId: this.env.deviceId };
    }
    await this.save();
  }

  /** Clear edit stamps that the diff has already accounted for. */
  async finish(): Promise<void> {
    const dirty = await this.dirtyTimes();
    const stale = [...dirty].filter(([, t]) => t <= this.diffStartedAt).map(([l]) => DIRTY_PREFIX + l);
    if (stale.length) await this.kv.remove(stale);
  }
}
