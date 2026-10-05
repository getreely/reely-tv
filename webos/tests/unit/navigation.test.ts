import { describe, expect, it } from "vitest";
import { actionOf } from "../../src/core/keys";
import { nextIndex, type Rect } from "../../src/core/spatial";
import { clientId, MemoryStorage, Store } from "../../src/core/storage";

const box = (left: number, top: number, width = 100, height = 150): Rect => ({ left, top, width, height });

describe("the cursor and the arrows", () => {
  // Two rows of posters, the second shifted half a poster along.
  const rowOne = [box(0, 0), box(120, 0), box(240, 0), box(360, 0)];
  const rowTwo = [box(60, 200), box(180, 200), box(300, 200)];
  const all = [...rowOne, ...rowTwo];

  it("right and left stay in the row", () => {
    expect(nextIndex(rowOne[1], all, "right")).toBe(2);
    expect(nextIndex(rowOne[1], all, "left")).toBe(0);
  });
  it("down goes to the nearest below, up comes back", () => {
    // Two equally near below: the first of them, every time.
    expect(nextIndex(rowOne[1], all, "down")).toBe(4);
    expect(nextIndex(rowOne[2], all, "down")).toBe(5);
    expect(nextIndex(rowTwo[1], all, "up")).toBe(1);
  });
  it("nothing that way is nothing", () => {
    expect(nextIndex(rowOne[0], all, "left")).toBe(-1);
    expect(nextIndex(rowOne[0], all, "up")).toBe(-1);
    expect(nextIndex(rowTwo[2], all, "down")).toBe(-1);
  });
  it("up is the row above, even with nothing in it straight above, before the tabs", () => {
    // A poster along a row; the row above has one, off to the left; the tabs above that are in line.
    const poster = box(350, 600, 250, 450);
    const above = box(80, 30, 250, 450);
    const tab = box(240, -500, 150, 70);
    expect(nextIndex(poster, [tab, above], "up")).toBe(1);
  });
  it("up and down stay in a column when the next row along it is as near", () => {
    // Settings: a row under the cursor's, and a section on the left a little nearer.
    const row = box(500, 100, 900, 70);
    expect(nextIndex(row, [box(500, 180, 900, 70), box(80, 175, 300, 60)], "down")).toBe(0);
  });
  it("in line beats diagonally nearer", () => {
    // A tall button on the left; a poster far right in line, a small one near but below.
    const from = box(0, 0, 100, 50);
    expect(nextIndex(from, [box(600, 10, 100, 40), box(110, 60, 40, 40)], "right")).toBe(0);
  });
});

describe("the LG remote", () => {
  const key = (keyCode: number, key = "") => actionOf({ keyCode, key });
  it("Back is webOS's 461, and the arrows and OK are the usual", () => {
    expect(key(461)).toBe("back");
    expect(key(37)).toBe("left");
    expect(key(13)).toBe("ok");
  });
  it("number keys from either row", () => {
    expect(key(55)).toEqual({ digit: 7 });
    expect(key(96)).toEqual({ digit: 0 });
  });
  it("media and channel keys", () => {
    expect(key(415)).toBe("play");
    expect(key(19)).toBe("pause");
    expect(key(33)).toBe("channelUp");
  });
  it("a keyboard in the browser works too, and anything else is nothing", () => {
    expect(key(0, "ArrowDown")).toBe("down");
    expect(key(0, "Escape")).toBe("back");
    expect(key(500)).toBeNull();
  });
});

describe("what the TV keeps", () => {
  it("reads back what it wrote, and survives rubbish", () => {
    const backing = new MemoryStorage();
    const store = new Store("t:", backing);
    store.setJson("prefs", { a: 1 });
    expect(store.json("prefs", {})).toEqual({ a: 1 });
    backing.setItem("t:broken", "{nope");
    expect(store.json("broken", "fallback")).toBe("fallback");
    store.remove("prefs");
    expect(store.get("prefs")).toBeNull();
  });
  it("storage that throws is no worse than none", () => {
    const angry = new MemoryStorage();
    angry.setItem = () => { throw new Error("quota"); };
    angry.getItem = () => { throw new Error("denied"); };
    const store = new Store("t:", angry);
    store.set("x", "1");
    expect(store.get("x")).toBeNull();
  });
  it("the TV's Plex id is made once and kept", () => {
    const store = new Store("t:", new MemoryStorage());
    const first = clientId(store);
    expect(first).toMatch(/^reely-lg-[0-9a-f]{32}$/);
    expect(clientId(store)).toBe(first);
  });
});
