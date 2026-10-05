import { ask, askJson, HttpError } from "../core/http";
import { stableSort } from "../core/sort";
import { qualityBadges, versionDetail, versionLabel } from "../core/quality";

/*
 * Plex, as the Android app talks to it (PlexApi.kt): plex.tv for signing in and the list
 * of servers, then each server for its libraries, pages, files and where things are up
 * to. The same requests, the same answers read the same way, so the two apps agree.
 */

export interface PlexItem {
  ratingKey: string;
  title: string;
  titleSort?: string | null;
  type: string;
  thumb: string | null;
  art: string | null;
  summary: string | null;
  year: number | null;
  index: number | null;
  parentIndex: number | null;
  parentRatingKey: string | null;
  parentTitle: string | null;
  grandparentRatingKey: string | null;
  grandparentTitle: string | null;
  grandparentThumb: string | null;
  durationMs: number;
  viewOffsetMs: number;
  leafCount: number;
  viewedLeafCount: number;
  viewCount: number;
  addedAt: number;
  lastViewedAt: number;
  logo?: string | null;
  qualities: string[];
  airDate?: string | null;
  librarySectionId: string | null;
  /** Which server it's from. Rows hold several servers' titles at once. */
  serverBase: string | null;
}

export interface PlexRole {
  name: string;
  role: string | null;
  thumb: string | null;
  id: string | null;
}

export interface PlexVersion {
  label: string;
  detail: string | null;
}

export interface PlexDetail {
  ratingKey: string;
  type: string;
  title: string;
  summary: string | null;
  tagline: string | null;
  year: number | null;
  durationMs: number;
  viewOffsetMs: number;
  contentRating: string | null;
  rating: number | null;
  audienceRating: number | null;
  airDate: string | null;
  viewCount: number;
  viewedLeafCount: number;
  studio: string | null;
  thumb: string | null;
  art: string | null;
  theme: string | null;
  genres: string[];
  directors: string[];
  roles: PlexRole[];
  writers: string[];
  childCount: number;
  leafCount: number;
  grandparentTitle: string | null;
  index: number | null;
  parentIndex: number | null;
  logo: string | null;
  qualities: string[];
  versions: PlexVersion[];
  guid: string | null;
  onDeckKey: string | null;
  onDeckSeasonKey: string | null;
}

export interface PlexServer {
  name: string;
  accessToken: string;
  connections: string[];
  owned: boolean;
}

export interface PlexSection {
  key: string;
  title: string;
  type: string;
}

export interface PlexGenre {
  id: string;
  title: string;
}

export interface PlexLetter {
  letter: string;
  count: number;
}

export interface PlexHomeUser {
  uuid: string;
  title: string;
  thumb: string | null;
  protected: boolean;
  admin: boolean;
  restricted: boolean;
}

export interface PlexPin {
  id: number;
  code: string;
}

export interface PlexMarker {
  type: string;
  startMs: number;
  endMs: number;
}

export interface PlexChapter {
  title: string;
  startMs: number;
  endMs: number;
  thumbUrl: string | null;
}

export interface PlexSubtitle {
  id: string;
  label: string;
  url: string;
  codec: string;
  language: string | null;
}

export interface PlexStream {
  id: string;
  language: string | null;
  selected: boolean;
  external: boolean;
  /** Only the parts in another language: shown even with subtitles off. */
  forced: boolean;
  label: string;
}

export interface PlexPlayback {
  url: string;
  subtitles: PlexSubtitle[];
  markers: PlexMarker[];
  audioCodec: string | null;
  audioChannels: number;
  previewUrl: string | null;
  chapters: PlexChapter[];
  partId: number | null;
  audioStreams: PlexStream[];
  subtitleStreams: PlexStream[];
  container: string | null;
  videoCodec: string | null;
}

export interface PlexExtra {
  ratingKey: string;
  title: string;
  durationMs: number;
}

export interface PlexPerson {
  id: string;
  name: string;
  thumb: string | null;
  serverBase: string | null;
}

export interface PlexFound {
  items: PlexItem[];
  people: PlexPerson[];
  collections: PlexItem[];
}

export interface PlexOnlineSubtitle {
  key: string;
  title: string;
  provider: string | null;
  language: string | null;
  codec: string | null;
  hearingImpaired: boolean;
  forced: boolean;
}

export interface PlexIndexEntry {
  ratingKey: string;
  serverBase: string | null;
  title: string;
  originalTitle: string | null;
  year: number | null;
  guids: string[];
}

export const TYPE_MOVIE = 1;
export const TYPE_SHOW = 2;
export const TYPE_EPISODE = 4;
const AUDIO_STREAM = 2;
const SUBTITLE_STREAM = 3;

const PLEX_TV = "https://plex.tv";
const DISCOVER = "https://discover.provider.plex.tv";
const PRODUCT = "Reely TV";

/** Who's asking: set once at start, sent with everything. */
export const identity = {
  clientId: "",
  version: "0.1.0",
  platform: "webOS",
  device: "LG TV",
  deviceName: "Reely on LG TV",
};

export const isPlayable = (item: Pick<PlexItem, "type">) => item.type === "movie" || item.type === "episode";

