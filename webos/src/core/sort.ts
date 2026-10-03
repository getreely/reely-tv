/**
 * A sorted copy where equal items keep their order. The TVs' browser (Chromium 68 on
 * webOS 5) doesn't promise that from Array.sort — it only became stable in Chromium 70 —
 * so every sort goes through here.
 */
export function stableSort<T>(list: readonly T[], by: (a: T, b: T) => number): T[] {
  return list
    .map((value, index) => ({ value, index }))
    .sort((a, b) => by(a.value, b.value) || a.index - b.index)
    .map((x) => x.value);
}
