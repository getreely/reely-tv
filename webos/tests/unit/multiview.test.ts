import { describe, expect, it } from "vitest";
import { focusRects, gridRects, hasSpareCell, movedTile, nextPlace, savedLabel, tileNeighbour, tileOrder, withoutTile } from "../../src/core/multiview";

describe("Multiview's layouts, as the Fire TV's", () => {
  it("the grid: two side by side, three with the first on the left, four in quarters", () => {
    expect(gridRects(1, 1920, 1080, 4)).toEqual([{ left: 0, top: 0, width: 1920, height: 1080 }]);
    expect(gridRects(2, 1920, 1080, 4)).toEqual([
      { left: 0, top: 0, width: 958, height: 1080 },
      { left: 962, top: 0, width: 958, height: 1080 },
    ]);
    const three = gridRects(3, 1920, 1080, 4);
    expect(three[0].height).toBe(1080);
    expect(three[1]).toEqual({ left: 962, top: 0, width: 958, height: 538 });
    expect(three[2]).toEqual({ left: 962, top: 542, width: 958, height: 538 });
    expect(gridRects(4, 1920, 1080, 4).map((r) => [r.left, r.top])).toEqual([[0, 0], [962, 0], [0, 542], [962, 542]]);
  });

  it("the focus layout: the one with the cursor large in its own place, the rest in columns either side", () => {
    const first = focusRects(3, 0, 1920, 1080, 4);
    expect(first[0]).toMatchObject({ left: 0, top: 0, height: 1080 });
    expect(first[1].left).toBe(first[2].left);
    expect(first[1].left).toBeGreaterThan(first[0].width);
    const middle = focusRects(3, 1, 1920, 1080, 4);
    expect(middle[0].left).toBe(0);
    expect(middle[1].left).toBe(middle[0].width + 4);
    expect(middle[2].left).toBe(middle[1].left + middle[1].width + 4);
    // Picture-shaped side tiles.
    expect(middle[0].height).toBe(Math.floor((middle[0].width * 9) / 16));
  });

  it("the arrows walk the grid, and the focus layout as a line", () => {
    expect(tileNeighbour(2, 0, 1, 0)).toBe(1);
    expect(tileNeighbour(2, 1, 1, 0)).toBeNull();
    expect(tileNeighbour(3, 2, -1, 0)).toBe(0);
    expect(tileNeighbour(3, 1, 0, 1)).toBe(2);
    expect(tileNeighbour(4, 3, 0, -1)).toBe(1);
    expect(tileNeighbour(4, 2, 0, 1)).toBeNull();
    expect(nextPlace(3, 0, 0, 1, true)).toBe(1);
    expect(nextPlace(3, 2, 1, 0, true)).toBeNull();
  });

  it("offers a spare cell from two channels in the focus layout, at three in the grid", () => {
    expect([1, 2, 3, 4].map((n) => hasSpareCell(n, true))).toEqual([false, true, true, false]);
    expect([1, 2, 3, 4].map((n) => hasSpareCell(n, false))).toEqual([false, false, true, false]);
  });

  it("tiles keep their places as others come and go, and move by swapping", () => {
    expect(tileOrder([0], 3)).toEqual([0, 1, 2]);
    expect(tileOrder([2, 0, 1], 2)).toEqual([0, 1]);
    expect(withoutTile([2, 0, 1, 3], 1)).toEqual([1, 0, 2]);
    expect(movedTile([0, 1, 2], 0, 1)).toEqual([1, 0, 2]);
    expect(movedTile([0, 1, 2], 2, 1)).toEqual([0, 1, 2]);
    expect(savedLabel(["BBC One", "ITV", "Sky"])).toBe("BBC One and 2 more");
  });
});