export function isWatched(item: Pick<PlexItem, "type" | "viewCount" | "leafCount" | "viewedLeafCount">): boolean {
  if (item.type === "movie" || item.type === "episode") return item.viewCount > 0;
  if (item.type === "show" || item.type === "season") return item.leafCount > 0 && item.viewedLeafCount >= item.leafCount;
  return false;
}

/** Where it was left, as a fraction; null when it hasn't been started. */
export function resumeFraction(item: Pick<PlexItem, "viewOffsetMs" | "durationMs">): number | null {
  return item.viewOffsetMs > 0 && item.durationMs > 0 ? Math.min(1, Math.max(0, item.viewOffsetMs / item.durationMs)) : null;
}

/** "S2 · E7", a year, an episode count. */
export function caption(item: PlexItem): string | null {
  switch (item.type) {
    case "episode": {
      const text = [item.parentIndex != null ? `S${item.parentIndex}` : null, item.index != null ? `E${item.index}` : null].filter(Boolean).join(" · ");
      return text || null;
    }
    case "season":
      return item.leafCount > 0 ? `${item.leafCount} episodes` : null;
    case "collection":
      return item.leafCount > 0 ? (item.leafCount === 1 ? "1 title" : `${item.leafCount} titles`) : null;
    default:
      return item.year != null ? String(item.year) : null;
  }
}

/** Unique across servers, which a rating key is not. */
export const listKey = (item: Pick<PlexItem, "serverBase" | "ratingKey">) => (item.serverBase ?? "") + "|" + item.ratingKey;

/** The show for an episode, else the title. */
export const rowTitle = (item: PlexItem) => (item.type === "episode" ? item.grandparentTitle ?? item.title : item.title);

function headers(token?: string | null): Record<string, string> {
  const h: Record<string, string> = {
    accept: "application/json",
    "X-Plex-Product": PRODUCT,
    "X-Plex-Version": identity.version,
    "X-Plex-Client-Identifier": identity.clientId,
    "X-Plex-Platform": identity.platform,
    "X-Plex-Device": identity.device,
    "X-Plex-Device-Name": identity.deviceName,
  };
  if (token) h["X-Plex-Token"] = token;
  return h;
}

const enc = encodeURIComponent;

// ---------------------------------------------------------------- Signing in

/** A sign-in PIN: the short one to type at plex.tv/link, or a strong one only a link carries. */
export async function createPin(strong = false): Promise<PlexPin> {
  const json = await askJson(`${PLEX_TV}/api/v2/pins`, {
    method: "POST",
    headers: { ...headers(), "content-type": "application/x-www-form-urlencoded" },
    body: `strong=${strong}`,
    failure: "Couldn't get a sign-in code from Plex. Try again.",
  });
  return { id: Number(json.id), code: String(json.code) };
}

/** Plex's own sign-in page for a strong PIN, for the QR code. */
export function authUrl(code: string): string {
  return `https://app.plex.tv/auth#?clientID=${enc(identity.clientId)}&code=${enc(code)}&context%5Bdevice%5D%5Bproduct%5D=${enc(PRODUCT)}`;
}

/** The account's token once the code's been entered; null while it's still waiting. */
export async function claimPin(id: number): Promise<string | null> {
  try {
    const json = await askJson(`${PLEX_TV}/api/v2/pins/${id}`, { headers: headers() });
    const token = json.authToken;
    return typeof token === "string" && token && token !== "null" ? token : null;
  } catch {
    return null;
  }
}

export async function account(token: string): Promise<PlexHomeUser | null> {
  try {
    return homeUserOf(await askJson(`${PLEX_TV}/api/v2/user`, { headers: headers(token) }));
  } catch {
    return null;
  }
}

export async function homeUsers(token: string): Promise<PlexHomeUser[]> {
  try {
    const json = await askJson(`${PLEX_TV}/api/v2/home/users`, { headers: headers(token) });
    return homeUsersFrom(json);
  } catch {
    return [];
  }
}

/** The users in a home/users answer: the list, or the Home around it. */
export function homeUsersFrom(json: any): PlexHomeUser[] {
  const users = Array.isArray(json) ? json : json?.users;
  if (!Array.isArray(users)) return [];
  return users.map(homeUserOf).filter((u): u is PlexHomeUser => u !== null);
}

function homeUserOf(entry: any): PlexHomeUser | null {
  const uuid = str(entry?.uuid);
  if (!uuid) return null;
  return {
    uuid,
    title: str(entry.title) || str(entry.username) || "Plex user",
    thumb: str(entry.thumb).startsWith("http") ? str(entry.thumb) : null,
    protected: entry.protected === true,
    admin: entry.admin === true,
    restricted: entry.restricted === true,
  };
}

