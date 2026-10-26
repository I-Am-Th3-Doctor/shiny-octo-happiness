# OmniSync

**End-to-end encrypted sync between Zen Browser and Brave on desktop.**

Zen is built on Firefox and uses Firefox Sync. Brave is built on Chromium and uses Brave Sync. The two can't talk to each other. OmniSync is a bridge: one browser extension, built once for each browser, plus a small relay server you run yourself. It does not use Google, Mozilla or Brave sync infrastructure. The relay only ever stores encrypted data; it never sees your bookmarks, passwords or keys.

| Feature | Status |
|---|---|
| Bookmarks, two-way (add / move / rename / delete, folder structure, per-item last-write-wins, same-URL dedupe within a folder) | ✅ Phase 1 done |
| Passwords: OmniSync's own vault, capture + autofill on websites, CSV import from both browsers | ✅ Phase 1 done |
| History | ⏸ Not started. It's the stretch goal, after Phase 1 has been used for real. |

## Quick start

Requirements: Node 20+, pnpm 10 (`corepack enable`).

```bash
pnpm install
pnpm dev:backend     # relay on http://127.0.0.1:8787 (embedded Postgres, nothing to set up)
pnpm build           # builds both: .output/chrome-mv3 (Brave) and .output/firefox-mv3 (Zen)
```

The two builds are `wxt build -b chrome` for Brave and `wxt build -b firefox --mv3` for Zen. Both are Manifest V3, so the extension behaves the same in both browsers.

**Load it in Brave:** open `brave://extensions`, turn on **Developer mode**, click **Load unpacked**, and choose `packages/extension/.output/chrome-mv3`.

**Load it in Zen:** open `about:debugging#/runtime/this-firefox`, click **Load Temporary Add-on…**, and choose `packages/extension/.output/firefox-mv3/manifest.json`. Temporary add-ons disappear when Zen restarts. To install permanently, sign it as an *unlisted* add-on on addons.mozilla.org. That's free and automated: `npx web-ext sign --channel=unlisted -s packages/extension/.output/firefox-mv3` with your AMO API keys.

**First run:**

1. In either browser, click the OmniSync toolbar button and choose **Create account**. Pick a username and a strong master password.
2. In the other browser, choose **Sign in** with the same username and master password.
3. **Zen only:** Firefox-based browsers make "access to all websites" opt-in. Click **Allow on all sites** in the OmniSync popup. Without it, password capture and autofill can't run on pages.
4. Bookmarks start syncing immediately; changes reach the other browser in about 1–2 seconds.
5. Bring your existing passwords over once. Open **Password vault** from the popup and import each browser's export:
   - **Brave:** `brave://password-manager/settings` → **Export passwords**
   - **Zen:** `about:logins` → **⋯** → **Export Passwords**

   Then **delete the exported CSV files**, because they contain every password in plain text. Extensions can't read a browser's built-in password store, so a one-time export is the only way in.
6. Recommended: turn off each browser's own "offer to save passwords" so you don't get two prompts. In Brave, that's `brave://settings/passwords`. In Zen, it's `about:preferences#privacy` → **Ask to save passwords**.

Other ways to run things:

- `pnpm dev:brave` / `pnpm dev:zen` start WXT dev mode with hot reload. Point WXT at the real browsers with a `web-ext.config.ts` (`binaries: { chrome: '/path/to/brave', firefox: '/path/to/zen' }`).
- Real Postgres: run `docker compose up -d`, then `DATABASE_URL=postgres://omnisync:omnisync@127.0.0.1:5432/omnisync pnpm dev:backend`.

## How the encryption works (plain language)

The full reasoning is in [`packages/crypto/src/index.ts`](packages/crypto/src/index.ts); this is the summary.

