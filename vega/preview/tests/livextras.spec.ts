import { expect, test, type Page } from '@playwright/test';
import { fakePanel, fakePlex, press, signedIn, signedInLive } from './fake';

async function onChannel(page: Page) {
  page.on('pageerror', (e) => console.log('pageerror:', e.message));
  await fakePlex(page);
  await fakePanel(page);
  await signedIn(page);
  await signedInLive(page);
  await page.goto('/');
  await expect(page.getByText('Continue Watching')).toBeVisible({ timeout: 20_000 });
  await press(page, 'ArrowRight', 3);
  await expect(page.getByText('5 categories')).toBeVisible({ timeout: 10_000 });
  await press(page, 'ArrowDown');
  await press(page, 'Enter');
  await expect(page.getByText('The Evening Report').first()).toBeVisible({ timeout: 10_000 });
  await page.waitForTimeout(400);
  await press(page, 'Enter');
  await page.waitForTimeout(600);
}

test('A channel: its controls with the bar, the guide over it, a number typed', async ({ page }) => {
  await onChannel(page);
  await press(page, 'Enter');
  await page.waitForTimeout(400);
  // News 24 keeps an archive: the bar spans what's on, and Start over is there.
  await expect(page.getByText('LIVE')).toBeVisible();
  await page.screenshot({ path: 'shots/livex-controls.png' });

  // Rewinding goes back into the archive, with Go live to come back.
  await press(page, ',');
  await page.waitForTimeout(1_200);
  await expect(page.getByText('Go live')).toBeVisible();
  await page.screenshot({ path: 'shots/livex-behind.png' });
  await press(page, 'ArrowUp');
  await page.waitForTimeout(200);
  await press(page, 'Enter');
  await page.waitForTimeout(500);
  await expect(page.getByText('Go live')).toHaveCount(0);
  // The cursor isn't lost with the button: it's on Play.
  await page.waitForTimeout(300);
  expect(await page.evaluate(() => document.activeElement !== document.body)).toBe(true);

  // Start over, the last button on the right: the programme from its beginning, the cursor on Go live.
  await press(page, 'ArrowRight', 7);
  await page.waitForTimeout(200);
  await page.screenshot({ path: 'shots/livex-startover-focus.png' });
  await press(page, 'Enter');
  await page.waitForTimeout(800);
  await expect(page.getByText('Go live')).toBeVisible();
  expect(await page.evaluate(() => document.activeElement?.textContent ?? '')).toContain('Go live');
  await page.screenshot({ path: 'shots/livex-startover.png' });
  await press(page, 'Enter');
  await page.waitForTimeout(500);
  await expect(page.getByText('Go live')).toHaveCount(0);

  // Down with the controls away raises the guide over the picture.
  await press(page, 'Escape');
  await page.waitForTimeout(300);
  await press(page, 'ArrowDown');
  await expect(page.getByText('OK to watch · hold OK for more · up for categories').or(page.getByText('The Evening Report  ·', { exact: false }).first())).toBeVisible();
  await page.waitForTimeout(500);
  await page.screenshot({ path: 'shots/livex-guide.png' });
  await press(page, 'ArrowUp');
  await page.waitForTimeout(300);
  await page.screenshot({ path: 'shots/livex-guide-categories.png' });
  await press(page, 'ArrowDown');
  await page.waitForTimeout(200);
  await press(page, 'ArrowDown');
  await page.waitForTimeout(200);
  await press(page, 'Enter');
  await page.waitForTimeout(500);
  // On World Report now.
  await press(page, 'Enter');
  await expect(page.getByText('102  World Report')).toBeVisible();
  await press(page, 'Escape');
  await page.waitForTimeout(300);

  // A number typed tunes it.
  await press(page, '1');
  await press(page, '0');
  await press(page, '3');
  await expect(page.getByText('UK | Business Now')).toBeVisible();
  await page.screenshot({ path: 'shots/livex-number.png' });
  await press(page, 'Enter');
  await page.waitForTimeout(300);
  await press(page, 'Enter');
  await expect(page.getByText('103  UK | Business Now')).toBeVisible();
  await page.screenshot({ path: 'shots/livex-tuned.png' });
});

test('Multiview: the tile menu, a second channel and a third, the spare cell, and Back closing one', async ({ page }) => {
  await onChannel(page);
  await press(page, 'm');
  await expect(page.getByText('Add another channel')).toBeVisible();
  await page.waitForTimeout(400);
  await page.screenshot({ path: 'shots/livex-tilemenu.png' });
  await press(page, 'Enter');
  await page.waitForTimeout(400);
  await expect(page.getByText('Add — News 24')).toBeVisible();
  await press(page, 'ArrowDown');
  await page.waitForTimeout(200);
  await page.screenshot({ path: 'shots/livex-add.png' });
  await press(page, 'Enter');
  await page.waitForTimeout(800);
  await page.screenshot({ path: 'shots/livex-two.png' });

  // A third, from the tile menu again; with three up the grid offers a spare cell.
  await press(page, 'm');
  await page.waitForTimeout(300);
  await press(page, 'ArrowDown');
  await press(page, 'Enter');
  await page.waitForTimeout(400);
  await press(page, 'ArrowDown', 2);
  await press(page, 'Enter');
  await page.waitForTimeout(800);
  await expect(page.getByText('Add a channel')).toBeVisible();
  await page.screenshot({ path: 'shots/livex-three.png' });

  // Over to the second channel, heard and outlined; Back closes it.
  await press(page, 'ArrowRight');
  await page.waitForTimeout(300);
  await page.screenshot({ path: 'shots/livex-three-right.png' });
  await press(page, 'Escape');
  await page.waitForTimeout(500);
  await expect(page.getByText('Add a channel')).toHaveCount(0);
  await page.screenshot({ path: 'shots/livex-closed.png' });
});