/** Becomes another member of the Home; their PIN when they have one. */
export async function switchHomeUser(token: string, uuid: string, pin: string | null): Promise<string> {
  const url = `${PLEX_TV}/api/v2/home/users/${uuid}/switch` + (pin ? `?pin=${enc(pin)}` : "");
  let response: Response;
  try {
    response = await ask(url, { method: "POST", headers: headers(token) });
  } catch (error) {
    if (error instanceof HttpError && (error.status === 401 || error.status === 403)) {
      throw new Error(pin ? "That PIN isn't right. Try again." : "Plex didn't allow switching to this profile.");
    }
    throw new Error("Couldn't switch profiles. Try again.");
  }
  const json = await response.json().catch(() => ({}));
  const next = str(json.authToken);
  if (!next || next === "null") throw new Error("Couldn't switch profiles. Try again.");
  return next;
}

// ---------------------------------------------------------------- Servers

/** The account's servers and those shared with it, its own first. */
export async function servers(token: string): Promise<PlexServer[]> {
  const json = await askJson(`${PLEX_TV}/api/v2/resources?includeHttps=1&includeRelay=1`, {
    headers: headers(token),
    failure: "Couldn't load your Plex servers. Try again.",
  });
  return serversFrom(json, token);
}

export function serversFrom(json: any, token: string): PlexServer[] {
  if (!Array.isArray(json)) return [];
  const list: PlexServer[] = [];
  for (const resource of json) {
    if (!str(resource?.provides).includes("server")) continue;
    const connections = connectionOrder(
      (Array.isArray(resource.connections) ? resource.connections : []).map((c: any) => ({
        uri: str(c.uri),
        address: str(c.address),
        port: Number(c.port) || 32400,
        local: c.local === true,
        relay: c.relay === true,
      })),
    );
    if (connections.length === 0) continue;
    list.push({
      name: str(resource.name) || "Plex Media Server",
      accessToken: str(resource.accessToken) || token,
      connections,
      owned: resource.owned === true,
    });
  }
  // Signing in connects to the first that answers: the account's own before a friend's.
  return stableSort(list, (a, b) => Number(b.owned) - Number(a.owned));
}

export interface PlexConnection {
  uri: string;
  address: string;
  port: number;
  local: boolean;
  relay: boolean;
}

/**
 * Best first: at home, then the internet, then Plex's relay; and each home address again
 * over plain http, for routers that won't look up plex.direct names.
 */
export function connectionOrder(connections: PlexConnection[]): string[] {
  const sorted = stableSort(
    connections.filter((c) => c.uri),
    (a, b) => Number(a.relay) - Number(b.relay) || Number(!a.local) - Number(!b.local),
  );
  const out: string[] = [];
  for (const c of sorted) {
    if (!out.includes(c.uri)) out.push(c.uri);
    if (c.local && !c.relay && c.address) {
      const plain = `http://${c.address}:${c.port}`;
      if (!out.includes(plain)) out.push(plain);
    }
  }
  return out;
}

export async function reachable(server: PlexServer, uri: string): Promise<boolean> {
  try {
    await ask(`${uri}/identity`, { headers: { accept: "application/json", "X-Plex-Token": server.accessToken }, timeoutMs: 5_000 });
    return true;
  } catch {
    return false;
  }
}

/** The best address that answers. All asked at once; the best of those that answer wins. */
export async function firstReachable(server: PlexServer): Promise<string | null> {
  const probes = server.connections.map((uri) => reachable(server, uri));
  for (let i = 0; i < probes.length; i++) {
    if (await probes[i]) return server.connections[i];
  }
  return null;
}

// ---------------------------------------------------------------- Libraries

async function container(url: string, token: string): Promise<any> {
  const json = await askJson(url, { headers: headers(token), failure: "Your Plex server couldn't do that. Try again." });
  return json?.MediaContainer ?? {};
}

export async function sections(base: string, token: string): Promise<PlexSection[]> {
  const c = await container(`${base}/library/sections`, token);
  return arr(c.Directory)
    .map((d: any) => ({ key: str(d.key), title: str(d.title), type: str(d.type) }))
    .filter((s: PlexSection) => s.key);
}

async function filterValues(base: string, token: string, section: string, field: string, type: number): Promise<PlexGenre[]> {
  const c = await container(`${base}/library/sections/${section}/${field}?type=${type}`, token);
  return arr(c.Directory)
    .map((d: any) => ({ id: str(d.key), title: str(d.title) }))
    .filter((g: PlexGenre) => g.id && g.title);
}

export const genres = (base: string, token: string, section: string, type: number) => filterValues(base, token, section, "genre", type);

export async function decades(base: string, token: string, section: string, type: number): Promise<PlexGenre[]> {
  return stableSort(await filterValues(base, token, section, "decade", type), (a, b) => (Number(b.id) || 0) - (Number(a.id) || 0));
}

export async function firstCharacters(base: string, token: string, section: string, type: number, filters: string): Promise<PlexLetter[]> {
  const c = await container(`${base}/library/sections/${section}/firstCharacter?type=${type}${filters}`, token);
  return arr(c.Directory)
    .map((d: any) => {
      let letter = str(d.title);
      if (!letter) {
        try {
          letter = decodeURIComponent(str(d.key));
        } catch {
          letter = "";
        }
      }
      return { letter, count: Number(d.size) || 0 };
    })
    .filter((l: PlexLetter) => l.letter && l.count > 0);
}

