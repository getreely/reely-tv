import { expect, test } from '@playwright/test';
import { fakePlex, press, signedIn } from './fake';

test('Settings: what this TV plays as it is, as it answered, and checking again', async ({ page }) => {
  page.on('pageerror', (e) => console.log('pageerror:', e.message));
  await fakePlex(page, { many: true });
  await signedIn(page);
  await page.goto('/');
  await expect(page.getByText('Continue Watching')).toBeVisible({ timeout: 20_000 });
  await press(page, 'ArrowRight', 6);
  await page.waitForTimeout(300);
  await press(page, 'Enter');
  await expect(page.getByText('Playback mode')).toBeVisible({ timeout: 10_000 });
  await page.waitForTimeout(500);
  await press(page, 'ArrowRight');
  await page.waitForTimeout(300);
  // Down past every setting to the last, Check again.
  for (let i = 0; i < 30; i++) {
    const at = await page.evaluate(() => document.activeElement?.textContent ?? '');
    if (at.startsWith('Check again')) break;
    await press(page, 'ArrowDown');
    await page.waitForTimeout(120);
  }
  await page.waitForTimeout(600);
  await expect(page.getByText('This TV', { exact: true })).toBeVisible();
  await expect(page.getByText(/Plays as it is: H\.264, HEVC, HEVC 4K, VP9\./)).toBeVisible({ timeout: 10_000 });
  await expect(page.getByText(/Converted by Plex: AV1\./)).toBeVisible();
  await expect(page.getByText(/Converted by Plex: DTS, Dolby TrueHD\./)).toBeVisible();
  await page.screenshot({ path: 'shots/settings-this-tv.png' });
  await press(page, 'Enter');
  await page.waitForTimeout(300);
  await expect(page.getByText(/Plays as it is: H\.264/)).toBeVisible();
});
