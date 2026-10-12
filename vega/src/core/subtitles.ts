import { stableSort } from "./sort";

/*
 * Text subtitles, read here and drawn by the app rather than burned into the picture by
 * Plex: so the file can still play as it is, and the words take the size and background
 * chosen in Settings, as on the Fire TV. SRT, WebVTT and ASS/SSA (as plain lines).
 */

export interface Cue {
  startMs: number;
  endMs: number;
  text: string;
}

/** The codecs read here; anything else (pictures, like PGS) is Plex's to burn in. */
export const TEXT_CODECS = ["srt", "subrip", "vtt", "webvtt", "ass", "ssa"];
export const isTextCodec = (codec: string | null | undefined) => !!codec && TEXT_CODECS.includes(codec.toLowerCase());

/** "00:01:02,345", "01:02.345" or ASS's "0:01:02.34", in milliseconds; NaN when it isn't a time. */
export function timeMs(raw: string): number {
  const m = /^\s*(?:(\d+):)?(\d{1,2}):(\d{1,2})[.,](\d{1,3})\s*$/.exec(raw);
  if (!m) return NaN;
  const [, h, min, s, frac] = m;
  const ms = Number(frac.padEnd(3, "0"));
  return ((Number(h ?? 0) * 60 + Number(min)) * 60 + Number(s)) * 1000 + ms;
}

/** Tags out (<i>, {\an8}), entities and line breaks as they're written. */
function clean(text: string): string {
  return text
    .replace(/\{[^}]*\}/g, "")
    .replace(/<[^>]+>/g, "")
    .replace(/\\N/gi, "\n")
    .replace(/&amp;/g, "&")
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&nbsp;/g, " ")
    .split("\n")
    .map((l) => l.trim())
    .filter(Boolean)
    .join("\n");
}

export function parseSubtitles(text: string, codec: string | null): Cue[] {
  const body = text.replace(/^﻿/, "").replace(/\r\n?/g, "\n");
  const c = (codec ?? "").toLowerCase();
  const cues = c === "ass" || c === "ssa" || /^\[Script Info\]/m.test(body) ? parseAss(body) : parseBlocks(body);
  return stableSort(cues, (a, b) => a.startMs - b.startMs || a.endMs - b.endMs);
}

/** SRT and WebVTT: blocks with a "start --> end" line, then the words. */
function parseBlocks(body: string): Cue[] {
  const out: Cue[] = [];
  for (const block of body.split(/\n{2,}/)) {
    const lines = block.split("\n");
    const at = lines.findIndex((l) => l.includes("-->"));
    if (at < 0) continue;
    const [from, to] = lines[at].split("-->");
    const startMs = timeMs(from);
    const endMs = timeMs((to ?? "").trim().split(/\s+/)[0] ?? "");
    const words = clean(lines.slice(at + 1).join("\n"));
    if (!isNaN(startMs) && !isNaN(endMs) && endMs > startMs && words) out.push({ startMs, endMs, text: words });
  }
  return out;
}

/** ASS/SSA: the Dialogue lines, in the order the Format line gives. */
function parseAss(body: string): Cue[] {
  const out: Cue[] = [];
  let fields = ["layer", "start", "end", "style", "name", "marginl", "marginr", "marginv", "effect", "text"];
  let inEvents = false;
  for (const line of body.split("\n")) {
    const t = line.trim();
    if (/^\[.*\]$/.test(t)) {
      inEvents = /^\[Events\]$/i.test(t);
      continue;
    }
    if (!inEvents) continue;
    if (/^Format:/i.test(t)) {
      fields = t.slice(7).split(",").map((f) => f.trim().toLowerCase());
      continue;
    }
    if (!/^Dialogue:/i.test(t)) continue;
    const parts = t.slice(9).split(",");
    const textAt = fields.indexOf("text");
    const value = (name: string) => parts[fields.indexOf(name)] ?? "";
    const startMs = timeMs(value("start"));
    const endMs = timeMs(value("end"));
    const words = clean(parts.slice(textAt).join(","));
    if (!isNaN(startMs) && !isNaN(endMs) && endMs > startMs && words) out.push({ startMs, endMs, text: words });
  }
  return out;
}

/** What's on screen at [ms]: every cue showing then, top first, in one. */
export function cueAt(cues: Cue[], ms: number): string | null {
  const on = cues.filter((c) => c.startMs <= ms && ms < c.endMs);
  return on.length ? on.map((c) => c.text).join("\n") : null;
}
