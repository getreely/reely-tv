import { expect, test } from '@playwright/test';
import { fakePlex, press, signedIn } from './fake';

test('A show\'s page: its episode, seasons, Play, the rail; then a film with its cast and an actor', async ({ page }) => {
  page.on('pageerror', (e) => console.log('pageerror:', e.message));
  await fakePlex(page, { many: true });
  await signedIn(page);
  await page.goto('/');
  await expect(page.getByText('Continue Watching')).toBeVisible({ timeout: 20_000 });
  // Down into Continue Watching, and OK on Northbound's episode.
  await press(page, 'ArrowDown');
  await page.waitForTimeout(300);
  await press(page, 'Enter');
  await expect(page.getByText('Season 1').first()).toBeVisible({ timeout: 10_000 });
  await page.waitForTimeout(800);
  await page.screenshot({ path: 'shots/detail-show.png' });
  await press(page, 'ArrowUp');
  await page.waitForTimeout(300);
  await page.screenshot({ path: 'shots/detail-show-play.png' });
  await press(page, 'ArrowDown', 3);
  await page.waitForTimeout(400);
  await page.screenshot({ path: 'shots/detail-show-down.png' });
  // Back to Home, along to a film in Continue Watching.
  await press(page, 'Escape');
  await page.waitForTimeout(500);
  await page.screenshot({ path: 'shots/detail-back-home.png' });
  await press(page, 'ArrowRight');
  await page.waitForTimeout(300);
  await press(page, 'Enter');
  await expect(page.getByText('Mara Quinn')).toBeVisible({ timeout: 10_000 });
  await page.waitForTimeout(800);
  await page.screenshot({ path: 'shots/detail-film.png' });
  await press(page, 'ArrowDown', 3);
  await page.waitForTimeout(400);
  await page.screenshot({ path: 'shots/detail-film-related.png' });
  await press(page, 'ArrowUp');
  await page.waitForTimeout(400);
  await page.screenshot({ path: 'shots/detail-film-cast.png' });
  await press(page, 'Enter');
  await expect(page.getByText(/on your server|Nothing else/)).toBeVisible({ timeout: 10_000 });
  await page.waitForTimeout(600);
  await page.screenshot({ path: 'shots/person.png' });
  await press(page, 'Escape');
  await expect(page.getByText('Mara Quinn')).toBeVisible({ timeout: 10_000 });
  await page.waitForTimeout(500);
  await page.screenshot({ path: 'shots/detail-film-back.png' });
  // Back on the actor it was opened from.
  await expect(page.locator(':focus')).toContainText('Ana Orbit');
});
