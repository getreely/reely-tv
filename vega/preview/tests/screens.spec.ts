import { expect, test } from '@playwright/test';
import { fakePlex, press, signedIn } from './fake';

test('Home: the hero, and rows under it', async ({ page }) => {
  await fakePlex(page);
  await signedIn(page);
  await page.goto('/');
  await expect(page.getByText('Continue Watching')).toBeVisible({ timeout: 20_000 });
  await page.waitForTimeout(800);
  await page.screenshot({ path: 'shots/home-tabs.png' });
  await press(page, 'ArrowDown');
  await page.waitForTimeout(600);
  await page.screenshot({ path: 'shots/home.png' });
});
