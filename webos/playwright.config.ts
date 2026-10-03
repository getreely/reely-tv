import { defineConfig } from "@playwright/test";

// The built app, served as the TV would open it, at the TV's size.
export default defineConfig({
  testDir: "tests/e2e",
  timeout: 30_000,
  use: {
    baseURL: "http://127.0.0.1:4173",
    viewport: { width: 1920, height: 1080 },
  },
  webServer: {
    command: "npx vite build && npx vite preview --host 127.0.0.1 --port 4173 --strictPort",
    url: "http://127.0.0.1:4173",
    reuseExistingServer: false,
    timeout: 60_000,
  },
});
