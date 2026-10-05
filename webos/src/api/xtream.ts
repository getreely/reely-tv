import { ask, askJson, HttpError } from "../core/http";
import { stableSort } from "../core/sort";

/*
 * Live TV from the provider, as the Android app has it (XtreamApi.kt, M3uPlaylist.kt):
 * an Xtream login or an M3U playlist; categories, channels, the short guide, catch-up.
 */

export interface XtreamCredentials {
  base: string;
  username: string;
  password: string;
  /** Set when live TV comes from an M3U playlist. */
  playlistUrl?: string | null;
  guideUrl?: string | null;
}

export const isPlaylist = (c: XtreamCredentials) => !!c.playlistUrl;

export interface XtreamAccount {
  status: string;
  maxConnections: string;
  activeConnections: string;
  expiresAt: string | null;
  timezone: string | null;
}

export interface XtreamCategory {
  id: string;
  name: string;
}

export interface XtreamChannel {
  streamId: number;
  number: number;
  name: string;
  icon: string | null;
  epgChannelId: string | null;
  url?: string | null;
  group?: string | null;
  archiveDays: number;
}

export interface Programme {
  channelId: string;
  /** Epoch seconds. */
  start: number;
  stop: number;
  title: string;
  description: string | null;
}

export const isOnAt = (p: Programme, at: number) => at >= p.start && at < p.stop;

/** How far through it is at [at], 0 to 1; null when it isn't on or has no length. */
export function progressAt(p: Pick<Programme, "start" | "stop">, at: number): number | null {
  if (p.stop <= p.start || at < p.start || at > p.stop) return null;
  return (at - p.start) / (p.stop - p.start);
}

/** Accepts "panel.example.com:8080", "http://…/" and the rest. */
export function normalizeBase(raw: string): string {
  const t = raw.trim().replace(/\/+$/, "");
  if (!t) return t;
  return /^https?:\/\//i.test(t) ? t : `http://${t}`;
}

function api(c: XtreamCredentials, action: string | null, extras: Record<string, string> = {}): string {
  const q = new URLSearchParams({ username: c.username, password: c.password });
  if (action) q.set("action", action);
  for (const [k, v] of Object.entries(extras)) q.set(k, v);
  return `${c.base}/player_api.php?${q.toString()}`;
}

// ---------------------------------------------------------------- Playlists

let playlist: { url: string; list: M3uPlaylist } | null = null;

export interface M3uPlaylist {
  channels: XtreamChannel[];
  guideUrl: string | null;
}

export const OTHER = "Other";
const VOD_EXTENSIONS = [".mp4", ".mkv", ".avi", ".mov", ".m4v", ".wmv", ".flv", ".webm"];

async function playlistFor(c: XtreamCredentials, fresh = false): Promise<M3uPlaylist> {
  const url = c.playlistUrl!;
  if (!fresh && playlist?.url === url) return playlist.list;
  const response = await ask(url, { timeoutMs: 120_000, failure: "Couldn't download the playlist." });
  const list = parseM3u(await response.text());
  playlist = { url, list };
  return list;
}

