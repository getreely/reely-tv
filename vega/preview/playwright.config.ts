import { defineConfig } from '@playwright/test';

// Vega's screens at the size Vega lays them out, 1920 by 1080, for screenshots and checks.
export default defineConfig({
  testDir: './tests',
  timeout: 60_000,
  use: {
    baseURL: 'http://localhost:5199',
    viewport: { width: 1920, height: 1080 },
    launchOptions: { executablePath: '/opt/pw-browsers/chromium' },
  },
  webServer: { command: 'npx vite --port 5199 --strictPort', url: 'http://localhost:5199', reuseExistingServer: true, timeout: 120_000 },
});
