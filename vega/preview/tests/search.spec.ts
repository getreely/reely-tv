import { expect, test } from '@playwright/test';
import { fakePlex, fakeReely, press, signedIn } from './fake';

test('Search: the box, then results as posters, an actor', async ({ page }) => {
  page.on('pageerror', (e) => console.log('pageerror:', e.message));
  await fakePlex(page, { many: true });
  await signedIn(page);
  await page.goto('/');
  await expect(page.getByText('Continue Watching')).toBeVisible({ timeout: 20_000 });
  await press(page, 'ArrowLeft');
  await expect(page.getByText('Search movies, shows, people, collections and live channels.')).toBeVisible({ timeout: 10_000 });
  await page.waitForTimeout(400);
  await page.screenshot({ path: 'shots/search-empty.png' });
  await page.getByPlaceholder('Movies, shows, people, collections and channels').fill('orbit');
  await expect(page.getByText('Movies and shows')).toBeVisible({ timeout: 10_000 });
  await page.waitForTimeout(600);
  await page.screenshot({ path: 'shots/search-results.png' });
});

test('Requests: connect to Reely, the rows, a show, its seasons, and asking', async ({ page }) => {
  page.on('pageerror', (e) => console.log('pageerror:', e.message));
  await fakePlex(page, { many: true });
  const reely = await fakeReely(page);
  await signedIn(page);
  await page.goto('/');
  await expect(page.getByText('Continue Watching')).toBeVisible({ timeout: 20_000 });
  await press(page, 'ArrowRight', 4);
  await expect(page.getByText('Ask for movies and shows')).toBeVisible({ timeout: 10_000 });
  await page.waitForTimeout(400);
  await page.screenshot({ path: 'shots/requests-connect.png' });
  await page.getByPlaceholder('reely.example.com').fill('192.168.1.5:8788');
  await page.getByText('Connect', { exact: true }).click();
  await expect(page.getByText('Inception')).toBeVisible({ timeout: 10_000 });
  await page.waitForTimeout(600);
  await page.screenshot({ path: 'shots/requests.png' });
  await page.getByText('Game of Thrones').first().click();
  await expect(page.getByText('Request all seasons')).toBeVisible({ timeout: 10_000 });
  await page.waitForTimeout(400);
  await page.screenshot({ path: 'shots/request-title.png' });
  await page.getByText('Season 2', { exact: true }).click();
  await page.getByText('Season 3', { exact: true }).click();
  await page.getByText('Request 1 season').click();
  await expect(page.getByText(/Requested for TV/)).toBeVisible({ timeout: 10_000 });
  await page.waitForTimeout(400);
  await page.screenshot({ path: 'shots/request-sent.png' });
  expect(reely.asked).toEqual([expect.objectContaining({ kind: 'show', tmdbId: 1399, seasons: [1], libraryId: 3 })]);
});