/** A playlist, a line at a time; films and series in it are passed over, only channels kept. */
export function parseM3u(text: string): M3uPlaylist {
  let guideUrl: string | null = null;
  let pending: { name: string | null; id: string | null; logo: string | null; number: number | null; group: string | null } | null = null;
  const channels: XtreamChannel[] = [];
  const ids = new Set<number>();
  for (const raw of text.split(/\r?\n/)) {
    const line = raw.trim().replace(/^﻿/, "");
    if (!line) continue;
    if (/^#EXTM3U/i.test(line)) {
      const a = attributes(line);
      const named = (a["url-tvg"] ?? a["x-tvg-url"])?.split(",").map((s) => s.trim()).find((s) => /^http/i.test(s));
      guideUrl = named ?? guideUrl;
    } else if (/^#EXTINF/i.test(line)) {
      const a = attributes(line);
      let quoted = false;
      let comma = -1;
      for (let i = 0; i < line.length; i++) {
        if (line[i] === '"') quoted = !quoted;
        else if (line[i] === "," && !quoted) {
          comma = i;
          break;
        }
      }
      const name = comma >= 0 ? line.slice(comma + 1).trim() : "";
      pending = {
        name: name || a["tvg-name"]?.trim() || null,
        id: a["tvg-id"]?.trim() || null,
        logo: /^http/i.test(a["tvg-logo"]?.trim() ?? "") ? a["tvg-logo"]!.trim() : null,
        number: Number.parseInt(a["tvg-chno"] ?? "", 10) || null,
        group: a["group-title"]?.trim() || null,
      };
    } else if (/^#EXTGRP:/i.test(line)) {
      if (pending && !pending.group) pending.group = line.slice(line.indexOf(":") + 1).trim() || null;
    } else if (line.startsWith("#")) {
      continue;
    } else {
      const entry = pending ?? { name: null, id: null, logo: null, number: null, group: null };
      pending = null;
      const path = line.split("?")[0].toLowerCase();
      if (path.includes("/movie/") || path.includes("/series/") || VOD_EXTENSIONS.some((e) => path.endsWith(e))) continue;
      let streamId = hash(line);
      while (ids.has(streamId)) streamId = (streamId + 1) & 0x7fffffff;
      ids.add(streamId);
      channels.push({
        streamId,
        number: entry.number ?? channels.length + 1,
        name: entry.name ?? `Channel ${channels.length + 1}`,
        icon: entry.logo,
        epgChannelId: entry.id?.toLowerCase() ?? null,
        url: line,
        group: entry.group,
        archiveDays: 0,
      });
    }
  }
  return { channels, guideUrl };
}

function attributes(line: string): Record<string, string> {
  const out: Record<string, string> = {};
  const re = /([A-Za-z0-9_-]+)="([^"]*)"/g;
  let m: RegExpExecArray | null;
  while ((m = re.exec(line))) out[m[1].toLowerCase()] = m[2];
  return out;
}

/** The same number for the same address, as Java's String.hashCode makes it. */
function hash(text: string): number {
  let h = 0;
  for (let i = 0; i < text.length; i++) h = (Math.imul(31, h) + text.charCodeAt(i)) | 0;
  return h & 0x7fffffff;
}

// ---------------------------------------------------------------- Signing in

export async function login(c: XtreamCredentials): Promise<XtreamAccount> {
  if (isPlaylist(c)) {
    const list = await playlistFor(c, true);
    if (!list.channels.length) throw new Error("There are no live channels in that playlist.");
    return { status: "Active", maxConnections: "?", activeConnections: "0", expiresAt: null, timezone: null };
  }
  const root = await askJson(api(c, null), { failure: "Couldn't connect. Check the server address and port." });
  const user = root?.user_info;
  if (!user || typeof user !== "object") throw new Error("Couldn't connect. Check the server address and port.");
  if (Number(user.auth) !== 1) throw new Error("Incorrect username or password.");
  const status = String(user.status || "Unknown");
  if (status.toLowerCase() !== "active") throw new Error(`This account is ${status}. Contact your provider.`);
  const exp = user.exp_date;
  return {
    status,
    maxConnections: String(user.max_connections || "?"),
    activeConnections: String(user.active_cons || "0"),
    expiresAt: exp && exp !== "null" ? String(exp) : null,
    timezone: root.server_info?.timezone ? String(root.server_info.timezone) : null,
  };
}

/** The panel login inside an M3U address from a panel's get.php, when there is one. */
export function panelLoginIn(url: string): XtreamCredentials | null {
  try {
    const u = new URL(url);
    if (!/get\.php$/i.test(u.pathname)) return null;
    const username = u.searchParams.get("username");
    const password = u.searchParams.get("password");
    if (!username || !password) return null;
    return { base: `${u.protocol}//${u.host}`, username, password };
  } catch {
    return null;
  }
}

// ---------------------------------------------------------------- Channels

export async function liveCategories(c: XtreamCredentials): Promise<XtreamCategory[]> {
  if (isPlaylist(c)) {
    const groups = Array.from(new Set((await playlistFor(c)).channels.map((ch) => ch.group ?? OTHER)));
    return groups.map((g) => ({ id: g, name: g }));
  }
  const list = await askJson<any[]>(api(c, "get_live_categories"), { failure: "Your provider couldn't do that. Try again." });
  return dedupe(
    (Array.isArray(list) ? list : [])
      .filter(isEntry)
      .map((x) => ({ id: String(x.category_id ?? ""), name: String(x.category_name || "Unnamed") }))
      .filter((x) => x.id),
    (x) => x.id,
  );
}

