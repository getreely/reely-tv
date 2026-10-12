import { decodingInfo } from '@amazon-devices/react-native-w3cmedia';

/*
 * What this stick decodes, asked of the stick itself: Amazon's MediaCapabilities
 * (decodingInfo) puts each format to the TV's decoders and says which it has. Asked once,
 * at start; until the answers are in, the formats every Vega stick plays stand in.
 */

export interface CodecProbe {
  /** The codec as a mime type writes it (codecs="…"); how canplay.ts finds it. */
  prefixes: string[];
  label: string;
  kind: 'video' | 'audio';
  contentType: string;
  width?: number;
  height?: number;
}

/** Every format Plex might send, as Amazon's player names it. */
export const PROBES: Record<string, CodecProbe> = {
  h264: { prefixes: ['avc1', 'avc3'], label: 'H.264', kind: 'video', contentType: 'video/mp4; codecs="avc1.640028"', width: 1920, height: 1080 },
  hevc: { prefixes: ['hev1', 'hvc1'], label: 'HEVC', kind: 'video', contentType: 'video/mp4; codecs="hvc1.1.6.L120.90"', width: 1920, height: 1080 },
  hevc4k: { prefixes: [], label: 'HEVC 4K', kind: 'video', contentType: 'video/mp4; codecs="hvc1.2.4.L153.90"', width: 3840, height: 2160 },
  vp9: { prefixes: ['vp09', 'vp9'], label: 'VP9', kind: 'video', contentType: 'video/webm; codecs="vp09.00.40.08"', width: 1920, height: 1080 },
  av1: { prefixes: ['av01', 'av1'], label: 'AV1', kind: 'video', contentType: 'video/mp4; codecs="av01.0.08M.08"', width: 1920, height: 1080 },
  aac: { prefixes: ['mp4a.40', 'mp4a.66', 'mp4a.67', 'mp4a.68'], label: 'AAC', kind: 'audio', contentType: 'audio/mp4; codecs="mp4a.40.2"' },
  mp3: { prefixes: ['mp4a.69', 'mp4a.6B', 'mp3'], label: 'MP3', kind: 'audio', contentType: 'audio/mp4; codecs="mp4a.69"' },
  ac3: { prefixes: ['ac-3'], label: 'Dolby Digital', kind: 'audio', contentType: 'audio/mp4; codecs="ac-3"' },
  eac3: { prefixes: ['ec-3'], label: 'Dolby Digital Plus', kind: 'audio', contentType: 'audio/mp4; codecs="ec-3"' },
  opus: { prefixes: ['opus'], label: 'Opus', kind: 'audio', contentType: 'audio/webm; codecs="opus"' },
  flac: { prefixes: ['flac'], label: 'FLAC', kind: 'audio', contentType: 'audio/mp4; codecs="flac"' },
  dts: { prefixes: ['dtsc', 'dts'], label: 'DTS', kind: 'audio', contentType: 'audio/mp4; codecs="dtsc"' },
  truehd: { prefixes: ['mlpa'], label: 'Dolby TrueHD', kind: 'audio', contentType: 'audio/mp4; codecs="mlpa"' },
};

/** What every Vega stick plays, for before the stick has been asked (canplay.ts's list until now). */
const KNOWN: Record<string, boolean> = { h264: true, hevc: true, aac: true, ac3: true, eac3: true };

/** How long one question is given: an answer that doesn't come is a no. */
const ASK_MS = 3_000;

let answers: Record<string, boolean> | null = null;
let asking: Promise<Record<string, boolean>> | null = null;
const listeners = new Set<() => void>();

async function ask(probe: CodecProbe): Promise<boolean> {
  const config = probe.kind === 'video'
    ? { type: 'file', video: { contentType: probe.contentType, width: probe.width ?? 1920, height: probe.height ?? 1080, bitrate: 8_000_000, framerate: 30 } }
    : { type: 'file', audio: { contentType: probe.contentType, channels: '2', bitrate: 256_000, samplerate: 48_000 } };
  const answer = Promise.resolve()
    .then(() => decodingInfo(config as never))
    .then((info) => !!(info as { supported?: boolean })?.supported, () => false);
  return Promise.race([answer, new Promise<boolean>((done) => setTimeout(() => done(false), ASK_MS))]);
}

/** The stick asked about every format, one after another; again when [fresh]. */
export function checkCodecs(fresh = false): Promise<Record<string, boolean>> {
  if (asking && !fresh) return asking;
  asking = (async () => {
    const found: Record<string, boolean> = {};
    for (const [key, probe] of Object.entries(PROBES)) found[key] = await ask(probe);
    answers = found;
    console.warn(`[reely] this TV decodes: ${Object.entries(found).map(([k, ok]) => `${k}=${ok ? 'yes' : 'no'}`).join(' ')}`);
    listeners.forEach((l) => l());
    return found;
  })();
  return asking;
}

/** The stick's answers, or null before they're in. */
export function codecAnswers(): Record<string, boolean> | null {
  return answers;
}

export function onCodecs(listener: () => void): () => void {
  listeners.add(listener);
  return () => { listeners.delete(listener); };
}

/** Whether the stick decodes [codec] (a mime codecs entry): the known ones, and whatever else it says it does. */
export function decodes(codec: string): boolean {
  const c = codec.trim();
  const key = Object.keys(PROBES).find((k) => PROBES[k].prefixes.some((p) => c === p || c.startsWith(`${p}.`) || (p.includes('.') && c.startsWith(p))));
  if (!key) return false;
  // Those every Vega stick has played stay playable whatever a question about them says.
  return !!KNOWN[key] || !!answers?.[key];
}
