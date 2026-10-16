/** popup ↔ background messages. The background is the only context that holds keys or talks to the server. */
export type Request =
  | { type: 'status' }
  | { type: 'signUp' | 'signIn'; serverUrl: string; username: string; password: string; deviceName: string }
  | { type: 'unlock'; password: string }
  | { type: 'lock' }
  | { type: 'signOut' }
  | { type: 'syncNow' };

export interface Status {
  state: 'signed-out' | 'locked' | 'ready';
  serverUrl?: string;
  username?: string;
  deviceId?: string;
  bookmarksSupported: boolean;
  lastSyncAt?: number;
  lastReport?: { pulled: number; pushed: number; conflicts: number; undecryptable: number };
  lastError?: string;
  syncing: boolean;
}

export type Response = { ok: true; status: Status } | { ok: false; error: string };