export async function liveChannels(c: XtreamCredentials, categoryId?: string | null): Promise<XtreamChannel[]> {
  if (isPlaylist(c)) {
    const all = (await playlistFor(c)).channels;
    return categoryId == null ? all : all.filter((ch) => (ch.group ?? OTHER) === categoryId);
  }
  const list = await askJson<any[]>(api(c, "get_live_streams", categoryId ? { category_id: categoryId } : {}), {
    failure: "Your provider couldn't do that. Try again.",
    timeoutMs: 60_000,
  });
  return dedupe(
    (Array.isArray(list) ? list : [])
      .filter(isEntry)
      .map((x): XtreamChannel | null => {
        const streamId = /^\d+$/.test(String(x.stream_id ?? "").trim()) ? Number(String(x.stream_id).trim()) : -1;
        if (streamId < 0) return null;
        const icon = String(x.stream_icon ?? "");
        const epg = String(x.epg_channel_id ?? "").trim();
        return {
          streamId,
          number: Number(x.num) || 0,
          name: String(x.name || `Channel ${streamId}`),
          icon: icon.startsWith("http") ? icon : null,
          epgChannelId: epg ? epg.toLowerCase() : null,
          archiveDays: String(x.tv_archive) === "1" ? Number(x.tv_archive_duration) || 0 : 0,
        };
      })
      .filter((x): x is XtreamChannel => x !== null),
    (x) => x.streamId,
  );
}

export type StreamFormat = "m3u8" | "ts";

export function streamUrl(c: XtreamCredentials, channel: XtreamChannel, format: StreamFormat): string {
  return channel.url ?? `${c.base}/live/${encodeURIComponent(c.username)}/${encodeURIComponent(c.password)}/${channel.streamId}.${format}`;
}

/** A programme from the channel's archive: the panel's timeshift address, in its own time zone. */
export function catchUpUrl(c: XtreamCredentials, channel: XtreamChannel, start: number, stop: number, timezone: string | null): string | null {
  if (isPlaylist(c) || channel.archiveDays <= 0) return null;
  const minutes = Math.max(1, Math.floor((stop - start + 59) / 60));
  return `${c.base}/timeshift/${encodeURIComponent(c.username)}/${encodeURIComponent(c.password)}/${minutes}/${panelTime(start, timezone)}/${channel.streamId}.ts`;
}

/** "2024-03-05:20-30": a moment as the panel's clock reads it. */
export function panelTime(epochSeconds: number, timezone: string | null): string {
  const parts = new Intl.DateTimeFormat("en-GB", {
    timeZone: timezone || undefined,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
  }).formatToParts(new Date(epochSeconds * 1000));
  const get = (t: string) => parts.find((p) => p.type === t)?.value ?? "00";
  const hour = get("hour") === "24" ? "00" : get("hour");
  return `${get("year")}-${get("month")}-${get("day")}:${hour}-${get("minute")}`;
}

/** The earliest moment that can be watched again, or null for a channel with no archive. */
export const catchUpFrom = (channel: XtreamChannel, now: number) => (channel.archiveDays > 0 ? now - channel.archiveDays * 86_400 : null);

/** The programme at [at], when it's over and still in the archive; else null (play live). */
export function catchUpProgramme(channel: XtreamChannel, listing: Programme[], at: number, now: number): Programme | null {
  const from = catchUpFrom(channel, now);
  if (from == null) return null;
  const p = listing.find((x) => isOnAt(x, at));
  return p && p.stop <= now && p.start >= from ? p : null;
}

// ---------------------------------------------------------------- The guide

