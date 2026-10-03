import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";

/*
 * webOS 5 runs Chromium 68. CSS it doesn't know is dropped without a word, so a layout
 * that looks right in a desktop browser falls apart on the TV. Caught here instead.
 */
const css = readFileSync(new URL("../../src/ui/styles.css", import.meta.url), "utf8");
const rules = css.replace(/\/\*[\s\S]*?\*\//g, "").split("}").map((r) => r.trim()).filter(Boolean);

describe("styles the TV's browser understands", () => {
  it("no inset (Chromium 87)", () => {
    expect(css).not.toMatch(/(^|[;{\s])inset\s*:/);
  });
  it("no gap on a flex box (Chromium 84); grids may have it", () => {
    const offenders = rules.filter((r) => /display:\s*(inline-)?flex/.test(r) && /(^|[;{\s])(row-|column-)?gap\s*:/.test(r));
    expect(offenders).toEqual([]);
  });
  it("no aspect-ratio (Chromium 88), :is or :where (88), or :has (105)", () => {
    expect(css).not.toMatch(/aspect-ratio\s*:/);
    expect(css).not.toMatch(/:(is|where|has)\(/);
  });
});
