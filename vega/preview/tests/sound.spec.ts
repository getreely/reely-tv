import { expect, test, type Page } from '@playwright/test';
import { fakePlex, press, signedIn } from './fake';

async function openSettings(page: Page) {
  await page.goto('/');
  await expect(page.getByText('Continue Watching')).toBeVisible({ timeout: 20_000 });
  await press(page, 'ArrowRight', 6);
  await page.waitForTimeout(300);
  await press(page, 'Enter');
  await expect(page.getByText('Playback mode')).toBeVisible({ timeout: 10_000 });
}

/** Down through the rows until the one with [text] has the cursor. */
async function downTo(page: Page, text: string) {
  for (let i = 0; i < 20; i++) {
    const at = await page.evaluate(() => document.activeElement?.textContent ?? '');
    if (at.includes(text)) return;
    await press(page, 'ArrowDown');
    await page.waitForTimeout(150);
  }
  throw new Error(`Never reached "${text}"`);
}

test('Sound: headphones paired with the stick chosen and connected, and new ones found and paired', async ({ page }) => {
  page.on('pageerror', (e) => console.log('pageerror:', e.message));
  await fakePlex(page);
  await signedIn(page);
  await page.addInitScript(() => localStorage.setItem('reely:fake-devices', '1'));
  await openSettings(page);
  await expect(page.getByText('Play sound on')).toBeVisible();
  await press(page, 'ArrowRight');
  await page.waitForTimeout(300);
  await downTo(page, 'Play sound on');
  await page.waitForTimeout(300);
  await page.screenshot({ path: 'shots/sound.png' });

  // The list: Automatic, then what's paired, the connected one said so.
  await press(page, 'Enter');
  await expect(page.getByText('Bluetooth, connected')).toBeVisible();
  await page.waitForTimeout(400);
  await page.screenshot({ path: 'shots/sound-choice.png' });
  await downTo(page, 'AirPods Pro');
  await press(page, 'Enter');
  await expect(page.getByText('Connecting AirPods Pro…')).toBeVisible();
  await expect(page.getByText('Connecting AirPods Pro…')).toHaveCount(0, { timeout: 5_000 });

  // A speaker in pairing mode, found and paired: it's where the sound goes now.
  await downTo(page, 'Pair new headphones');
  await press(page, 'Enter');
  await expect(page.getByText('JBL Flip 6')).toBeVisible({ timeout: 5_000 });
  await page.waitForTimeout(300);
  await page.screenshot({ path: 'shots/sound-found.png' });
  await downTo(page, 'JBL Flip 6');
  await press(page, 'Enter');
  await expect(page.getByText('Pair new headphones')).toBeVisible();
  await expect.poll(async () => page.getByText('JBL Flip 6').count(), { timeout: 8_000 }).toBeGreaterThanOrEqual(1);
  await page.waitForTimeout(1_500);
  await page.screenshot({ path: 'shots/sound-paired.png' });
  expect(await page.evaluate(() => JSON.parse(localStorage.getItem('reely:prefs') ?? '{}').audioOutput)).toBe('bt-3');
});

test("Sound and frame rate as the Fire TV sets them: where to do it", async ({ page }) => {
  page.on('pageerror', (e) => console.log('pageerror:', e.message));
  await fakePlex(page);
  await signedIn(page);
  await openSettings(page);
  await expect(page.getByText("Chosen on the Fire TV itself", { exact: false })).toBeVisible();
  await page.waitForTimeout(300);
  await page.screenshot({ path: 'shots/sound-fallback.png' });
});