export async function items(base: string, token: string, path: string, limit = 200, offset = 0): Promise<PlexItem[]> {
  const sep = path.includes("?") ? "&" : "?";
  const c = await container(`${base}${path}${sep}X-Plex-Container-Start=${offset}&X-Plex-Container-Size=${limit}`, token);
  return arr(c.Metadata).map((m: any) => ({ ...parseItem(m), serverBase: base }));
}

/** Continue Watching as Plex's own apps build it; On Deck for a server too old for that. */
export async function continueWatching(base: string, token: string): Promise<PlexItem[]> {
  const c = await container(`${base}/hubs?identifier=${enc("home.continue,home.ondeck")}&count=40`, token);
  const hubs = c.Hub;
  if (!Array.isArray(hubs)) return items(base, token, "/library/onDeck", 40);
  return metadataOf(hubs)
    .filter(isPlayable)
    .map((item: PlexItem) => ({ ...item, serverBase: base }));
}

export const recentlyAdded = (base: string, token: string, section: string, type: number, limit = 60, offset = 0) =>
  items(base, token, `/library/sections/${section}/all?type=${type}&sort=addedAt:desc`, limit, offset);

export const children = (base: string, token: string, ratingKey: string) => items(base, token, `/library/metadata/${ratingKey}/children`, 400);

export async function episodesOf(base: string, token: string, ratingKey: string): Promise<PlexItem[]> {
  return (await items(base, token, `/library/metadata/${ratingKey}/allLeaves`, 2000)).filter((i) => i.type === "episode");
}

/**
 * Which episode to play next: the one part watched, else the first unwatched after the
 * last watched, else the first unwatched, else the first. Specials aside, unless they're
 * all there is.
 */
export function nextEpisode<T extends PlexItem>(episodes: T[]): T | null {
  const partly = episodes.find((e) => resumeFraction(e) !== null && !isWatched(e));
  if (partly) return partly;
  const main = episodes.filter((e) => e.parentIndex != null);
  const list = main.length ? main : episodes;
  let lastWatched = -1;
  list.forEach((e, i) => {
    if (isWatched(e)) lastWatched = i;
  });
  if (lastWatched >= 0) {
    const after = list.slice(lastWatched + 1).find((e) => !isWatched(e));
    if (after) return after;
  }
  return list.find((e) => !isWatched(e)) ?? list[0] ?? null;
}

export async function playlists(base: string, token: string): Promise<PlexItem[]> {
  const c = await container(`${base}/playlists?playlistType=video`, token);
  return arr(c.Metadata)
    .filter((m: any) => Number(m.leafCount) > 0)
    .map((m: any) => ({ ...parseItem(m), serverBase: base, thumb: str(m.composite) || str(m.thumb) || null }));
}

export const playlistItems = (base: string, token: string, ratingKey: string) => items(base, token, `/playlists/${ratingKey}/items`, 500);
export const collections = (base: string, token: string, section: string) => items(base, token, `/library/sections/${section}/collections`, 500);
export const collectionItems = (base: string, token: string, ratingKey: string) => items(base, token, `/library/collections/${ratingKey}/children`, 500);
export const withActor = (base: string, token: string, section: string, type: number, personId: string) =>
  items(base, token, `/library/sections/${section}/all?type=${type}&actor=${personId}&sort=originallyAvailableAt:desc`, 300);

export async function libraryEntries(base: string, token: string, section: string, type: number): Promise<PlexIndexEntry[]> {
  const c = await container(`${base}/library/sections/${section}/all?type=${type}&includeGuids=1&X-Plex-Container-Start=0&X-Plex-Container-Size=100000`, token);
  return entriesIn(c.Metadata, base);
}

/** Every outside id a library's titles carry ("tmdb://603", "tvdb://81189"): what Requests marks as here. */
export async function libraryGuids(base: string, token: string, section: string, type: number): Promise<Set<string>> {
  return guidsOf(await libraryEntries(base, token, section, type));
}

export function guidsOf(entries: PlexIndexEntry[]): Set<string> {
  const out = new Set<string>();
  for (const e of entries) for (const g of e.guids) out.add(g);
  return out;
}

export function entriesIn(metadata: any, base: string | null): PlexIndexEntry[] {
  return arr(metadata).map((m: any) => ({
    ratingKey: str(m.ratingKey),
    serverBase: base,
    title: str(m.title),
    originalTitle: str(m.originalTitle) || null,
    year: Number(m.year) > 0 ? Number(m.year) : null,
    guids: arr(m.Guid).map((g: any) => str(g?.id)).filter(Boolean),
  }));
}

// ---------------------------------------------------------------- A title's page

export async function detail(base: string, token: string, ratingKey: string): Promise<PlexDetail | null> {
  const c = await container(`${base}/library/metadata/${ratingKey}?includeOnDeck=1`, token);
  const entry = arr(c.Metadata)[0];
  return entry ? parseDetail(entry) : null;
}

