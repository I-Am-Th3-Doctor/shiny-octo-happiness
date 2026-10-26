import type { BmNode, BookmarksApi } from '../src/bookmarks/engine.ts';

type Flavor = 'chromium' | 'firefox';

interface N {
  id: string;
  parentId?: string;
  title: string;
  url?: string;
  folderType?: string;
  children?: string[];
}

/** In-memory bookmarks API with each browser's real root layout. */
export class FakeBookmarks implements BookmarksApi {
  private nodes = new Map<string, N>();
  private nextId = 100;
  readonly roots: Record<'toolbar' | 'other' | 'mobile' | 'menu', string | undefined>;

  constructor(readonly flavor: Flavor) {
    if (flavor === 'chromium') {
      this.nodes.set('0', { id: '0', title: '', children: ['1', '2', '3'] });
      this.nodes.set('1', { id: '1', parentId: '0', title: 'Bookmarks bar', folderType: 'bookmarks-bar', children: [] });
      this.nodes.set('2', { id: '2', parentId: '0', title: 'Other bookmarks', folderType: 'other', children: [] });
      this.nodes.set('3', { id: '3', parentId: '0', title: 'Mobile bookmarks', folderType: 'mobile', children: [] });
      this.roots = { toolbar: '1', other: '2', mobile: '3', menu: undefined };
    } else {
      const top = ['menu________', 'toolbar_____', 'unfiled_____', 'mobile______'];
      this.nodes.set('root________', { id: 'root________', title: '', children: top });
      for (const id of top) this.nodes.set(id, { id, parentId: 'root________', title: id, children: [] });
      this.roots = { toolbar: 'toolbar_____', other: 'unfiled_____', mobile: 'mobile______', menu: 'menu________' };
    }
  }

  private toNode(id: string, deep: boolean): BmNode {
    const n = this.nodes.get(id)!;
    const parent = n.parentId ? this.nodes.get(n.parentId) : undefined;
    return {
      id: n.id,
      title: n.title,
      ...(n.parentId && { parentId: n.parentId, index: parent!.children!.indexOf(id) }),
      ...(n.url !== undefined && { url: n.url }),
      ...(n.folderType && { folderType: n.folderType }),
      ...(this.flavor === 'firefox' && { type: n.url !== undefined ? 'bookmark' : 'folder' }),
      ...(n.children && deep && { children: n.children.map((c) => this.toNode(c, true)) }),
    };
  }

  async getTree() {
    return [this.toNode(this.flavor === 'chromium' ? '0' : 'root________', true)];
  }

  async create(d: { parentId: string; index?: number; title?: string; url?: string }) {
    const parent = this.nodes.get(d.parentId);
    if (!parent?.children) throw new Error('bad parent');
    if (d.index !== undefined && d.index > parent.children.length) throw new Error('Index out of bounds');
    const id = String(this.nextId++);
    this.nodes.set(id, { id, parentId: d.parentId, title: d.title ?? '', ...(d.url !== undefined ? { url: d.url } : { children: [] }) });
    parent.children.splice(d.index ?? parent.children.length, 0, id);
    return this.toNode(id, false);
  }

  async update(id: string, c: { title?: string; url?: string }) {
    const n = this.nodes.get(id)!;
    if (c.title !== undefined) n.title = c.title;
    if (c.url !== undefined) n.url = c.url;
    return this.toNode(id, false);
  }

  async move(id: string, dest: { parentId?: string; index?: number }) {
    const n = this.nodes.get(id)!;
    const from = this.nodes.get(n.parentId!)!;
    const oldIndex = from.children!.indexOf(id);
    from.children!.splice(oldIndex, 1);
    const to = this.nodes.get(dest.parentId ?? n.parentId!)!;
    let index = dest.index ?? to.children!.length;
    // Chromium counts the destination index before removing the node from its old slot.
    if (this.flavor === 'chromium' && to === from && dest.index !== undefined && index > oldIndex) index -= 1;
    to.children!.splice(Math.min(index, to.children!.length), 0, id);
    n.parentId = to.id;
    return this.toNode(id, false);
  }

  async removeTree(id: string) {
    const n = this.nodes.get(id);
    if (!n) throw new Error('not found');
    const p = this.nodes.get(n.parentId!)!;
    p.children!.splice(p.children!.indexOf(id), 1);
    const drop = (x: string) => {
      this.nodes.get(x)?.children?.forEach(drop);
      this.nodes.delete(x);
    };
    drop(id);
  }

  // ---- test helpers

  add(parentId: string, title: string, url?: string, index?: number) {
    return this.create({ parentId, title, ...(url !== undefined && { url }), ...(index !== undefined && { index }) });
  }

  /** Human-readable outline of one root, e.g. ["Folder/", "Folder/Child -> https://x"]. */
  outline(rootId: string): string[] {
    const out: string[] = [];
    const walk = (id: string, prefix: string) => {
      for (const c of this.nodes.get(id)!.children!) {
        const n = this.nodes.get(c)!;
        out.push(n.url !== undefined ? `${prefix}${n.title} -> ${n.url}` : `${prefix}${n.title}/`);
        if (n.children) walk(c, `${prefix}${n.title}/`);
      }
    };
    walk(rootId, '');
    return out;
  }

  find(title: string): string {
    for (const n of this.nodes.values()) if (n.title === title) return n.id;
    throw new Error(`no node titled ${title}`);
  }
}
