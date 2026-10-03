import { describe, expect, it } from "vitest";
import { cueAt, isTextCodec, parseSubtitles, timeMs } from "../../src/core/subtitles";

describe("text subtitles, read and drawn by the app", () => {
  it("reads times as each format writes them", () => {
    expect(timeMs("00:01:02,345")).toBe(62_345);
    expect(timeMs("01:02.345")).toBe(62_345);
    expect(timeMs("0:01:02.34")).toBe(62_340);
    expect(timeMs("nonsense")).toBeNaN();
  });

  it("SRT: blocks in order, tags out, line breaks kept", () => {
    const cues = parseSubtitles("﻿1\r\n00:00:01,000 --> 00:00:02,500\r\n<i>Hello</i>\r\nthere\r\n\r\n2\r\n00:00:03,000 --> 00:00:04,000\r\nAgain\r\n", "srt");
    expect(cues).toEqual([
      { startMs: 1000, endMs: 2500, text: "Hello\nthere" },
      { startMs: 3000, endMs: 4000, text: "Again" },
    ]);
    expect(cueAt(cues, 2000)).toBe("Hello\nthere");
    expect(cueAt(cues, 2600)).toBeNull();
  });

  it("WebVTT: the header and cue settings aside", () => {
    const cues = parseSubtitles("WEBVTT\n\n00:01.000 --> 00:02.000 align:start\nOne &amp; two\n", "vtt");
    expect(cues).toEqual([{ startMs: 1000, endMs: 2000, text: "One & two" }]);
  });

  it("ASS: the Dialogue lines, override codes out, \\N as a new line, commas in the words kept", () => {
    const ass = [
      "[Script Info]", "Title: x", "", "[Events]",
      "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text",
      "Dialogue: 0,0:00:01.00,0:00:02.50,Default,,0,0,0,,{\\an8}Up top\\Nsecond, line",
    ].join("\n");
    expect(parseSubtitles(ass, "ass")).toEqual([{ startMs: 1000, endMs: 2500, text: "Up top\nsecond, line" }]);
  });

  it("pictures (PGS) aren't text: Plex burns those in", () => {
    expect(isTextCodec("srt")).toBe(true);
    expect(isTextCodec("pgs")).toBe(false);
    expect(isTextCodec(null)).toBe(false);
  });
});
