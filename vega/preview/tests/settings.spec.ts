import { expect, test } from '@playwright/test';
import { fakePlex, press, signedIn } from './fake';

test('Settings: the sections down the left, each shown as the cursor reaches it, a list to choose from', async ({ page }) => {
  page.on('pageerror', (e) => console.log('pageerror:', e.message));
  await fakePlex(page, { many: true });
  await signedIn(page);
  await page.goto('/');
  await expect(page.getByText('Continue Watching')).toBeVisible({ timeout: 20_000 });
  // Up to the tabs, along to the gear, OK.
  await press(page, 'ArrowRight', 6);
  await page.waitForTimeout(300);
  await press(page, 'Enter');
  await expect(page.getByText('Playback mode')).toBeVisible({ timeout: 10_000 });
  await page.waitForTimeout(500);
  await page.screenshot({ path: 'shots/settings.png' });
  // Into the settings, and the list for Playback mode.
  await press(page, 'ArrowRight');
  await page.waitForTimeout(300);
  await page.screenshot({ path: 'shots/settings-in.png' });
  await press(page, 'Enter');
  await expect(page.getByText('Always convert')).toBeVisible();
  await page.waitForTimeout(400);
  await page.screenshot({ path: 'shots/settings-choice.png' });
  await press(page, 'Escape');
  await page.waitForTimeout(300);
  // Back to the sections, down to Plex, and About.
  await press(page, 'Escape');
  await page.waitForTimeout(300);
  await press(page, 'ArrowDown', 5);
  await expect(page.getByText('Sign out of Plex')).toBeVisible();
  await page.waitForTimeout(400);
  await page.screenshot({ path: 'shots/settings-plex.png' });
  await press(page, 'ArrowDown');
  await expect(page.getByText('Take the tour')).toBeVisible();
  await page.waitForTimeout(300);
  await page.screenshot({ path: 'shots/settings-about.png' });
});
