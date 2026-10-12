import { expect, test } from '@playwright/test';
import { fakePlex, press, signedIn } from './fake';

test('Movies: the tab home, the whole library with its chips and rail, sorting, collections', async ({ page }) => {
  page.on('pageerror', (e) => console.log('pageerror:', e.message));
  await fakePlex(page, { big: true });
  await signedIn(page);
  await page.goto('/');
  await expect(page.getByText('Continue Watching')).toBeVisible({ timeout: 20_000 });
  // Along the tabs to Movies: its own home of rows.
  await press(page, 'ArrowRight');
  await expect(page.getByText('Recently Added')).toBeVisible({ timeout: 10_000 });
  await page.waitForTimeout(600);
  await page.screenshot({ path: 'shots/library-home.png' });
  await press(page, 'ArrowDown');
  await page.waitForTimeout(400);
  await page.screenshot({ path: 'shots/library-home-down.png' });
  // The tab's menu, then the whole library.
  await press(page, 'ArrowUp');
  await press(page, 'Enter');
  await expect(page.getByText('In Movies')).toBeVisible();
  await page.waitForTimeout(400);
  await page.screenshot({ path: 'shots/library-menu.png' });
  await press(page, 'ArrowDown');
  await press(page, 'Enter');
  await expect(page.getByText('Sort · A–Z')).toBeVisible({ timeout: 10_000 });
  await page.waitForTimeout(800);
  await page.screenshot({ path: 'shots/library-grid.png' });
  await press(page, 'ArrowDown');
  await press(page, 'ArrowDown');
  await page.waitForTimeout(400);
  await page.screenshot({ path: 'shots/library-grid-down.png' });
  // The rail: right along the row to its end, then once more.
  await press(page, 'ArrowRight', 6);
  await page.waitForTimeout(300);
  await page.screenshot({ path: 'shots/library-rail.png' });
  // Up to the chips, and the sort list.
  await press(page, 'ArrowLeft');
  await press(page, 'ArrowUp', 4);
  await page.waitForTimeout(300);
  await page.screenshot({ path: 'shots/library-chips.png' });
  // Down onto the sort chip, and its list.
  await press(page, 'ArrowDown');
  await page.waitForTimeout(300);
  await press(page, 'Enter');
  await expect(page.getByText('Sort by')).toBeVisible();
  await page.waitForTimeout(400);
  await page.screenshot({ path: 'shots/library-sort.png' });
  await press(page, 'ArrowDown');
  await press(page, 'Enter');
  await expect(page.getByText('Sort · Recently added')).toBeVisible({ timeout: 10_000 });
  await page.waitForTimeout(500);
  await page.screenshot({ path: 'shots/library-sorted.png' });
  // Back on the chip that asked, as on the Fire TV.
  await expect(page.locator(':focus')).toHaveText('Sort · Recently added');
  // The collections, from the tab's menu.
  await press(page, 'ArrowUp');
  await press(page, 'Enter');
  await expect(page.getByText('In Movies')).toBeVisible();
  await press(page, 'ArrowDown', 2);
  await press(page, 'Enter');
  await expect(page.getByText('Space Films').last()).toBeVisible({ timeout: 10_000 });
  await page.waitForTimeout(500);
  await page.screenshot({ path: 'shots/library-collections.png' });
  // A collection's own page.
  await press(page, 'Enter');
  await page.waitForTimeout(1500);
  await expect(page.getByText('In this collection')).toBeVisible({ timeout: 10_000 });
  await page.waitForTimeout(600);
  await page.screenshot({ path: 'shots/collection.png' });
  await expect(page.locator(':focus')).toContainText('Low Orbit');
  await press(page, 'Escape');
  await page.waitForTimeout(500);
  await expect(page.locator(':focus')).toContainText('Space Films');
  // Home's playlist, and its page.
  await press(page, 'ArrowUp');
  await press(page, 'ArrowLeft');
  await expect(page.getByText('Continue Watching')).toBeVisible({ timeout: 10_000 });
  await page.waitForTimeout(500);
  for (let i = 0; i < 10; i++) {
    await press(page, 'ArrowDown');
    await page.waitForTimeout(150);
    if ((await page.locator(':focus').textContent())?.includes('Weekend Films')) break;
  }
  await page.waitForTimeout(500);
  await page.screenshot({ path: 'shots/home-playlists.png' });
  await press(page, 'Enter');
  await expect(page.getByText('Shuffle')).toBeVisible({ timeout: 10_000 });
  await page.waitForTimeout(600);
  await page.screenshot({ path: 'shots/playlist.png' });
});
