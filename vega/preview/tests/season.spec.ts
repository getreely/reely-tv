import { expect, test } from '@playwright/test';
import { fakePlex, press, SERVER, signedIn } from './fake';

test('An episode from Continue Watching opens its show on that season, at that episode', async ({ page }) => {
  page.on('pageerror', (e) => console.log('pageerror:', e.message));
  await fakePlex(page);
  // Northbound with a second season, and Season 2's second episode the one under way.
  const json = (body: unknown) => ({ status: 200, contentType: 'application/json', headers: { 'Access-Control-Allow-Origin': '*' }, body: JSON.stringify(body) });
  const ep = (n: number) => ({
    ratingKey: `e2${n}`, type: 'episode', title: `Second Season ${n}`, index: n, parentIndex: 2, parentRatingKey: 's2', grandparentRatingKey: 'show1',
    grandparentTitle: 'Northbound', duration: 60_000, art: '/art/show1', thumb: `/thumb/e2${n}`, summary: `Episode ${n} of the second season.`,
  });
  await page.route(`${SERVER}/**`, async (route) => {
    const path = new URL(route.request().url()).pathname;
    if (path === '/hubs') return route.fulfill(json({ MediaContainer: { Hub: [{ Metadata: [{ ...ep(2), viewOffset: 20_000, lastViewedAt: 5 }] }] } }));
    if (path === '/library/metadata/show1/children') {
      return route.fulfill(json({ MediaContainer: { Metadata: [
        { ratingKey: 's1', type: 'season', title: 'Season 1', index: 1 }, { ratingKey: 's2', type: 'season', title: 'Season 2', index: 2 },
      ] } }));
    }
    if (path === '/library/metadata/s2/children') return route.fulfill(json({ MediaContainer: { Metadata: [ep(1), ep(2), ep(3)] } }));
    if (path === '/library/metadata/e22') return route.fulfill(json({ MediaContainer: { Metadata: [ep(2)] } }));
    return route.fallback();
  });
  await signedIn(page);
  await page.goto('/');
  await expect(page.getByText('Continue Watching')).toBeVisible({ timeout: 20_000 });
  await press(page, 'ArrowDown');
  await page.waitForTimeout(300);
  await press(page, 'Enter');
  await expect(page.getByText('Second Season 2').first()).toBeVisible({ timeout: 10_000 });
  await page.waitForTimeout(800);
  expect(await page.evaluate(() => document.activeElement?.textContent ?? '')).toContain('Second Season 2');
  await page.screenshot({ path: 'shots/season-episode.png' });
});
