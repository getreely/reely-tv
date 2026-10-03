import { describe, expect, it } from "vitest";
import { relevant, rememberedSearches, split } from "../../src/core/searchMatch";
import { item } from "./fixtures";

const film = (title: string) => item({ ratingKey: title, title });
const episode = (title: string, show: string) => item({ ratingKey: `${show}/${title}`, title, type: "episode", grandparentTitle: show });
const titles = (query: string, ...items: ReturnType<typeof item>[]) => relevant(query, items).map((i) => i.title);

describe("search matching", () => {
  it("only names with it in, not things that came close", () => {
    expect(titles("nfl", film("Friday Night Lights"), film("NFL Films Presents"), film("Inflation"))).toEqual(["NFL Films Presents"]);
  });
  it("an exact name first, then names starting with it, then the rest", () => {
    expect(titles("dune", film("The Dune Chronicles"), film("Dune: Part Two"), film("Dune"))).toEqual(["Dune", "Dune: Part Two", "The Dune Chronicles"]);
  });
  it("a show's episodes match on the show's name", () => {
    expect(titles("nfl game", episode("Week 1", "NFL Game Day"), episode("Pilot", "Suits"))).toEqual(["Week 1"]);
  });
  it("punctuation, case and accents don't count", () => {
    expect(titles("spiderman", film("Spider-Man"))).toEqual(["Spider-Man"]);
    expect(titles("AMELIE", film("Amélie"))).toEqual(["Amélie"]);
  });
  it("words typed together can start at any word", () => {
    expect(titles("ofsteel", film("Man of Steel"))).toEqual(["Man of Steel"]);
  });
  it("the rest follow, not thrown away", () => {
    const [matches, others] = split("nfl", [film("Friday Night Lights"), film("NFL Films Presents"), film("Inflation")]);
    expect(matches.map((i) => i.title)).toEqual(["NFL Films Presents"]);
    expect(others.map((i) => i.title)).toEqual(["Friday Night Lights", "Inflation"]);
  });
  it("recent searches: newest first, once, however it was typed", () => {
    expect(rememberedSearches(["dune", "Heat"], "  heat  ")).toEqual(["heat", "dune"]);
    expect(rememberedSearches(["a"], "   ")).toEqual(["a"]);
    expect(rememberedSearches(["1", "2", "3", "4", "5", "6", "7", "8"], "new")).toHaveLength(8);
  });
});