/** A channel's whole listing the panel holds, what's been as well as what's coming: the guide's grid. */
export async function epgTable(c: XtreamCredentials, streamId: number): Promise<Programme[]> {
  if (isPlaylist(c)) return [];
  const root = await askJson(api(c, "get_simple_data_table", { stream_id: String(streamId) }), { timeoutMs: 30_000 });
  const listings = Array.isArray(root?.epg_listings) ? root.epg_listings : [];
  const programmes = listings
    .map((e: any): Programme | null => {
      const start = Number(e.start_timestamp) || parsePanelTime(e.start);
      const stop = Number(e.stop_timestamp) || parsePanelTime(e.end);
      if (!start || !stop || stop <= start) return null;
      return { channelId: String(streamId), start, stop, title: decodeField(e.title) || "Untitled", description: decodeField(e.description) || null };
    })
    .filter((p: Programme | null): p is Programme => p !== null);
  // Panels repeat a programme now and then; once each, in order.
  const seen = new Set<number>();
  return stableSort(programmes, (a: Programme, b: Programme) => a.start - b.start).filter((p: Programme) => !seen.has(p.start) && !!seen.add(p.start));
}

/** Now and next for one channel, from the panel; titles come base64, mostly. */
export async function shortEpg(c: XtreamCredentials, streamId: number, limit = 4): Promise<Programme[]> {
  if (isPlaylist(c)) return [];
  const root = await askJson(api(c, "get_short_epg", { stream_id: String(streamId), limit: String(limit) }));
  const listings = Array.isArray(root?.epg_listings) ? root.epg_listings : [];
  const programmes = listings
    .map((e: any): Programme | null => {
      const start = Number(e.start_timestamp) || parsePanelTime(e.start);
      const stop = Number(e.stop_timestamp) || parsePanelTime(e.end);
      if (!start || !stop) return null;
      return { channelId: String(streamId), start, stop, title: decodeField(e.title) || "Untitled", description: decodeField(e.description) || null };
    })
    .filter((p: Programme | null): p is Programme => p !== null);
  return stableSort(programmes, (a: Programme, b: Programme) => a.start - b.start);
}

export function decodeField(raw: unknown): string {
  const text = raw == null ? "" : String(raw);
  if (!text) return "";
  try {
    if (!/^[A-Za-z0-9+/=\s]+$/.test(text)) return text.trim();
    const bytes = atob(text.replace(/\s/g, ""));
    const decoded = decodeURIComponent(Array.from(bytes, (ch) => "%" + ch.charCodeAt(0).toString(16).padStart(2, "0")).join(""));
    return decoded.trim();
  } catch {
    return text.trim();
  }
}

function parsePanelTime(raw: unknown): number {
  const text = raw == null ? "" : String(raw);
  const m = /^(\d{4})-(\d{2})-(\d{2}) (\d{2}):(\d{2}):(\d{2})$/.exec(text);
  if (!m) return 0;
  return Math.floor(new Date(+m[1], +m[2] - 1, +m[3], +m[4], +m[5], +m[6]).getTime() / 1000);
}

/** Where the whole XMLTV guide is: the panel's, or the playlist's own. */
export function xmltvUrl(c: XtreamCredentials): string | null {
  if (isPlaylist(c)) {
    const held = playlist;
    return c.guideUrl ?? (held && held.url === c.playlistUrl ? held.list.guideUrl : null);
  }
  return `${c.base}/xmltv.php?${new URLSearchParams({ username: c.username, password: c.password }).toString()}`;
}

/** "20240305203000 +0100" in epoch seconds. */
export function parseXmltvTime(raw: string | null | undefined): number {
  if (!raw || raw.length < 14 || !/^\d{14}/.test(raw)) return 0;
  const y = +raw.slice(0, 4), mo = +raw.slice(4, 6), d = +raw.slice(6, 8), h = +raw.slice(8, 10), mi = +raw.slice(10, 12), s = +raw.slice(12, 14);
  // A month or day that isn't one is rubbish, not a date rolled into the next month.
  const days = new Date(Date.UTC(y, mo, 0)).getUTCDate();
  if (mo < 1 || mo > 12 || d < 1 || d > days || h > 23 || mi > 59 || s > 59) return 0;
  let seconds = Date.UTC(y, mo - 1, d, h, mi, s) / 1000;
  const zone = /([+-])(\d{2}):?(\d{2})\s*$/.exec(raw.slice(14));
  if (zone) seconds -= (zone[1] === "-" ? -1 : 1) * (+zone[2] * 3600 + +zone[3] * 60);
  return seconds;
}

/**
 * Reads an XMLTV guide as it arrives, programme by programme, without holding the whole
 * of it: a big provider's runs to tens of megabytes.
 */
