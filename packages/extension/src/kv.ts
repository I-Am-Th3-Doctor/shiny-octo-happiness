/** Key-value seam over browser.storage.* so sync logic is testable in Node. */
export interface KV {
  get<T>(key: string): Promise<T | undefined>;
  getAll(): Promise<Record<string, unknown>>;
  set(items: Record<string, unknown>): Promise<void>;
  remove(keys: string | string[]): Promise<void>;
}

export function memoryKV(): KV {
  const m = new Map<string, unknown>();
  const clone = <T>(v: T): T => (v === undefined ? v : structuredClone(v));
  return {
    get: async (k) => clone(m.get(k)) as never,
    getAll: async () => Object.fromEntries([...m].map(([k, v]) => [k, clone(v)])),
    set: async (items) => {
      for (const [k, v] of Object.entries(items)) m.set(k, clone(v));
    },
    remove: async (keys) => {
      for (const k of [keys].flat()) m.delete(k);
    },
  };
}

type StorageArea = {
  get(keys: string | string[] | null): Promise<Record<string, unknown>>;
  set(items: Record<string, unknown>): Promise<void>;
  remove(keys: string | string[]): Promise<void>;
};

export function areaKV(area: StorageArea): KV {
  return {
    get: async (k) => (await area.get(k))[k] as never,
    getAll: () => area.get(null),
    set: (items) => area.set(items),
    remove: (keys) => area.remove(keys),
  };
}
