// The LG app's spatial navigation (webos/src/core/spatial.ts), standing in for Vega's
// focus engine in the browser preview.
export interface Rect { left: number; top: number; width: number; height: number }
export type Direction = 'up' | 'down' | 'left' | 'right';

export function nextIndex(from: Rect, candidates: Rect[], direction: Direction): number {
  const fx1 = from.left, fx2 = from.left + from.width, fy1 = from.top, fy2 = from.top + from.height;
  const fcx = (fx1 + fx2) / 2, fcy = (fy1 + fy2) / 2;
  let best = -1;
  let bestScore = Infinity;
  candidates.forEach((c, i) => {
    const cx1 = c.left, cx2 = c.left + c.width, cy1 = c.top, cy2 = c.top + c.height;
    const ccx = (cx1 + cx2) / 2, ccy = (cy1 + cy2) / 2;
    let ahead: number, aside: number, overlap: number;
    switch (direction) {
      case 'right': ahead = cx1 - fx2; aside = Math.abs(ccy - fcy); overlap = Math.min(fy2, cy2) - Math.max(fy1, cy1); if (ccx <= fcx + 1) return; break;
      case 'left': ahead = fx1 - cx2; aside = Math.abs(ccy - fcy); overlap = Math.min(fy2, cy2) - Math.max(fy1, cy1); if (ccx >= fcx - 1) return; break;
      case 'down': ahead = cy1 - fy2; aside = Math.abs(ccx - fcx); overlap = Math.min(fx2, cx2) - Math.max(fx1, cx1); if (ccy <= fcy + 1) return; break;
      default: ahead = fy1 - cy2; aside = Math.abs(ccx - fcx); overlap = Math.min(fx2, cx2) - Math.max(fx1, cx1); if (ccy >= fcy - 1) return;
    }
    const inLine = overlap > 0;
    const score = (inLine ? 0 : 1_000_000) + Math.max(0, ahead) * 2 + aside * (inLine ? 0.5 : 3);
    if (score < bestScore) { bestScore = score; best = i; }
  });
  return best;
}