export class XmltvReader {
  private buffer = "";
  constructor(private emit: (p: Programme) => void, private keep: (channelId: string) => boolean = () => true) {}

  push(chunk: string) {
    this.buffer += chunk;
    let end: number;
    while ((end = this.buffer.indexOf("</programme>")) >= 0) {
      const start = this.buffer.lastIndexOf("<programme", end);
      const block = start >= 0 ? this.buffer.slice(start, end) : "";
      this.buffer = this.buffer.slice(end + "</programme>".length);
      if (block) this.read(block);
    }
    // Nothing worth keeping before the last programme that's begun.
    const open = this.buffer.lastIndexOf("<programme");
    if (open > 0) this.buffer = this.buffer.slice(open);
    else if (open < 0 && this.buffer.length > 4096) this.buffer = this.buffer.slice(-4096);
  }

  private read(block: string) {
    const tag = /<programme\b([^>]*)>/.exec(block);
    if (!tag) return;
    // Whole attribute names only: "start" isn't the end of "catchup-start".
    const attr = (name: string) => new RegExp(`(?:^|\\s)${name}="([^"]*)"`).exec(tag[1])?.[1] ?? null;
    const channel = attr("channel")?.toLowerCase();
    if (!channel || !this.keep(channel)) return;
    const start = parseXmltvTime(attr("start"));
    const stop = parseXmltvTime(attr("stop"));
    if (!start || !stop || stop <= start) return;
    const title = unescapeXml(/<title\b[^>]*>([\s\S]*?)<\/title>/.exec(block)?.[1] ?? "").trim() || "Untitled";
    const desc = unescapeXml(/<desc\b[^>]*>([\s\S]*?)<\/desc>/.exec(block)?.[1] ?? "").trim() || null;
    this.emit({ channelId: channel, start, stop, title, description: desc });
  }
}

/** A playlist's guide, read once and kept for a while: each channel's programmes, by its tvg-id. */
let playlistGuideHeld: { url: string; at: number; byChannel: Map<string, Programme[]> } | null = null;
let playlistGuideReading: { url: string; done: Promise<Map<string, Programme[]>> } | null = null;

/** How long a playlist's guide is kept before it's read again. */
export const PLAYLIST_GUIDE_KEEP_SECONDS = 6 * 3600;

/** When the playlist's guide was last read, in epoch seconds; null before it has been. */
export function playlistGuideAt(c: XtreamCredentials): number | null {
  const url = xmltvUrl(c);
  return url && playlistGuideHeld?.url === url ? playlistGuideHeld.at : null;
}

/**
 * The playlist's whole XMLTV guide, for its own channels and a day either side of now to
 * three days ahead, as the Fire TV keeps it. Read as it arrives; a provider's runs to tens of
 * megabytes, and only what's kept is held.
 */
export async function playlistGuide(c: XtreamCredentials, fresh = false, now = Math.floor(Date.now() / 1000)): Promise<Map<string, Programme[]>> {
  if (!isPlaylist(c)) return new Map();
  const list = await playlistFor(c);
  const url = xmltvUrl(c);
  if (!url) return new Map();
  if (!fresh && playlistGuideHeld?.url === url && now - playlistGuideHeld.at < PLAYLIST_GUIDE_KEEP_SECONDS) return playlistGuideHeld.byChannel;
  if (playlistGuideReading?.url === url) return playlistGuideReading.done;
  const done = (async () => {
    const ids = new Set(list.channels.map((ch) => ch.epgChannelId?.toLowerCase()).filter((id): id is string => !!id));
    const byChannel = new Map<string, Programme[]>();
    const from = now - 86_400;
    const to = now + 3 * 86_400;
    const reader = new XmltvReader((p) => {
      if (p.stop <= from || p.start >= to) return;
      const list = byChannel.get(p.channelId);
      if (list) list.push(p);
      else byChannel.set(p.channelId, [p]);
    }, (id) => ids.has(id));
    await readXmltv(url, (text) => reader.push(text));
    // In order, and each start once: some guides list a programme twice.
    for (const [id, list] of byChannel) {
      const seen = new Set<number>();
      byChannel.set(id, stableSort(list, (a: Programme, b: Programme) => a.start - b.start).filter((p) => !seen.has(p.start) && !!seen.add(p.start)));
    }
    playlistGuideHeld = { url, at: now, byChannel };
    return byChannel;
  })();
  playlistGuideReading = { url, done };
  try {
    return await done;
  } finally {
    if (playlistGuideReading?.done === done) playlistGuideReading = null;
  }
}

