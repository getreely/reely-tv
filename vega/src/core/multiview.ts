/*
 * Where each channel goes when several share the screen, as the Fire TV lays them out
 * (MultiView.kt). Tiles are numbered by what they are: 0 the channel the player is on,
 * then the others in the order they were added. Places are where they're drawn: `order`
 * says which tile is in each place, so a tile can be moved without its picture being
 * started again.
 */

export interface Rect {
  left: number;
  top: number;
  width: number;
  height: number;
}

/** The most channels at once: a 2x2 grid is where the screen runs out. */
export const MAX_TILES = 4;

const rect = (left: number, top: number, right: number, bottom: number): Rect => ({ left, top, width: right - left, height: bottom - top });

/**
 * Every place in the even grid. Two go side by side, three give the first the left half
 * and stack the others, and four quarter the screen.
 */
export function gridRects(slots: number, width: number, height: number, gap: number): Rect[] {
  const halfW = Math.floor((width - gap) / 2);
  const halfH = Math.floor((height - gap) / 2);
  const right = width - halfW;
  const lower = height - halfH;
  switch (slots) {
    case 2: return [rect(0, 0, halfW, height), rect(right, 0, width, height)];
    case 3: return [rect(0, 0, halfW, height), rect(right, 0, width, halfH), rect(right, lower, width, height)];
    case 4: return [rect(0, 0, halfW, halfH), rect(right, 0, width, halfH), rect(0, lower, halfW, height), rect(right, lower, width, height)];
    default: return [rect(0, 0, width, height)];
  }
}

/** The share of the width a side column takes in the focus layout: less with one each side. */
const SIDE_ONE = 0.3;
const SIDE_BOTH = 0.2;

/** The places either side of the large one in the focus layout, in their own order. */
export function focusSides(slots: number, focused: number): [number[], number[]] {
  const large = Math.max(0, Math.min(focused, slots - 1));
  const before: number[] = [];
  const after: number[] = [];
  for (let i = 0; i < slots; i++) if (i < large) before.push(i); else if (i > large) after.push(i);
  return [before, after];
}

/**
 * Every place in the focus layout: the one the cursor's on large, full height, in its own
 * place in the line; the ones before and after it in picture-shaped boxes stacked down the
 * middle of a column either side.
 */
export function focusRects(slots: number, focused: number, width: number, height: number, gap: number): Rect[] {
  if (slots <= 1) return [rect(0, 0, width, height)];
  const [before, after] = focusSides(slots, focused);
  const columns = (before.length ? 1 : 0) + (after.length ? 1 : 0);
  const room = width - gap * columns;
  const sideW = Math.floor(room * (columns === 2 ? SIDE_BOTH : SIDE_ONE));
  const largeW = room - sideW * columns;
  const largeLeft = before.length ? sideW + gap : 0;
  const rects: Rect[] = new Array(slots).fill(null).map(() => rect(0, 0, 0, 0));
  rects[Math.max(0, Math.min(focused, slots - 1))] = rect(largeLeft, 0, largeLeft + largeW, height);
  const column = (places: number[], left: number) => {
    const tileH = Math.floor((sideW * 9) / 16);
    const total = tileH * places.length + gap * (places.length - 1);
    let top = Math.floor((height - total) / 2);
    for (const place of places) {
      rects[place] = rect(left, top, left + sideW, top + tileH);
      top += tileH + gap;
    }
  };
  column(before, 0);
  column(after, largeLeft + largeW + gap);
  return rects;
}

/** The place a direction leads to in the grid, or null when there's none that way. */
export function tileNeighbour(slots: number, from: number, dx: number, dy: number): number | null {
  let target: number | null = null;
  if (slots === 2) {
    if (dx < 0 && from === 1) target = 0;
    else if (dx > 0 && from === 0) target = 1;
  } else if (slots === 3) {
    // Place 0 has the left half; 1 and 2 are stacked on the right.
    if (dx > 0 && from === 0) target = 1;
    else if (dx < 0 && from !== 0) target = 0;
    else if (dy > 0 && from === 1) target = 2;
    else if (dy < 0 && from === 2) target = 1;
  } else if (slots === 4) {
    // 0 1
    // 2 3
    if (dx > 0) target = from === 0 ? 1 : from === 2 ? 3 : null;
    else if (dx < 0) target = from === 1 ? 0 : from === 3 ? 2 : null;
    else if (dy > 0) target = from === 0 ? 2 : from === 1 ? 3 : null;
    else if (dy < 0) target = from === 2 ? 0 : from === 3 ? 1 : null;
  }
  return target != null && target >= 0 && target < slots ? target : null;
}

/** Where the arrows go from [from]: the focus layout is a line, the grid by [tileNeighbour]. */
export function nextPlace(slots: number, from: number, dx: number, dy: number, focusLayout: boolean): number | null {
  if (!focusLayout) return tileNeighbour(slots, from, dx, dy);
  const to = from + dx + dy;
  return to >= 0 && to < slots ? to : null;
}

/**
 * Whether there's room for a spare cell to add a channel in. In the focus layout it costs a
 * slice of the side column, so it's there from the second channel; the grid would shrink a
 * channel from half the screen to a quarter, so there it waits for the third.
 */
export const hasSpareCell = (tiles: number, focusLayout: boolean) => (focusLayout ? tiles >= 2 && tiles <= 3 : tiles === 3);

/** Which tile is in each place for [count] tiles, carried over from [order]: new ones join at the end. */
export function tileOrder(order: number[], count: number): number[] {
  const kept: number[] = [];
  for (const t of order) if (t >= 0 && t < count && !kept.includes(t)) kept.push(t);
  for (let t = 0; t < count; t++) if (!kept.includes(t)) kept.push(t);
  return kept;
}

/** [order] once tile [gone] is closed: the tiles after it are each numbered one less; places don't change. */
export const withoutTile = (order: number[], gone: number) => order.filter((t) => t !== gone).map((t) => (t > gone ? t - 1 : t));

/** [order] with the tile in place [from] swapped with the one [delta] places along. */
export function movedTile(order: number[], from: number, delta: number): number[] {
  const to = from + delta;
  if (from < 0 || from >= order.length || to < 0 || to >= order.length) return order;
  const moved = order.slice();
  [moved[from], moved[to]] = [moved[to], moved[from]];
  return moved;
}

/** What a provider answers when it won't open another stream: over the account's limit. */
export const REFUSED = [401, 403, 429, 458, 509];

/** What to call a saved set in a menu: "BBC One and 2 more". */
export function savedLabel(names: string[]): string {
  const first = names[0]?.trim() || "Channel";
  return `${first} and ${names.length - 1} more`;
}
