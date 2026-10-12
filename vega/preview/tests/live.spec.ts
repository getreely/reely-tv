import { expect, test } from '@playwright/test';
import { fakePanel, fakePlex, press, signedIn, signedInLive } from './fake';

test('Live TV: categories as tiles, then a category opens into its guide', async ({ page }) => {
  page.on('pageerror', (e) => console.log('pageerror:', e.message));
  await fakePlex(page);
  await fakePanel(page);
  await signedIn(page);
  await signedInLive(page);
  await page.goto('/');
  await expect(page.getByText('Continue Watching')).toBeVisible({ timeout: 20_000 });
  // Along the tabs to Live TV, as the remote does.
  await press(page, 'ArrowRight', 3);
  await expect(page.getByText('5 categories')).toBeVisible({ timeout: 10_000 });
  await page.waitForTimeout(500);
  await page.screenshot({ path: 'shots/live-categories-tabs.png' });
  await press(page, 'ArrowDown');
  await page.waitForTimeout(400);
  await page.screenshot({ path: 'shots/live-categories.png' });
  await press(page, 'Enter');
  await expect(page.getByText('The Evening Report').first()).toBeVisible({ timeout: 10_000 });
  await page.waitForTimeout(600);
  await page.screenshot({ path: 'shots/live-guide.png' });
  await press(page, 'ArrowDown');
  await page.waitForTimeout(300);
  await page.screenshot({ path: 'shots/live-guide-down.png' });
  await press(page, 'ArrowUp');
  await press(page, 'Enter');
  await page.waitForTimeout(500);
  await page.screenshot({ path: 'shots/live-player.png' });
  await press(page, 'Enter');
  await page.waitForTimeout(400);
  await page.screenshot({ path: 'shots/live-player-controls.png' });
});