/** Whether the playlist names a guide: null until the playlist has been read and that's known. */
export function guideNamed(c: XtreamCredentials): boolean | null {
  if (!isPlaylist(c)) return true;
  if (c.guideUrl) return true;
  const held = playlist;
  return held && held.url === c.playlistUrl ? !!held.list.guideUrl : null;
}

/** The kept guide forgotten, as when signing out. */
export function forgetPlaylistGuide() {
  playlistGuideHeld = null;
  playlistGuideReading = null;
}

/** A channel's programmes from the playlist's guide, under the channel's stream id as the panel's are. */
export function playlistListing(byChannel: Map<string, Programme[]>, channel: XtreamChannel): Programme[] {
  const id = channel.epgChannelId?.toLowerCase();
  const list = id ? byChannel.get(id) : undefined;
  return list ? list.map((p) => ({ ...p, channelId: String(channel.streamId) })) : [];
}

/** Now and what's next from a listing, as the panel's short guide gives them. */
export function nowAndNext(list: Programme[], now: number, count = 2): Programme[] {
  const at = list.findIndex((p) => p.stop > now);
  return at < 0 ? [] : list.slice(at, at + count);
}

/** The guide's text a piece at a time; a gzipped file opened where the TV can. */
async function readXmltv(url: string, push: (text: string) => void) {
  const response = await ask(url, { timeoutMs: 120_000, failure: "Couldn't download the TV guide." });
  const decoder = new TextDecoder("utf-8");
  const body = response.body;
  if (!body || typeof body.getReader !== "function") {
    push(await response.text());
    return;
  }
  const raw = body.getReader();
  const first = await raw.read();
  if (first.done || !first.value) return;
  const gzip = first.value[0] === 0x1f && first.value[1] === 0x8b;
  let reader: ReadableStreamDefaultReader<Uint8Array> = raw;
  let head: Uint8Array | null = first.value;
  if (gzip) {
    if (typeof DecompressionStream === "undefined") {
      void raw.cancel();
      throw new HttpError("This TV can't open the TV guide: it's compressed. Ask your provider for an uncompressed address.", 0);
    }
    const replay = new ReadableStream<Uint8Array>({
      start(controller) { controller.enqueue(first.value!); },
      async pull(controller) {
        const next = await raw.read();
        if (next.done) controller.close();
        else controller.enqueue(next.value);
      },
      cancel() { void raw.cancel(); },
    });
    reader = replay.pipeThrough(new DecompressionStream("gzip") as unknown as ReadableWritablePair<Uint8Array, Uint8Array>).getReader();
    head = null;
  }
  try {
    if (head) push(decoder.decode(head, { stream: true }));
    for (;;) {
      const next = await reader.read();
      if (next.done) break;
      push(decoder.decode(next.value, { stream: true }));
    }
  } catch {
    throw new HttpError("The TV guide stopped partway. Try again.", 0);
  }
  push(decoder.decode());
}

function unescapeXml(text: string): string {
  // CDATA is taken as written; only what's outside it has entities to undo.
  return text
    .split(/(<!\[CDATA\[[\s\S]*?\]\]>)/)
    .map((part) => (part.startsWith("<![CDATA[") ? part.slice(9, -3) : entities(part)))
    .join("");
}

function entities(text: string): string {
  const char = (code: number) => (code > 0 && code <= 0x10ffff ? String.fromCodePoint(code) : "");
  return text
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&quot;/g, '"')
    .replace(/&apos;/g, "'")
    .replace(/&#x([0-9a-f]+);/gi, (_, n) => char(parseInt(n, 16)))
    .replace(/&#(\d+);/g, (_, n) => char(+n))
    .replace(/&amp;/g, "&");
}

function isEntry(x: unknown): x is Record<string, any> {
  return !!x && typeof x === "object";
}

function dedupe<T, K>(list: T[], key: (t: T) => K): T[] {
  const seen = new Set<K>();
  return list.filter((x) => (seen.has(key(x)) ? false : (seen.add(key(x)), true)));
}
