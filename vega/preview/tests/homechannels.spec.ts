import { expect, test } from '@playwright/test';
import { fakePanel, fakePlex, press, signedIn, signedInLive } from './fake';

test('Home: Your Channels, the favorites and the ones watched lately, and one played from there', async ({ page }) => {
  page.on('pageerror', (e) => console.log('pageerror:', e.message));
  await fakePlex(page);
  await fakePanel(page);
  await signedIn(page);
  await signedInLive(page);
  await page.addInitScript(() => {
    if (!localStorage.getItem('reely:favorites')) localStorage.setItem('reely:favorites', JSON.stringify([101]));
  });
  await page.goto('/');
  await expect(page.getByText('Continue Watching')).toBeVisible({ timeout: 20_000 });
  await expect(page.getByText('Your Channels')).toBeAttached({ timeout: 10_000 });
  await expect(page.getByText('Favorite', { exact: true })).toBeAttached();
  await page.waitForTimeout(400);
  await page.screenshot({ path: 'shots/home-channels.png' });
  // Down to its card and OK: the channel plays, and Back comes home to it.
  // The last row: down until the cursor's on its card.
  for (let i = 0; i < 14; i++) {
    if (await page.evaluate(() => document.activeElement?.textContent?.includes('News 24') ?? false)) break;
    await press(page, 'ArrowDown');
    await page.waitForTimeout(200);
  }
  expect(await page.evaluate(() => document.activeElement?.textContent?.includes('News 24') ?? false)).toBe(true);
  await page.screenshot({ path: 'shots/home-channels-row.png' });
  await press(page, 'Enter');
  await page.waitForTimeout(800);
  await press(page, 'Enter');
  await expect(page.getByText('LIVE')).toBeVisible({ timeout: 5_000 });
  await press(page, 'Escape', 2);
  await expect(page.getByText('Your Channels')).toBeVisible({ timeout: 5_000 });
  expect(await page.evaluate(() => document.activeElement?.textContent?.includes('News 24') ?? false)).toBe(true);
});

test('Settings, About: the playback log, a page of what the app has said', async ({ page }) => {
  await fakePlex(page, { many: true });
  await signedIn(page);
  await page.goto('/');
  await expect(page.getByText('Continue Watching')).toBeVisible({ timeout: 20_000 });
  await page.evaluate(() => console.warn('[reely] player 1 opens example .ts'));
  // Up to the tabs, along to the gear, OK; down the sections to About, the last.
  await press(page, 'ArrowRight', 6);
  await page.waitForTimeout(300);
  await press(page, 'Enter');
  await expect(page.getByText('Playback mode')).toBeVisible({ timeout: 10_000 });
  await press(page, 'ArrowDown', 6);
  await expect(page.getByText('Playback log').first()).toBeVisible({ timeout: 10_000 });
  await press(page, 'ArrowRight');
  for (let i = 0; i < 12 && !(await page.getByText('player 1 opens example .ts').isVisible()); i++) {
    await press(page, 'ArrowDown');
    await page.waitForTimeout(150);
    const onLog = await page.evaluate(() => document.activeElement?.textContent?.includes('Playback log') ?? false);
    if (onLog) await press(page, 'Enter');
  }
  await expect(page.getByText('player 1 opens example .ts')).toBeVisible();
  await page.screenshot({ path: 'shots/settings-log.png' });
});