export function parseDetail(entry: any): PlexDetail {
  const onDeck = arr(entry.OnDeck?.Metadata)[0];
  const type = str(entry.type);
  return {
    ratingKey: str(entry.ratingKey),
    type,
    title: str(entry.title),
    summary: str(entry.summary) || null,
    tagline: str(entry.tagline) || null,
    year: pos(entry.year),
    durationMs: Number(entry.duration) || 0,
    viewOffsetMs: Number(entry.viewOffset) || 0,
    contentRating: str(entry.contentRating) || null,
    rating: Number(entry.rating) > 0 ? Number(entry.rating) : null,
    audienceRating: Number(entry.audienceRating) > 0 ? Number(entry.audienceRating) : null,
    airDate: str(entry.originallyAvailableAt) || null,
    viewCount: Number(entry.viewCount) || 0,
    viewedLeafCount: Number(entry.viewedLeafCount) || 0,
    studio: str(entry.studio) || null,
    thumb: str(entry.thumb) || null,
    art: str(entry.art) || null,
    theme: str(entry.theme) || null,
    genres: tags(entry, "Genre"),
    directors: tags(entry, "Director"),
    roles: arr(entry.Role)
      .map((r: any) => ({ name: str(r.tag), role: str(r.role) || null, thumb: str(r.thumb) || null, id: str(r.id) || null }))
      .filter((r: PlexRole) => r.name),
    writers: tags(entry, "Writer"),
    childCount: Number(entry.childCount) || 0,
    leafCount: Number(entry.leafCount) > 0 ? Number(entry.leafCount) : type === "collection" ? Number(entry.childCount) || 0 : 0,
    grandparentTitle: str(entry.grandparentTitle) || null,
    index: pos(entry.index),
    parentIndex: pos(entry.parentIndex),
    logo: logoOf(entry),
    qualities: qualitiesOf(entry),
    versions: versionsOf(entry),
    guid: str(entry.guid).startsWith("plex://") ? str(entry.guid) : null,
    onDeckKey: str(onDeck?.ratingKey) || null,
    onDeckSeasonKey: str(onDeck?.parentRatingKey) || null,
  };
}

export function isShow(detail: Pick<PlexDetail, "type">) {
  return detail.type === "show";
}

/** "2014 · 2h 18m · TV-MA · Studio". */
export function facts(d: PlexDetail): string {
  const minutes = d.durationMs > 0 ? Math.floor(d.durationMs / 60_000) : 0;
  const duration = minutes > 0 ? (minutes >= 60 ? `${Math.floor(minutes / 60)}h ${minutes % 60}m` : `${minutes}m`) : null;
  return [d.year != null ? String(d.year) : null, duration, d.contentRating, d.studio].filter(Boolean).join("  ·  ");
}

function versionsOf(entry: any): PlexVersion[] {
  const media = arr(entry.Media);
  if (media.length < 2) return [];
  return media.map((file: any) => {
    const part = arr(file.Part)[0];
    const video = arr(part?.Stream).find((s: any) => Number(s.streamType) === 1);
    return {
      label: versionLabel(str(file.videoResolution) || null, video?.DOVIPresent === true, str(video?.colorTrc) || null),
      detail: versionDetail(str(file.videoCodec), str(file.audioCodec), Number(file.audioChannels) || 0, Number(file.bitrate) || 0, Number(part?.size) || 0),
    };
  });
}

/** The file to play, its subtitles, markers and chapters. */
export async function playback(base: string, token: string, ratingKey: string, mediaIndex = 0): Promise<PlexPlayback | null> {
  const c = await container(`${base}/library/metadata/${ratingKey}?includeMarkers=1&includeChapters=1`, token);
  const metadata = arr(c.Metadata)[0];
  return metadata ? parsePlayback(metadata, base, token, mediaIndex) : null;
}

export function parsePlayback(metadata: any, base: string, token: string, mediaIndex = 0): PlexPlayback | null {
  const files = arr(metadata.Media);
  const media = files[mediaIndex] ?? files[0];
  if (!media) return null;
  const part = arr(media.Part)[0];
  const key = str(part?.key);
  if (!key) return null;
  const streams = arr(part.Stream);
  const audio = streams.filter((s: any) => Number(s.streamType) === AUDIO_STREAM);
  const chosenAudio = audio.find(isSelected) ?? audio[0];
  const partId = Number(part.id) > 0 ? Number(part.id) : null;
  return {
    url: `${base}${key}?X-Plex-Token=${token}`,
    subtitles: streams
      .filter((s: any) => Number(s.streamType) === SUBTITLE_STREAM && str(s.key))
      .map((s: any) => ({
        id: str(s.id),
        label: str(s.displayTitle) || str(s.language) || "Subtitles",
        url: `${base}${str(s.key)}?X-Plex-Token=${token}`,
        codec: str(s.codec).toLowerCase(),
        language: str(s.languageTag) || str(s.language) || null,
      }))
      .filter((s: PlexSubtitle) => ["srt", "subrip", "vtt", "webvtt", "ass", "ssa"].includes(s.codec)),
    markers: markerArray(metadata)
      .map((m: any) => ({ type: str(m.type), startMs: num(m.startTimeOffset, -1), endMs: num(m.endTimeOffset, -1) }))
      .filter((m: PlexMarker) => m.type && m.startMs >= 0 && m.endMs > m.startMs),
    audioCodec: (str(chosenAudio?.codec) || str(media.audioCodec)).toLowerCase() || null,
    audioChannels: Number(chosenAudio?.channels) || Number(media.audioChannels) || 0,
    previewUrl: partId != null ? `${base}/library/parts/${partId}/indexes/sd/{ms}?X-Plex-Token=${token}` : null,
    chapters: chaptersOf(metadata, base, token),
    partId,
    audioStreams: streamsOf(streams, AUDIO_STREAM),
    subtitleStreams: streamsOf(streams, SUBTITLE_STREAM),
    container: str(media.container).toLowerCase() || null,
    videoCodec: str(media.videoCodec).toLowerCase() || null,
  };
}

