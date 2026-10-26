import { defineConfig } from 'wxt';

/**
 * One codebase, two builds:
 *   Brave (Chromium):        wxt build -b chrome         → .output/chrome-mv3
 *   Zen (Firefox engine):    wxt build -b firefox --mv3  → .output/firefox-mv3
 * Both are Manifest V3, so storage.session, the CSP and the background lifecycle rules are the
 * same in both browsers and the extension behaves identically from the user's side.
 */
export default defineConfig({
  srcDir: '.',
  manifest: ({ browser }) => ({
    name: 'OmniSync',
    description: 'End-to-end encrypted bookmark and password sync between Zen and Brave.',
    permissions: ['bookmarks', 'storage', 'alarms', 'activeTab'],
    // Content scripts (password capture/autofill) run on every http(s) site, and the sync server
    // can live anywhere. Brave grants this at install; Zen asks the user, and OmniSync shows an
    // "Allow on all sites" button until it's granted.
    host_permissions: ['<all_urls>'],
    // libsodium ships as WebAssembly; nothing else is relaxed.
    content_security_policy: {
      extension_pages: "script-src 'self' 'wasm-unsafe-eval'; object-src 'self'",
    },
    ...(browser === 'firefox' && {
      browser_specific_settings: {
        gecko: {
          id: 'omnisync@omnisync.dev',
          strict_min_version: '128.0',
          // No telemetry, no analytics.
          data_collection_permissions: { required: ['none'] },
        },
      },
    }),
  }),
});
