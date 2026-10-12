import { expect, test } from '@playwright/test';
import { fakePlex, press, signedIn } from './fake';

test('An episode: Skip Intro, then Up Next when the credits start', async ({ page }) => {
  page.on('pageerror', (e) => console.log('pageerror:', e.message));
  await fakePlex(page, { many: true });
  await signedIn(page);
  await page.goto('/');
  await expect(page.getByText('Continue Watching')).toBeVisible({ timeout: 20_000 });
  await press(page, 'ArrowDown');
  await page.waitForTimeout(400);
  await press(page, 'Enter');
  await expect(page.getByText('Season 1').first()).toBeVisible({ timeout: 10_000 });
  await page.waitForTimeout(600);
  // OK on the episode the page landed on: it plays, from the start of its intro.
  await press(page, 'Enter');
  await expect(page.getByText('Skip Intro')).toBeVisible({ timeout: 10_000 });
  await page.waitForTimeout(800);
  await page.screenshot({ path: 'shots/player-skip-intro.png' });
  // The controls go; Skip Intro stays, bottom right, with the cursor.
  await press(page, 'Escape');
  await page.waitForTimeout(400);
  await page.screenshot({ path: 'shots/player-skip-intro-alone.png' });
  await press(page, 'Enter');
  await expect(page.getByText('Skip Intro')).toBeHidden({ timeout: 5_000 });
  // On past the credits' start: Up Next.
  await press(page, 'ArrowRight', 4);
  await expect(page.getByText('UP NEXT')).toBeVisible({ timeout: 10_000 });
  await page.waitForTimeout(800);
  await page.screenshot({ path: 'shots/player-up-next.png' });
  // Watch credits: back to the picture.
  await press(page, 'ArrowRight');
  await press(page, 'Enter');
  await expect(page.getByText('UP NEXT')).toBeHidden({ timeout: 5_000 });
  await page.waitForTimeout(400);
  await page.screenshot({ path: 'shots/player-credits.png' });
  // With the credits back, Next Episode has the cursor: one press goes on.
  await expect(page.locator(':focus')).toHaveText('Next Episode');
});