1. **Your master password is turned into keys on your device, slowly on purpose (Argon2id).** If someone stole the relay's database, they would try billions of password guesses. Argon2id makes each guess cost 64 MiB of memory and about half a second of work. That's nothing for you once at sign-in, and ruinous at a billion guesses, because memory-hard work can't be sped up cheaply with GPUs. Argon2id is the current OWASP recommendation.
2. **That secret is split into two unrelated keys.** One is a login token the relay checks. The other unlocks your data and never leaves the device. Knowing the login token tells you nothing about the unlock key.
3. **Your data is encrypted with a random "vault key", not with your password.** The relay keeps the vault key only in locked form. A future password change only needs to re-lock that one key.
4. **Every bookmark and password is sealed with XChaCha20-Poly1305.** This hides the content and detects tampering: if anyone changes a single bit, the seal breaks and the item is rejected. ChaCha20 is fast in software everywhere, which matters inside a browser. The "X" version uses long random nonces, so the same one is never reused by accident across two browsers.
5. **Each sealed item is tied to its label.** The item's type, ID, timestamp and device are covered by the seal. So the relay can't swap one item's contents into another or fake a newer timestamp to win a conflict.
6. **Sizes are blurred.** Items are padded to multiples of 128 bytes, so the relay can't tell a short URL from a long one.
7. **What the relay does see:** a hash of your username (never the username), how many items you have, when they change, and from which (randomly numbered) device.

The vault key stays only in the browser's memory-only session storage. It's wiped when the browser closes, so after a restart you unlock with your master password. That unlock happens offline. **Lock** wipes it immediately.

## Bookmarks

- **Native APIs on both sides.** The extension uses each browser's own `bookmarks` API, so what you see in Zen's and Brave's bookmark managers *is* the synced data.
- **Folders map across browsers:**

  | Zen | Brave |
  |---|---|
  | Bookmarks Toolbar | Bookmarks bar |
  | Other Bookmarks | Other bookmarks |
  | Mobile Bookmarks | Mobile bookmarks |
  | Bookmarks Menu | Other bookmarks (Brave has no menu folder) |

  Nested folders and order inside folders are preserved.
- **Merge rule: per item, last write wins.** If the same bookmark is changed in both browsers before they sync, the later change wins. Changes to *different* bookmarks never conflict. The timestamps are corrected against the server clock, so a browser on a machine with a wrong clock can't win every conflict.
- **Duplicate URLs in the same folder are collapsed to one.** Both browsers pick the same survivor, so they converge. The same URL in *different* folders is kept. On first sign-in, bookmarks that already exist in both browsers are matched up instead of duplicated.
- **Order across engines:** Chromium's `bookmarks.move()` counts the target position *before* removing the item; Firefox counts the final position. OmniSync compensates in the Brave build. The Chromium behaviour was confirmed in real Chromium (moving A of `ABCD` to index 2 gives `BACD`).
- **Not synced:** separators, policy-managed bookmarks, and Zen's Essentials/pinned tabs and workspaces. Those aren't bookmarks.

## Passwords

OmniSync keeps its **own** encrypted password vault, separate from Zen's and Brave's built-in password managers.

- **Capture:** content scripts watch for login submits: a form submit, a click on a sign-in button, or Enter in the password field. This also works for JavaScript logins that have no `<form>`. After the next page loads, a small **"Save password?"** prompt appears (or **"Update password?"** if you already have that login). It offers *Save*, *Never for this site* or *Not now*. The prompt is also in the toolbar popup. If the vault is locked, the captured login waits in memory for 5 minutes while you unlock.
- **Why not `webRequest`:** on Chromium MV3, `webRequest` can only observe requests and can't reliably see what you typed. Many logins are also sent as JSON by JavaScript. Watching the page works the same in Brave and Zen.
- **Autofill:** focus a login field and an OmniSync dropdown lists the logins saved for that site; pick one to fill. The popup also has a **Fill** button for each login on the current site.
- **Vault page:** search, show, copy, add, edit and delete logins, and import CSVs. Open it from the popup with **Password vault**.
- **Safety rules:**
  - A login fills only on its exact site. `www.` is ignored; other subdomains don't match.
  - An https login never fills on plain http.
  - The website's own scripts can't read OmniSync's prompt or dropdown (closed shadow DOM).
  - A password is released only after a real user click, and only for the site the *browser* says the page is on.
  - Content scripts never receive the vault or the list of passwords.
  - On disk, the local vault copy stays encrypted, the same as on the relay.