export function streamsOf(streams: any[], type: number): PlexStream[] {
  return streams
    .filter((s) => Number(s.streamType) === type && str(s.id))
    .map((s) => ({
      id: str(s.id),
      language: str(s.languageTag) || str(s.languageCode) || null,
      selected: isSelected(s),
      external: !!str(s.key),
      forced: isSet(s?.forced),
      label: str(s.displayTitle) || str(s.language) || (type === AUDIO_STREAM ? "Sound" : "Subtitles"),
    }));
}

function isSelected(stream: any): boolean {
  return isSet(stream?.selected);
}

/** Servers have said a flag as a boolean, a number and a string. */
function isSet(v: any): boolean {
  return v === true || v === 1 || v === "1" || (typeof v === "string" && v.toLowerCase() === "true");
}

function markerArray(metadata: any): any[] {
  const m = metadata.Marker;
  return Array.isArray(m) ? m : m && typeof m === "object" ? [m] : [];
}

function chaptersOf(metadata: any, base: string, token: string): PlexChapter[] {
  const raw = Array.isArray(metadata.Chapter) ? metadata.Chapter : metadata.Chapter ? [metadata.Chapter] : [];
  const chapters = stableSort(raw
    .map((c: any, i: number): PlexChapter => {
      const thumb = str(c.thumb) || null;
      return {
        title: str(c.tag) || `Chapter ${Number(c.index) || i + 1}`,
        startMs: Number(c.startTimeOffset) || 0,
        endMs: Number(c.endTimeOffset) || 0,
        thumbUrl: thumb ? (thumb.startsWith("http") ? thumb : `${base}${thumb}?X-Plex-Token=${token}`) : null,
      };
    }), (a: PlexChapter, b: PlexChapter) => a.startMs - b.startMs);
  return chapters.length > 1 ? chapters : [];
}

/** Where playback is, told to the server: what makes Continue Watching right everywhere. */
export async function reportTimeline(
  base: string,
  token: string,
  ratingKey: string,
  positionMs: number,
  durationMs: number,
  state: "playing" | "paused" | "stopped",
  sessionId: string,
): Promise<void> {
  const url =
    `${base}/:/timeline?ratingKey=${ratingKey}&key=${enc(`/library/metadata/${ratingKey}`)}` +
    `&identifier=com.plexapp.plugins.library&state=${state}&time=${Math.floor(positionMs)}&duration=${Math.floor(durationMs)}` +
    `&playbackTime=${Math.floor(positionMs)}&playQueueItemID=-1`;
  try {
    await ask(url, { headers: { ...headers(token), "X-Plex-Session-Identifier": sessionId } });
  } catch {
    /* a missed report is caught up by the next */
  }
}

/** The server converting the file to HLS, from the start; the player seeks. */
export function transcodeUrl(
  base: string, token: string, ratingKey: string, sessionId: string, maxBitrateKbps: number, resolution: string, mediaIndex = 0,
  /** "burn" draws the chosen subtitles into the picture; "none" leaves them to the app. */
  subtitles: "burn" | "none" = "burn",
  /** The size burned subtitles are drawn at, in percent. */
  subtitleSize = 100,
  /**
   * The file's video codec. Only H.264 is passed through as it is: anything else (HEVC,
   * say) Plex makes H.264 of, as the TV's own player can't play it from Plex's stream, and
   * playback failed with the file's HEVC copied into it.
   */
  videoCodec: string | null = null,
): string {
  const bitrate = maxBitrateKbps > 0 ? `&maxVideoBitrate=${maxBitrateKbps}` : "";
  const copyVideo = !videoCodec || videoCodec.toLowerCase() === "h264";
  return (
    `${base}/video/:/transcode/universal/start.m3u8?path=${enc(`/library/metadata/${ratingKey}`)}&mediaIndex=${mediaIndex}&partIndex=0` +
    `&protocol=hls&fastSeek=1&directPlay=0&directStream=${copyVideo ? 1 : 0}&subtitles=${subtitles}&subtitleSize=${subtitleSize}&audioBoost=100&videoQuality=100` +
    `&videoResolution=${resolution}${bitrate}&session=${sessionId}` +
    `&X-Plex-Client-Identifier=${enc(identity.clientId)}&X-Plex-Platform=${enc(identity.platform)}&X-Plex-Product=${enc(PRODUCT)}&X-Plex-Token=${token}`
  );
}

export async function stopTranscode(base: string, token: string, sessionId: string): Promise<void> {
  try {
    await ask(`${base}/video/:/transcode/universal/stop?session=${sessionId}&X-Plex-Token=${token}`);
  } catch {
    /* it times out by itself */
  }
}

