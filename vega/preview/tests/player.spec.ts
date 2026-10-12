import { expect, test } from '@playwright/test';
import { fakePlex, press, signedIn } from './fake';

test('A film plays: the controls, a scrub with its preview, the panels, then Back to its page', async ({ page }) => {
  page.on('pageerror', (e) => console.log('pageerror:', e.message));
  await fakePlex(page, { many: true });
  await signedIn(page);
  await page.goto('/');
  await expect(page.getByText('Continue Watching')).toBeVisible({ timeout: 20_000 });
  await press(page, 'ArrowDown');
  await page.waitForTimeout(400);
  await press(page, 'ArrowRight');
  await page.waitForTimeout(400);
  await expect(page.locator(':focus')).toContainText('Low Orbit');
  await press(page, 'Enter');
  await expect(page.getByText('Mara Quinn')).toBeVisible({ timeout: 10_000 });
  await page.waitForTimeout(500);
  // Play, on the film's page.
  await press(page, 'Enter');
  await expect(page.getByText('Low Orbit').first()).toBeVisible({ timeout: 10_000 });
  await page.waitForTimeout(1200);
  await page.screenshot({ path: 'shots/player-controls.png' });
  // Up to the bar, and along it: the preview above.
  await press(page, 'ArrowUp');
  await page.waitForTimeout(200);
  await press(page, 'ArrowRight', 4);
  await page.waitForTimeout(250);
  await page.screenshot({ path: 'shots/player-scrub.png' });
  await page.waitForTimeout(1200);
  // Down to Play, along to Subtitles, and its panel.
  await press(page, 'ArrowDown');
  await page.waitForTimeout(200);
  await press(page, 'ArrowRight', 2);
  await page.waitForTimeout(200);
  await press(page, 'Enter');
  await expect(page.getByText('Increase size')).toBeVisible();
  await page.waitForTimeout(400);
  await page.screenshot({ path: 'shots/player-subtitles.png' });
  // English subtitles on: drawn over the picture.
  await press(page, 'ArrowDown');
  await press(page, 'Enter');
  await page.waitForTimeout(800);
  await page.screenshot({ path: 'shots/player-subtitles-on.png' });
  // Chapters.
  await press(page, 'ArrowLeft');
  await press(page, 'Enter');
  await expect(page.getByText('Coming home')).toBeVisible();
  await page.waitForTimeout(400);
  await page.screenshot({ path: 'shots/player-chapters.png' });
  await press(page, 'Escape');
  await page.waitForTimeout(300);
  // Controls away, then out of the player, back on the film's page.
  await press(page, 'Escape');
  await page.waitForTimeout(400);
  await page.screenshot({ path: 'shots/player-clean.png' });
  await press(page, 'Escape');
  await expect(page.getByText('Mara Quinn')).toBeVisible({ timeout: 10_000 });
  await page.waitForTimeout(600);
  await page.screenshot({ path: 'shots/player-back.png' });
});
