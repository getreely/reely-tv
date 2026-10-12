import { expect, test } from '@playwright/test';
import { fakePanel, fakePlex, press, signedIn, signedInLive } from './fake';

test('Starting again: last time\'s server and Home at once, without waiting on plex.tv', async ({ page }) => {
  page.on('pageerror', (e) => console.log('pageerror:', e.message));
  await fakePlex(page);
  await signedIn(page);
  await page.goto('/');
  await expect(page.getByText('Continue Watching')).toBeVisible({ timeout: 20_000 });
  await page.waitForTimeout(1_500);
  // plex.tv slow to say which servers there are: Home is there regardless.
  await page.route('https://plex.tv/api/v2/resources**', async (route) => {
    await new Promise((r) => setTimeout(r, 15_000));
    await route.fallback();
  });
  await page.reload();
  await expect(page.getByText('Continue Watching')).toBeVisible({ timeout: 3_000 });
  await expect(page.getByText('Finding a Plex server')).toHaveCount(0);
  await page.screenshot({ path: 'shots/startup-again.png' });
});

test('First start, Plex still looking: the tabs are there, and Live TV with them', async ({ page }) => {
  page.on('pageerror', (e) => console.log('pageerror:', e.message));
  await fakePlex(page);
  await fakePanel(page);
  await signedIn(page);
  await signedInLive(page);
  await page.route('https://plex.tv/api/v2/resources**', async (route) => {
    await new Promise((r) => setTimeout(r, 20_000));
    await route.fallback();
  });
  await page.goto('/');
  await expect(page.getByText('Finding a Plex server')).toBeVisible({ timeout: 10_000 });
  await page.waitForTimeout(500);
  await page.screenshot({ path: 'shots/startup-finding.png' });
  await press(page, 'ArrowRight', 3);
  await expect(page.getByText('5 categories')).toBeVisible({ timeout: 10_000 });
  await page.screenshot({ path: 'shots/startup-live.png' });
});