export async function selectStream(base: string, token: string, partId: number, audioStreamId?: string | null, subtitleStreamId?: string | null): Promise<boolean> {
  const choice = [audioStreamId ? `audioStreamID=${audioStreamId}` : null, subtitleStreamId != null ? `subtitleStreamID=${subtitleStreamId}` : null].filter(Boolean);
  if (!choice.length) return false;
  try {
    await ask(`${base}/library/parts/${partId}?${choice.join("&")}&allParts=1`, { method: "PUT", headers: headers(token) });
    return true;
  } catch {
    return false;
  }
}

export async function searchSubtitles(base: string, token: string, ratingKey: string, language: string): Promise<PlexOnlineSubtitle[]> {
  const c = await container(`${base}/library/metadata/${ratingKey}/subtitles?language=${language}&hearingImpaired=0&forced=0`, token);
  return arr(c.Stream)
    .filter((s: any) => str(s.key))
    .map((s: any) => ({
      key: str(s.key),
      title: str(s.title) || str(s.displayTitle) || str(s.languageTag) || "Subtitles",
      provider: str(s.providerTitle) || null,
      language: str(s.languageCode) || str(s.languageTag) || null,
      codec: str(s.codec) || null,
      hearingImpaired: s.hearingImpaired === true,
      forced: s.forced === true,
    }));
}

export async function addSubtitle(base: string, token: string, ratingKey: string, subtitle: PlexOnlineSubtitle, language: string): Promise<boolean> {
  const query = [
    `key=${enc(subtitle.key)}`,
    subtitle.codec ? `codec=${enc(subtitle.codec)}` : null,
    `language=${enc(subtitle.language ?? language)}`,
    `hearingImpaired=${subtitle.hearingImpaired ? 1 : 0}`,
    `forced=${subtitle.forced ? 1 : 0}`,
    subtitle.provider ? `providerTitle=${enc(subtitle.provider)}` : null,
  ]
    .filter(Boolean)
    .join("&");
  try {
    await ask(`${base}/library/metadata/${ratingKey}/subtitles?${query}`, { method: "PUT", headers: headers(token) });
    return true;
  } catch {
    return false;
  }
}

// ---------------------------------------------------------------- Search, extras, related

export async function searchAll(base: string, token: string, query: string): Promise<PlexFound> {
  if (!query.trim()) return { items: [], people: [], collections: [] };
  const c = await container(`${base}/hubs/search?query=${enc(query.trim())}&limit=30`, token);
  const hubs = arr(c.Hub);
  return { items: itemsFromHubs(hubs, base), people: peopleFromHubs(hubs, base), collections: itemsFromHubs(hubs, base, ["collection"]) };
}

export function itemsFromHubs(hubs: any[], base: string, wanted = ["movie", "show", "episode"]): PlexItem[] {
  const seen = new Set<string>();
  const out: PlexItem[] = [];
  for (const hub of hubs) {
    for (const m of arr(hub?.Metadata)) {
      const item = { ...parseItem(m), serverBase: base };
      if (!wanted.includes(item.type) || !item.ratingKey || seen.has(item.ratingKey)) continue;
      seen.add(item.ratingKey);
      out.push(item);
    }
  }
  return out;
}

export function peopleFromHubs(hubs: any[], base: string): PlexPerson[] {
  const seen = new Set<string>();
  const out: PlexPerson[] = [];
  for (const hub of hubs) {
    if (str(hub?.type) !== "actor") continue;
    for (const p of arr(hub.Directory ?? hub.Metadata)) {
      const id = str(p.id);
      const name = str(p.tag) || str(p.title);
      if (!id || !name || seen.has(id)) continue;
      seen.add(id);
      out.push({ id, name, thumb: str(p.thumb) || null, serverBase: base });
    }
  }
  return out;
}

export async function trailers(base: string, token: string, ratingKey: string): Promise<PlexExtra[]> {
  try {
    const c = await container(`${base}/library/metadata/${ratingKey}/extras`, token);
    return arr(c.Metadata)
      .filter((m: any) => str(m.subtype).toLowerCase() === "trailer" && str(m.ratingKey))
      .map((m: any) => ({ ratingKey: str(m.ratingKey), title: str(m.title) || "Trailer", durationMs: Number(m.duration) || 0 }));
  } catch {
    return [];
  }
}

export async function related(base: string, token: string, ratingKey: string): Promise<PlexItem[]> {
  try {
    const c = await container(`${base}/library/metadata/${ratingKey}/related?count=24`, token);
    const seen = new Set<string>();
    return metadataOf(arr(c.Hub))
      .filter((i: PlexItem) => i.ratingKey !== ratingKey && (i.type === "movie" || i.type === "show") && !seen.has(i.ratingKey) && !!seen.add(i.ratingKey))
      .slice(0, 24)
      .map((i: PlexItem) => ({ ...i, serverBase: base }));
  } catch {
    return [];
  }
}

// ---------------------------------------------------------------- Watched, Watchlist

