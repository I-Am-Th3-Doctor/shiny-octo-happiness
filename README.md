# OmniSync

End-to-end encrypted sync for bookmarks (and later passwords, tabs and history) that works across Chromium and Firefox browsers on desktop and Android. It doesn't use Google, Mozilla or Apple sync infrastructure. The server only ever stores ciphertext.

**Status:** Phase 1 is in progress. Bookmark sync works end to end and is verified in two real Chromium profiles (see [Verification](#verification)). Password capture and autofill are the remaining Phase 1 work.

```
.
├── package.json              pnpm workspace root (typecheck / test / dev / e2e scripts)
├── pnpm-workspace.yaml
├── tsconfig.base.json
├── docker-compose.yml        optional Postgres for local dev
└── packages/
    ├── crypto/               ★ security-critical: the only code that touches keys
    │   ├── src/index.ts        Argon2id KDF, key hierarchy, XChaCha20-Poly1305 envelopes
    │   └── test/crypto.test.ts known-answer vector, tamper/AAD/nonce/padding tests
    ├── protocol/             wire types + the last-write-wins rule shared by server and clients
    │   ├── src/index.ts
    │   └── test/lww.test.ts
    ├── backend/              Hono + Postgres (PGlite when no DATABASE_URL)
    │   ├── src/app.ts          REST API: accounts, sessions, devices, push/pull/long-poll
    │   ├── src/db.ts           schema + Postgres/PGlite adapters
    │   ├── src/server.ts       Node entry point
    │   └── test/api.test.ts    runs on PGlite, and on real Postgres if TEST_DATABASE_URL is set
    └── extension/            WXT: Chromium MV3 and Firefox MV3 from one codebase
        ├── wxt.config.ts       manifest (permissions, CSP, Firefox/Android settings)
        ├── entrypoints/
        │   ├── background.ts   service worker: alarms, long-poll, bookmark listeners, key custody
        │   └── popup/          sign up / sign in / unlock / status UI
        ├── src/
        │   ├── account.ts      sign up / sign in / offline unlock
        │   ├── api.ts          REST client
        │   ├── sync.ts         pull → apply → diff → push orchestrator, clock-skew correction
        │   ├── sync-types.ts   CollectionAdapter interface (bookmarks now; passwords/tabs/history next)
        │   ├── bookmarks/engine.ts  bookmark adapter (snapshot diff, root mapping, LWW)
        │   ├── kv.ts           storage seam (browser.storage or in-memory for tests)
        │   └── messages.ts     popup ↔ background protocol
        ├── test/               a Chromium tree and a Firefox tree syncing through the real backend
        └── e2e/two-profiles.ts the Phase 1 success criterion, automated with real Chromium
```

## Running it locally

Requirements: Node 20+ and pnpm 10 (`corepack enable`). Docker/Postgres is optional.

```bash
pnpm install
pnpm test                 # crypto, protocol, backend and extension suites
pnpm typecheck
```

### 1. Start the sync server

```bash
pnpm dev:backend          # http://127.0.0.1:8787 with embedded PGlite (data in packages/backend/.data/)
```

To use real Postgres instead:

```bash
docker compose up -d postgres
DATABASE_URL=postgres://omnisync:omnisync@127.0.0.1:5432/omnisync pnpm dev:backend
```

Environment variables: `PORT` (8787), `HOST` (127.0.0.1), `DATABASE_URL`, `PGLITE_DIR`, `PGLITE_MEMORY=1`, `TRUST_PROXY=1` (rate-limit by `X-Forwarded-For` behind a reverse proxy), `QUIET=1`.

### 2. Build and load the extension in two Chromium profiles

```bash
pnpm build:extension      # → packages/extension/.output/chrome-mv3/
```

In **each** of two Chromium profiles (or two different Chromium browsers, e.g. Chrome and Brave):

1. Open `chrome://extensions`, turn on **Developer mode**, click **Load unpacked**, and choose `packages/extension/.output/chrome-mv3`.
2. Click the OmniSync toolbar icon. The server defaults to `http://localhost:8787`.
3. In profile A, enter a username and master password (8+ characters) and click **Create account**.
4. In profile B, enter the same username and password and click **Sign in**.
5. Add a bookmark in profile A. It appears in profile B within about 1–2 seconds.

`pnpm dev:extension` instead starts WXT's dev mode, which opens a fresh browser with the extension hot-reloading.

### 3. Firefox (desktop)

```bash
pnpm --filter @omnisync/extension build:firefox   # → .output/firefox-mv3/
```

Open `about:debugging#/runtime/this-firefox`, click **Load Temporary Add-on…**, and pick `.output/firefox-mv3/manifest.json`. Sign in with the same account. Firefox's toolbar, menu, "Other" and mobile folders map onto Chromium's bookmarks bar and other folders (see [Root mapping](#root-mapping)).

### 4. Run the automated success-criterion test

```bash
pnpm e2e
```

This builds the extension, starts an in-process backend, launches two isolated Chromium profiles with the unpacked extension, signs up in A and signs in in B. It then adds a bookmark in A and waits for it in B with no manual sync, renames it in B and waits for the rename in A, idles 40 s, and deletes it in A. Finally it checks that the server database contains no URL, title or username. It uses Playwright's Chromium at `/opt/pw-browsers/...` or `CHROMIUM_PATH`. Branded Google Chrome 137+ ignores `--load-extension`, so point `CHROMIUM_PATH` at Chromium or Chrome for Testing. Set `HEADED=1` to watch.

## Verification

Latest run in this repo:

| Suite | Result |
|---|---|
| `@omnisync/crypto` | 23 tests. The Argon2id + KDF output matches an independent implementation (argon2-cffi + Python `hashlib.blake2b`) byte for byte. |
| `@omnisync/protocol` | 4 tests |
| `@omnisync/backend` | 21 tests on PGlite + 21 on real Postgres 16, including concurrent pushes from 3 devices |
| `@omnisync/extension` | 14 tests: Chromium↔Firefox trees through the real backend with real encryption |
| `pnpm e2e` (two real Chromium profiles) | add A→B **1.3 s**, rename B→A **1.3 s**, delete A→B after 40 s idle **1.3 s**; server holds ciphertext only |

## Security model

```
master password ──Argon2id(t=3, m=64 MiB, salt=BLAKE2b("omnisync/v1/salt"‖username))──▶ masterKey
   ├─ BLAKE2b-KDF(ctx "os-auth_", 1) ─▶ authKey   sent to the server as the login credential
   └─ BLAKE2b-KDF(ctx "os-kek__", 2) ─▶ kek       never leaves the device
vaultKey (random 32 B) ── XChaCha20-Poly1305 under kek ──▶ wrappedVaultKey (stored on the server)
record payload ── XChaCha20-Poly1305 under vaultKey, AAD = collection‖id‖modifiedAt‖deviceId ──▶ "v1.<nonce‖ct>"
```

* **What the server sees:** `loginId = BLAKE2b(username)` (never the username itself), `SHA-256(authKey)`, the wrapped vault key, and opaque envelopes. It also sees each record's collection name, random ID, client timestamp and device ID, which LWW needs. Device names are encrypted client-side.
* **authKey and kek are independent KDF outputs**, so the login credential can't decrypt anything. A server-side SHA-256 is enough for authKey because it already sits behind Argon2id.
* **AAD binding:** the server can't move a ciphertext to another record or rewrite its timestamp or device to win a conflict. Both are tested, both in unit tests and against a tampered database.
* **Padding** to 128-byte blocks hides exact URL and title lengths.
* **Random 192-bit nonces** (XChaCha) make nonce reuse a non-issue without any counters.
* **Key custody in the extension:** the vault key lives in `storage.session`. That is RAM-only, wiped when the browser exits, and not readable by content scripts. After a browser restart the user unlocks offline by re-deriving kek and unwrapping the vault key; no server round trip is needed. **Lock** wipes it immediately.
* **No plaintext logging, no analytics.** The backend logs only `METHOD /route-pattern status latency`, never URLs with IDs, query strings, bodies or error details. A test enforces this. The Firefox manifest declares `data_collection_permissions: none`.
* **Envelope and KDF versioning** (`v1.` prefix, stored `kdf` params) leaves room for algorithm upgrades. The known-answer test pins v1 so an accidental change can't silently lock users out.

**Known limits (not yet handled):**
* A malicious server can withhold records or replay an older (validly signed) version of a record (rollback). Detecting that needs signed per-device logs or a client-side version vector.
* Forgetting the master password means losing the data, by design. A recovery-key export is a good Phase 1.5 item.
* Changing the password is cheap (only the vault key needs re-wrapping) but has no UI or endpoint yet.

## Backend choice: Hono + Postgres (not Cloudflare Workers + D1)

* **The whole point is independence from someone else's sync infrastructure.** Hono + Postgres runs on a $5 VPS, a Raspberry Pi or a home server, using only one Node process and Postgres. Workers + D1 would trade Google/Mozilla lock-in for Cloudflare lock-in. Hono itself is runtime-portable, so a Workers deployment stays possible later.
* **Correct LWW needs a read-compare-write transaction.** The push handler locks the user's row (`SELECT … FOR UPDATE`), compares versions, and assigns a per-user sequence number in commit order. That guarantees a pull cursor never skips a record committed late, which is the classic "pull by timestamp" bug. D1 offers only batched statements, not interactive transactions.
* **PGlite** (Postgres compiled to WASM) runs the identical schema in-process, so `pnpm dev:backend` and the test suites need no database setup.

### API (v1)

| Method & path | Auth | Purpose |
|---|---|---|
| `POST /v1/accounts` | — | `{loginId, authKey, kdf, wrappedVaultKey}` → `201 {userId}` |
| `POST /v1/sessions` | — | `{loginId, authKey}` → `{token, wrappedVaultKey, kdf}` (identical 401 for unknown user or wrong password; rate-limited) |
| `DELETE /v1/sessions/current` | bearer | sign out |
| `POST /v1/devices` | bearer | **register device** `{deviceId?, nameCiphertext}` → `{deviceId}`, binds the session to it |
| `GET /v1/devices` · `DELETE /v1/devices/:id` | bearer | list devices / revoke one (kills its sessions) |
| `POST /v1/records` | bearer + device | **push** ≤500 encrypted records → per-record `ok` / `stale` (+ current version) |
| `GET /v1/records?since=<seq>&limit=<n>` | bearer | **pull** everything newer than the cursor, ordered, paginated |
| `GET /v1/records/wait?since=<seq>&timeout=<≤25>` | bearer | long-poll: returns as soon as anything newer exists |

"Pull by timestamp" uses a server-assigned, per-user, gapless `seq` as the cursor rather than wall-clock time. Client clocks are only used for conflict ordering.

## Sync design

**Conflict resolution** is last-write-wins per record. Versions are ordered by `(modifiedAt, deviceId)` (`compareVersions` in `protocol`). The server enforces this atomically on push and returns `stale` with the winning version. Clients apply the same rule when merging pulled records against unsynced local edits. Client clocks are corrected toward the server's `serverTime` using a round-trip midpoint, and the server rejects timestamps more than 10 minutes in the future. Without the correction, a device with a fast clock would win every conflict. A test covers this.

**Bookmark engine (snapshot diff).** MV3 service workers are killed at will, so the engine doesn't depend on an in-memory event queue. Each sync:

1. Pulls new records and applies remote winners. Parents are created before children; records that arrive before their parent folder are deferred and persisted.
2. Walks the local tree and compares each node's keyed fingerprint with the last-synced baseline. That finds local creates, edits, moves and deletions (as tombstones).
3. Pushes the changes and advances the baseline for accepted writes.

Bookmark events do two things only: they record the local edit time used for LWW, and they schedule a debounced sync. If an event is lost, the next diff still catches the change. On a browser that already has the same bookmarks, the first sync **adopts** identical existing ones instead of duplicating them.

**Root mapping.** Built-in folders map to fixed IDs: Chromium *Bookmarks bar* ↔ Firefox *Bookmarks Toolbar* (`root_toolbar`), *Other bookmarks* ↔ *Other Bookmarks/unfiled* (`root_other`), *Mobile* ↔ *Mobile* (`root_mobile`). Items in Firefox's *Bookmarks Menu* land in *Other bookmarks* on Chromium. Managed (policy) bookmarks and Firefox separators aren't synced.

**Latency without a persistent worker.** A `chrome.alarms` alarm fires every 30 s, which is Chrome's minimum. Each alarm runs a sync and then holds a **long-poll** to `/v1/records/wait` for the rest of a 27 s window. Each request stays under 25 s, below the ~30 s a pending fetch may keep an MV3 worker alive. The window also ends before the next alarm, so the worker is never pinned awake. Local edits push after a 1 s debounce. The result is that changes typically arrive in ~1–2 s, and worst case within one alarm period. The long-poll is an optimization only: if it fails, the next alarm syncs anyway. With several backend instances, the in-process notifier should become Postgres `LISTEN/NOTIFY`.

**Known limitation:** sibling order uses per-record LWW on `index`. Concurrent reorders of the same folder on two devices can settle in a slightly different order on each device, but no data is lost. Firefox Sync solves this with folder child lists; we can adopt that if it matters in practice.

## Android

No native app in Phase 1. Use a browser that runs desktop extensions:

* **Quetta** (Chromium-based): load the Chromium build. The bookmarks API is available, so bookmark sync works.
* **Firefox for Android:** the extension installs (`gecko_android` is declared) and sign-in works. However, **Firefox for Android does not implement the `bookmarks` WebExtension API**, so bookmark sync can't run there. The popup says so. Passwords (Phase 1) and tabs (Phase 2) don't need that API, so Firefox for Android stays a target for those.

## Roadmap

* **Phase 1 (remaining): passwords.** Add a `passwords` `CollectionAdapter`: records `{origin, username, password, notes}` under the same envelope. Capture from a content script on form submit, using `webRequest` request-body inspection as a fallback where available. A Bitwarden-style autofill popup is filled by the background. Content scripts never see the vault key; they get only the one credential the user picks.
* **Phase 2: tab sessions.** A `tabs` adapter snapshots windows and tabs on demand and on the existing alarm, with restore on other devices.
* **Phase 3: history.** Batch `history.onVisited`, with a per-device opt-in toggle. The privacy and storage tradeoffs will be documented in the UI.
