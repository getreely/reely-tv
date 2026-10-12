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

test('Updates: a newer Vega release said at start, with how to install it, and in Settings', async ({ page }) => {
  page.on('pageerror', (e) => console.log('pageerror:', e.message));
  await fakePlex(page);
  await signedIn(page);
  // GitHub's releases: a newer Vega one, a test build and another app's, which are passed over.
  await page.route('https://api.github.com/**', (route) => route.fulfill({
    status: 200, contentType: 'application/json', headers: { 'Access-Control-Allow-Origin': '*' },
    body: JSON.stringify([
      { tag_name: 'v0.70.0', html_url: 'https://github.com/getreely/reely-tv/releases/tag/v0.70.0', assets: [] },
      { tag_name: 'vega-test', prerelease: true, html_url: 'x', assets: [] },
      { tag_name: 'vega-v0.59.0', html_url: 'https://github.com/getreely/reely-tv/releases/tag/vega-v0.59.0', assets: [{ name: 'reely-vega-armv7.vpkg', size: 21_400_000 }] },
      { tag_name: 'vega-v0.58.0', html_url: 'https://github.com/getreely/reely-tv/releases/tag/vega-v0.58.0', assets: [] },
    ]),
  }));
  await page.goto('/');
  await expect(page.getByText('Continue Watching')).toBeVisible({ timeout: 20_000 });
  await expect(page.getByText('A new version is available')).toBeVisible({ timeout: 15_000 });
  await expect(page.getByText('Version 0.59.0 is ready (21 MB). You have 0.58.4.')).toBeVisible();
  await page.waitForTimeout(400);
  await page.screenshot({ path: 'shots/update-prompt.png' });
  // The cursor is on Later, not left on the poster behind.
  expect(await page.evaluate(() => document.activeElement?.textContent ?? '')).toBe('Later');
  await press(page, 'Enter');
  await expect(page.getByText('A new version is available')).toHaveCount(0);

  // Settings, About: the version, and the update it found.
  await press(page, 'ArrowRight', 6);
  await page.waitForTimeout(300);
  await press(page, 'Enter');
  await expect(page.getByText('Playback mode')).toBeVisible({ timeout: 10_000 });
  for (let i = 0; i < 10 && !(await page.getByText('Take the tour').isVisible()); i++) {
    await press(page, 'ArrowDown');
    await page.waitForTimeout(250);
  }
  await expect(page.getByText('Version 0.59.0', { exact: true })).toBeVisible();
  await page.waitForTimeout(300);
  await page.screenshot({ path: 'shots/update-settings.png' });
});
