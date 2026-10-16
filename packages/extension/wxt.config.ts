import { defineConfig } from 'wxt';

// One codebase → Chromium MV3 (`wxt build`) and Firefox (`wxt build -b firefox`, incl. Firefox for Android).
export default defineConfig({
  srcDir: '.',
  // MV3 on Firefox too: same storage.session + CSP semantics everywhere (Firefox 115+, Firefox Android 120+).
  manifestVersion: 3,
  manifest: ({ browser }) => ({
    name: 'OmniSync',
    description: 'End-to-end encrypted bookmark sync across Chromium and Firefox browsers.',
    permissions: ['bookmarks', 'storage', 'alarms'],
    // Local dev servers are allowed up front; any other sync server is requested at sign-in.
    host_permissions: ['http://localhost/*', 'http://127.0.0.1/*'],
    optional_host_permissions: ['https://*/*', 'http://*/*'],
    // libsodium ships as WebAssembly; nothing else is relaxed.
    content_security_policy: {
      extension_pages: "script-src 'self' 'wasm-unsafe-eval'; object-src 'self'",
    },
    ...(browser === 'firefox' && {
      browser_specific_settings: {
        gecko: {
          id: 'omnisync@omnisync.dev',
          strict_min_version: '115.0',
          // No telemetry, no analytics.
          data_collection_permissions: { required: ['none'] },
        },
        gecko_android: { strict_min_version: '120.0' },
      },
    }),
  }),
});
