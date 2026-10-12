import { test } from '@playwright/test';
import { fakePlex, signedIn } from './fake';

test('debug', async ({ page }) => {
  page.on('console', (m) => console.log('console:', m.type(), m.text().slice(0, 300)));
  page.on('pageerror', (e) => console.log('pageerror:', e.message.slice(0, 500)));
  await fakePlex(page);
  await signedIn(page);
  await page.goto('/');
  await page.waitForTimeout(5000);
  await page.screenshot({ path: 'shots/debug.png' });
});