- **Dedupe:** one login per site + username. Saving again updates it. If both browsers create the same login independently, the newer one is kept.
- **Not yet:** password generator, TOTP/2FA codes, auto-clearing the clipboard after **Copy**, and save prompts inside embedded frames (capture works there; the prompt shows in the popup). Multi-step logins where the username and password are on separate pages may save without a username.

## Relay server

The relay is Hono + Postgres; without a `DATABASE_URL` it uses embedded PGlite. It's self-hostable on any small server. It enforces last-write-wins atomically per item, and hands out changes in order with a cursor that never skips anything. It also holds a long-poll open so browsers hear about changes within about a second.

| Endpoint | Purpose |
|---|---|
| `POST /v1/accounts` / `POST /v1/sessions` | create account / sign in (with `H(username)` and the login key only) |
| `POST /v1/devices` | register this browser |
| `POST /v1/records` | push encrypted items (per-item LWW; older writes come back as `stale` with the winner) |
| `GET /v1/records?since=` | pull encrypted items newer than the cursor |
| `GET /v1/records/wait?since=` | long-poll for "something changed" |

Server logs contain only `METHOD /route-pattern status latency`. There is no analytics anywhere, and the Firefox manifest declares `data_collection_permissions: none`.

## Repository layout

```
packages/
  crypto/      key derivation + encryption (the security-critical code, isolated, 23 tests)
  protocol/    API types and the last-write-wins rule shared by relay and extension
  backend/     relay server
  extension/
    wxt.config.ts            one config, two builds (Brave / Zen)
    entrypoints/background.ts   sync engine host, key custody, message routing
    entrypoints/content.ts      password capture + autofill dropdown (runs in web pages)
    entrypoints/popup/          toolbar popup
    entrypoints/vault/          full-page password vault + CSV import
    src/bookmarks/engine.ts     bookmark merge (snapshot diff, root mapping, dedupe)
    src/passwords/              vault (encrypted replica), CSV parser, site matching, form detection
    e2e/                        real-browser tests
```

## Verification

| What | Result |
|---|---|
| Unit + integration (`pnpm test`) | **104 passing**: crypto 23, protocol 4, relay 21 (also run on real Postgres 16), extension 56 |
| Extension tests | A Zen-shaped (Firefox) bookmark tree and a Brave-shaped (Chromium) tree syncing through the real relay with real encryption. Covers moves in both directions, Chromium index behaviour, dedupe, conflicts, clock skew, tampering, 650-item batches, plus the password vault (sync, LWW, dedupe, CSV import, no plaintext at rest) |
| `e2e/two-profiles.ts` (real Chromium, two profiles) | add, rename, nested folders, in-folder reorder, cross-folder move, same-URL dedupe and delete all propagate. Typical delay **1.3 s**; relay holds only ciphertext |
| `e2e/passwords.ts` (real Chromium, two profiles) | form login captured → prompt after navigation → saved → synced (1.3 s) → dropdown autofill → popup Fill → **refused on a different host** → JavaScript-only login captured → update without duplicate → Brave CSV import → no plaintext on the relay or in extension storage |
| Zen build | `web-ext lint`: 0 errors, 0 warnings, 0 notices |

**Not yet verified in the real target browsers:**

- The automated tests ran in Chromium (the engine Brave is built on). Brave itself and Zen itself weren't available to run here: the Zen download worked, but running a downloaded binary was blocked in this environment, and Brave's packages weren't reachable.
- The Firefox side was tested with a Firefox-shaped bookmark tree and the linter, not a live Zen. One Firefox detail is unverified: that `bookmarks.move()` uses the final index.
- The first thing to do is run through Quick start with real Zen and Brave and watch the bookmark manager and a login page.

## Next

1. **Phase 1 in real Zen and Brave:** confirm bookmarks and passwords end to end, especially Zen's site-access prompt and in-folder reordering.
2. **History (stretch):** read via each browser's `history` API, batched. It would have an on/off switch per browser and probably sync only the last 30 days unless full history performs well. History is the most sensitive and the largest data set, so it should be opt-in.
