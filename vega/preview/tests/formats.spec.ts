import { expect, test } from '@playwright/test';
import { fakePlex, press, signedIn } from './fake';

test('A DTS episode: the TV asked, the picture kept and the sound made Dolby surround, and Playback info says why', async ({ page }) => {
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
  await press(page, 'Enter');
  await expect(page.getByText('Skip Intro')).toBeVisible({ timeout: 10_000 });
  const opened = decodeURIComponent(await page.evaluate(() => (window as unknown as { lastOpened?: string }).lastOpened ?? ''));
  // Plex's HLS, in fragmented MP4 chunks the TV's player reads itself, as Plezy asks.
  expect(opened).toContain('/video/:/transcode/universal/start.m3u8');
  expect(opened).toContain('protocol=hls');
  expect(opened).toContain('container=mp4');
  expect(opened).toContain('directStream=1');
  expect(opened).toContain('directStreamAudio=0');
  // The picture the TV plays, copied; the 5.1 DTS made Dolby Digital Plus, else Dolby Digital, else AAC.
  expect(opened).toContain('videoCodec=h264%2Chevc&audioCodec=eac3%2Cac3%2Caac');
  expect(opened).toContain('scopeName=eac3&type=upperBound&name=audio.channels&value=6');
  expect(opened).toContain('scopeName=aac&type=upperBound&name=audio.channels&value=2');
  await press(page, 'i');
  await expect(page.getByText('Playback info')).toBeVisible({ timeout: 5_000 });
  await expect(page.getByText("This TV can't play DTS sound")).toBeVisible();
  await page.waitForTimeout(400);
  await page.screenshot({ path: 'shots/player-info-converted.png' });
});