export async function setWatched(base: string, token: string, ratingKey: string, watched: boolean): Promise<void> {
  await ask(`${base}/:/${watched ? "scrobble" : "unscrobble"}?key=${ratingKey}&identifier=com.plexapp.plugins.library&X-Plex-Token=${token}`, {
    headers: { accept: "application/json" },
    failure: "Plex couldn't update the watched status.",
  });
}

export async function removeFromContinueWatching(base: string, token: string, ratingKey: string): Promise<void> {
  await ask(`${base}/actions/removeFromContinueWatching?ratingKey=${ratingKey}`, {
    method: "PUT",
    headers: headers(token),
    failure: "Couldn't remove that from Continue Watching.",
  });
}

export async function watchlist(token: string): Promise<string[]> {
  const c = await container(`${DISCOVER}/library/sections/watchlist/all?includeFields=guid,type,title&X-Plex-Container-Start=0&X-Plex-Container-Size=100`, token);
  return arr(c.Metadata).map((m: any) => str(m.guid)).filter((g: string) => g.startsWith("plex://"));
}

export async function byGuid(base: string, token: string, guid: string): Promise<PlexItem | null> {
  return (await items(base, token, `/library/all?guid=${enc(guid)}`, 1))[0] ?? null;
}

export async function setWatchlisted(token: string, guid: string, on: boolean): Promise<void> {
  const key = guid.slice(guid.lastIndexOf("/") + 1);
  await ask(`${DISCOVER}/actions/${on ? "addToWatchlist" : "removeFromWatchlist"}?ratingKey=${key}`, {
    method: "PUT",
    headers: headers(token),
    failure: "Couldn't change your Watchlist.",
  });
}

// ---------------------------------------------------------------- Pictures

/** A picture at the size it's drawn, through the server's resizer. */
export function imageUrl(base: string, token: string, path: string | null | undefined, width: number, height: number): string | null {
  if (!path) return null;
  return `${base}/photo/:/transcode?width=${width}&height=${height}&minSize=1&upscale=1&url=${enc(path)}&X-Plex-Token=${token}`;
}

export function logoUrl(base: string, token: string, path: string): string {
  return `${base}${path}${path.includes("?") ? "&" : "?"}X-Plex-Token=${token}`;
}

// ---------------------------------------------------------------- Reading answers

export function parseItem(entry: any): PlexItem {
  const type = str(entry.type);
  return {
    ratingKey: str(entry.ratingKey),
    title: str(entry.title),
    titleSort: str(entry.titleSort) || null,
    type,
    thumb: str(entry.thumb) || null,
    art: str(entry.art) || null,
    summary: str(entry.summary) || null,
    year: pos(entry.year),
    index: pos(entry.index),
    parentIndex: pos(entry.parentIndex),
    parentRatingKey: str(entry.parentRatingKey) || null,
    parentTitle: str(entry.parentTitle) || null,
    grandparentRatingKey: str(entry.grandparentRatingKey) || null,
    grandparentTitle: str(entry.grandparentTitle) || null,
    grandparentThumb: str(entry.grandparentThumb) || null,
    durationMs: Number(entry.duration) || 0,
    viewOffsetMs: Number(entry.viewOffset) || 0,
    leafCount: Number(entry.leafCount) > 0 ? Number(entry.leafCount) : type === "collection" ? Number(entry.childCount) || 0 : 0,
    viewedLeafCount: Number(entry.viewedLeafCount) || 0,
    viewCount: Number(entry.viewCount) || 0,
    addedAt: Number(entry.addedAt) || 0,
    lastViewedAt: Number(entry.lastViewedAt) || 0,
    logo: logoOf(entry),
    qualities: qualitiesOf(entry),
    airDate: str(entry.originallyAvailableAt) || null,
    librarySectionId: str(entry.librarySectionID) || null,
    serverBase: null,
  };
}

function qualitiesOf(entry: any): string[] {
  const media = arr(entry.Media)[0];
  if (!media) return [];
  const video = arr(arr(media.Part)[0]?.Stream).find((s: any) => Number(s.streamType) === 1);
  return qualityBadges(str(media.videoResolution) || null, Number(media.audioChannels) || 0, video?.DOVIPresent === true, str(video?.colorTrc) || null);
}

function logoOf(entry: any): string | null {
  const logo = arr(entry.Image).find((i: any) => str(i?.type) === "clearLogo");
  return str(logo?.url) || null;
}

function tags(entry: any, field: string): string[] {
  return arr(entry[field]).map((t: any) => str(t?.tag)).filter(Boolean);
}

/** Every hub's items, in order. (flatMap is newer than the TVs' browser.) */
function metadataOf(hubs: any[]): PlexItem[] {
  const out: PlexItem[] = [];
  for (const hub of hubs) for (const entry of arr(hub?.Metadata)) out.push(parseItem(entry));
  return out;
}

function arr(value: any): any[] {
  return Array.isArray(value) ? value : [];
}

function str(value: any): string {
  return value == null ? "" : typeof value === "string" ? value : String(value);
}

function pos(value: any): number | null {
  const n = Number(value);
  return n > 0 ? n : null;
}

function num(value: any, fallback: number): number {
  const n = Number(value);
  return value == null || isNaN(n) ? fallback : n;
}
