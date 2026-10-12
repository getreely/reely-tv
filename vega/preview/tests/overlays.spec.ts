import { expect, test } from '@playwright/test';
import { fakePlex, press, signedIn } from './fake';

test('The tour, a step at a time, the remote drawn with its button lit', async ({ page }) => {
  page.on('pageerror', (e) => console.log('pageerror:', e.message));
  await fakePlex(page, { many: true, tour: true });
  await signedIn(page);
  await page.goto('/');
  await expect(page.getByText('Welcome to Reely')).toBeVisible({ timeout: 20_000 });
  await page.waitForTimeout(500);
  await page.screenshot({ path: 'shots/tour.png' });
  await press(page, 'Enter');
  await press(page, 'Enter');
  await expect(page.getByText('Hold OK for more')).toBeVisible();
  await page.waitForTimeout(400);
  await page.screenshot({ path: 'shots/tour-hold.png' });
  await press(page, 'ArrowRight', 2);
  await press(page, 'Enter');
  await expect(page.getByText('Welcome to Reely')).toBeHidden();
});

test("Who's watching: the profiles, and a PIN pad for one with a PIN", async ({ page }) => {
  page.on('pageerror', (e) => console.log('pageerror:', e.message));
  await fakePlex(page, { many: true, home: true });
  await signedIn(page);
  await page.goto('/');
  await expect(page.getByText('Continue Watching')).toBeVisible({ timeout: 20_000 });
  await page.waitForTimeout(500);
  // Settings, Plex, the profile.
  await press(page, 'ArrowRight', 6);
  await press(page, 'Enter');
  await expect(page.getByText('Playback mode')).toBeVisible();
  await press(page, 'ArrowDown', 5);
  await expect(page.getByText('Sign out of Plex')).toBeVisible();
  await page.waitForTimeout(300);
  await page.screenshot({ path: 'shots/settings-plex-home.png' });
  await press(page, 'ArrowRight');
  await page.waitForTimeout(200);
  await press(page, 'Enter');
  await expect(page.getByText("Who's watching?")).toBeVisible({ timeout: 10_000 });
  await page.waitForTimeout(500);
  await page.screenshot({ path: 'shots/profiles.png' });
  await press(page, 'ArrowRight');
  await press(page, 'Enter');
  await expect(page.getByText('Enter the PIN for this profile')).toBeVisible();
  await page.waitForTimeout(400);
  await page.screenshot({ path: 'shots/profiles-pin.png' });
});

test('The screensaver after the minutes set, and any button puts it away', async ({ page }) => {
  page.on('pageerror', (e) => console.log('pageerror:', e.message));
  await page.clock.install();
  await fakePlex(page, { many: true });
  await signedIn(page);
  await page.goto('/');
  await expect(page.getByText('Continue Watching')).toBeVisible({ timeout: 20_000 });
  await page.clock.fastForward('04:00');
  await page.clock.runFor(6_000);
  await page.waitForTimeout(500);
  await page.clock.runFor(2_500);
  await page.waitForTimeout(500);
  await page.screenshot({ path: 'shots/screensaver.png' });
  await press(page, 'ArrowDown');
  await page.waitForTimeout(300);
  await expect(page.getByText('Continue Watching')).toBeVisible();
});
