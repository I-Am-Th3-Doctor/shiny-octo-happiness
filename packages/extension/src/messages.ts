/**
 * Messages into the background, the only context that holds keys or talks to the server.
 *
 * Two audiences with different trust:
 *  - PageRequest: our own extension pages (popup, vault). Full control.
 *  - ContentRequest: content scripts running inside websites. Deliberately narrow: they can only
 *    ask about logins for the site they are on, and the background decides what "the site" is
 *    from the browser-supplied sender URL, never from anything the page or script claims.
 */
import type { LoginInput, UpsertStatus } from './passwords/vault.ts';

export type PageRequest =
  | { type: 'status' }
  | { type: 'signUp' | 'signIn'; serverUrl: string; username: string; password: string; deviceName: string }
  | { type: 'unlock'; password: string }
  | { type: 'lock' }
  | { type: 'signOut' }
  | { type: 'syncNow' }
  | { type: 'pw.list' }
  | { type: 'pw.reveal'; id: string }
  | { type: 'pw.save'; login: LoginInput }
  | { type: 'pw.delete'; id: string }
  | { type: 'pw.import'; logins: LoginInput[] }
  | { type: 'pw.forTab'; tabId: number }
  | { type: 'pw.fillTab'; tabId: number; id: string }
  | { type: 'pw.resolvePending'; tabId: number; action: PendingAction };

export type ContentRequest =
  | { type: 'cs.query' }
  | { type: 'cs.fill'; id: string }
  | { type: 'cs.captured'; username: string; password: string }
  | { type: 'cs.pending' }
  | { type: 'cs.resolvePending'; action: PendingAction };

/** Background → content script (top frame only). */
export interface FillCommand {
  type: 'cs.doFill';
  origin: string;
  username: string;
  password: string;
}

export type PendingAction = 'save' | 'never' | 'dismiss';

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
  /** Content scripts need host access to every site (Firefox asks the user separately for it). */
  siteAccess: boolean;
}

/** A login as shown in lists: never includes the password. */
export interface LoginSummary {
  id: string;
  origin: string;
  username: string;
  title?: string;
  notes?: string;
  modifiedAt: number;
}

/** "Save/update password?" prompt shown after a login form is submitted. */
export interface PendingPrompt {
  origin: string;
  username: string;
  kind: 'save' | 'update';
  locked: boolean;
}

export interface QueryResult {
  state: Status['state'];
  logins: Pick<LoginSummary, 'id' | 'username' | 'title'>[];
}

export type { LoginInput, UpsertStatus };

export type Response<T = unknown> = { ok: true; data: T } | { ok: false; error: string };
