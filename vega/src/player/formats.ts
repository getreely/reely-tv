import { decodingInfo } from '@amazon-devices/react-native-w3cmedia';
import type { ConvertPlan, PlexPlayback, PlexStream, PlexVideoInfo } from '../api/plex';

/*
 * Whether this TV plays a title as it is, asked of the TV itself for that title: its
 * picture as the file has it (codec, profile and level, size, frame rate, bit depth, HDR
 * or Dolby Vision) and its sound (format and channels). Amazon's MediaCapabilities puts
 * each question to the stick's own decoders, as Android's are asked on the Fire TV.
 *
 * What the TV doesn't play, Plex converts, and only that: a picture it plays is copied as
 * it is while the sound is made again, and sound made again keeps its surround in a Dolby
 * format the TV plays (the Fire TV's AudioPlan.kt), else stereo AAC.
 */

export interface Verdict {
  /** The file plays as it is. */
  direct: boolean;
  /** When it doesn't, what Plex's conversion keeps and makes again. */
  plan: ConvertPlan;
  /** Why it's converted, for Playback info; null when it isn't. */
  reason: string | null;
}

/**
 * Files the stick plays as they are: MP4 and QuickTime. The stick's player reads a file
 * from the network as a stream, and in that mode its Matroska reader can't go elsewhere
 * in one ("Seek failed. Internal error 0" on the stick, at once, for every skip and every
 * scrub), so MKV, WebM and MPEG-TS come through Plex as a stream instead, with whatever
 * this TV plays copied as it is, and going elsewhere asks Plex for it from there: as
 * Plex's own apps on televisions whose players can't seek in them.
 */
const CONTAINERS = ['mp4', 'm4v', 'mov'];

/** How long the TV is given to answer one question: none is taken as a no, and Plex converts. */
const ASK_MS = 3_000;

/** Dolby formats a conversion keeps surround in, best first, as the Fire TV's AudioPlan.kt. */
const SURROUND = ['eac3', 'ac3'];

const NAMES: Record<string, string> = {
  h264: 'H.264', hevc: 'HEVC', h265: 'HEVC', vp9: 'VP9', av1: 'AV1', mpeg2video: 'MPEG-2', vc1: 'VC-1', mpeg4: 'MPEG-4',
  aac: 'AAC', mp3: 'MP3', ac3: 'Dolby Digital', eac3: 'Dolby Digital Plus', truehd: 'Dolby TrueHD', dca: 'DTS', dts: 'DTS',
  flac: 'FLAC', opus: 'Opus', vorbis: 'Vorbis', pcm: 'PCM', alac: 'ALAC', mp2: 'MP2',
};
const named = (codec: string) => NAMES[codec] ?? codec.toUpperCase();

const said = new Map<string, Promise<boolean>>();

/** One question to the TV, each asked once; no answer in time is a no. */
function ask(config: object): Promise<boolean> {
  const key = JSON.stringify(config);
  let answer = said.get(key);
  if (!answer) {
    const asked = Promise.resolve()
      .then(() => decodingInfo(config as never))
      .then((info) => !!(info as { supported?: boolean })?.supported, () => false);
    answer = Promise.race([asked, new Promise<boolean>((done) => setTimeout(() => done(false), ASK_MS))]);
    said.set(key, answer);
  }
  return answer;
}

const hex2 = (n: number) => Math.max(0, Math.min(255, Math.round(n))).toString(16).toUpperCase().padStart(2, '0');
const dec2 = (n: number) => String(Math.max(0, Math.round(n))).padStart(2, '0');

/** H.264's level as its idc: Plex says 41 (or 4.1) for 4.1. */
function avcLevel(level: number | null, height: number): number {
  if (level == null) return height > 1088 ? 51 : 41;
  return level < 10 ? Math.round(level * 10) : level;
}

/** HEVC's level as its idc, thirty times the level: Plex says 150 (or 5 / 5.0) for 5.0. */
function hevcLevel(level: number | null, height: number): number {
  if (level == null) return height > 1088 ? 153 : 123;
  return level < 30 ? Math.round(level * 30) : level;
}

/** The picture's codec as a mime type writes it (RFC 6381), from what Plex read in the file. */
function videoCodecs(v: PlexVideoInfo): string | null {
  const p = v.profile ?? '';
  switch (v.codec) {
    case 'h264': {
      const profile = p.includes('high 4:4:4') ? 0xf4 : p.includes('high 4:2:2') ? 0x7a : p.includes('high 10') ? 0x6e
        : p.includes('high') ? 0x64 : p.includes('main') ? 0x4d : p.includes('extended') ? 0x58 : p.includes('baseline') ? 0x42 : 0x64;
      const constraint = p.includes('constrained baseline') ? 0x40 : 0;
      return `avc1.${hex2(profile)}${hex2(constraint)}${hex2(avcLevel(v.level, v.height))}`;
    }
    case 'hevc':
    case 'h265': {
      const main10 = p.includes('10') || v.bitDepth > 8;
      return `hvc1.${main10 ? '2.4' : '1.6'}.L${hevcLevel(v.level, v.height)}.B0`;
    }
    case 'vp9': {
      const profile = /profile\s*(\d)/.exec(p)?.[1] ?? (v.bitDepth > 8 ? '2' : '0');
      const level = v.height > 1088 ? ((v.frameRate ?? 30) > 30 ? 51 : 50) : (v.frameRate ?? 30) > 30 ? 41 : 40;
      return `vp09.${dec2(Number(profile))}.${dec2(level)}.${dec2(v.bitDepth)}`;
    }
    case 'av1': {
      const level = v.height > 1088 ? ((v.frameRate ?? 30) > 30 ? 13 : 12) : (v.frameRate ?? 30) > 30 ? 9 : 8;
      return `av01.0.${dec2(level)}M.${dec2(v.bitDepth)}`;
    }
    default:
      return null;
  }
}

function videoConfig(v: PlexVideoInfo, codecs: string, transfer: 'pq' | 'hlg' | null) {
  return {
    type: 'file',
    video: {
      contentType: `video/mp4; codecs="${codecs}"`,
      width: v.width,
      height: v.height,
      bitrate: (v.bitrateKbps > 0 ? v.bitrateKbps : v.height > 1088 ? 25_000 : 8_000) * 1000,
      framerate: v.frameRate && v.frameRate > 0 ? v.frameRate : 24,
      ...(transfer ? { transferFunction: transfer } : {}),
      ...(transfer || v.bt2020 ? { colorGamut: 'rec2020' } : {}),
    },
  };
}

/** What the picture is, said as a person would: "4K HEVC 10-bit HDR", "Dolby Vision profile 5". */
function describeVideo(v: PlexVideoInfo): string {
  if (v.dovi && v.dovi.compat === 0) return `Dolby Vision profile ${v.dovi.profile}`;
  const size = v.height > 1088 ? '4K ' : '';
  const depth = v.bitDepth > 8 ? ` ${v.bitDepth}-bit` : '';
  const hdr = v.dovi ? ' Dolby Vision' : v.transfer === 'pq' ? ' HDR' : v.transfer === 'hlg' ? ' HLG' : '';
  return `${size}${named(v.codec)}${depth}${hdr}`;
}

/**
 * Whether the TV plays the picture. Dolby Vision is asked about as itself first; one with
 * an HDR10, HLG or SDR picture under it (profiles 7 and 8) plays as that where the TV has
 * no Dolby Vision, as any player does; profile 5 has nothing under it.
 */
export async function playsVideo(v: PlexVideoInfo): Promise<boolean> {
  if (v.dovi) {
    const level = dec2(v.dovi.level || 6);
    const dolby = `${v.dovi.profile === 7 ? 'dvhe' : 'dvh1'}.${dec2(v.dovi.profile)}.${level}`;
    if (await ask(videoConfig(v, dolby, 'pq'))) return true;
    if (v.dovi.compat === 0) return false;
  }
  const codecs = videoCodecs(v);
  if (!codecs) return false;
  const transfer = v.dovi ? (v.dovi.compat === 4 ? 'hlg' : v.dovi.compat === 2 ? null : 'pq') : v.transfer;
  return ask(videoConfig(v, codecs, transfer));
}

/** The sound's format as a mime type writes it; null for one Vega has no decoder for (DTS, TrueHD, PCM…). */
function audioCodecs(codec: string, profile: string | null): string | null {
  switch (codec) {
    case 'aac': return profile?.includes('he') ? 'mp4a.40.5' : 'mp4a.40.2';
    case 'mp3': return 'mp4a.69';
    case 'ac3': return 'ac-3';
    case 'eac3': return 'ec-3';
    case 'flac': return 'flac';
    case 'opus': return 'opus';
    case 'vorbis': return 'vorbis';
    default: return null;
  }
}

/** Whether the TV plays [codec] sound with [channels] channels. */
export function playsAudio(codec: string, channels: number, profile: string | null = null, sampleRate = 48_000): Promise<boolean> {
  const codecs = audioCodecs(codec, profile);
  if (!codecs) return Promise.resolve(false);
  return ask({
    type: 'file',
    audio: { contentType: `audio/${codec === 'opus' || codec === 'vorbis' ? 'webm' : 'mp4'}; codecs="${codecs}"`, channels: String(Math.max(1, channels || 2)), bitrate: 640_000, samplerate: sampleRate || 48_000 },
  });
}

/** The sound track that plays: the one chosen, else the file's first. */
function soundOf(p: PlexPlayback): { codec: string; channels: number; profile: string | null; sampleRate: number } | null {
  const stream: PlexStream | undefined = p.audioStreams.find((s) => s.selected) ?? p.audioStreams[0];
  const codec = stream?.codec ?? p.audioCodec;
  if (!codec) return null;
  return { codec, channels: stream?.channels || p.audioChannels || 2, profile: stream?.profile ?? null, sampleRate: stream?.samplingRate || 48_000 };
}

/** Surround formats the TV plays at [channels] (at most 5.1, as Dolby carries), best first. */
async function surroundFor(channels: number): Promise<string[]> {
  if (channels <= 2) return [];
  const out: string[] = [];
  for (const codec of SURROUND) if (await playsAudio(codec, Math.min(channels, 6))) out.push(codec);
  return out;
}

/** The TV asked about [p] as it is: whether it plays it, and if not, what Plex makes of it. */
export async function checkPlayback(p: PlexPlayback): Promise<Verdict> {
  const v = p.video;
  const sound = soundOf(p);
  const [videoOk, audioOk] = await Promise.all([
    v ? playsVideo(v) : Promise.resolve(true),
    sound ? playsAudio(sound.codec, sound.channels, sound.profile, sound.sampleRate) : Promise.resolve(true),
  ]);
  const containerOk = !!p.container && CONTAINERS.includes(p.container);
  const surround = !audioOk && sound ? await surroundFor(sound.channels) : [];
  const plan: ConvertPlan = {
    video: videoOk ? 'copy' : 'convert',
    videoCodec: videoOk && v ? v.codec : null,
    audio: audioOk ? 'copy' : surround.length ? 'surround' : 'stereo',
    audioCodec: audioOk && sound ? sound.codec : null,
    surround,
  };
  const reason = !videoOk && v ? `This TV can't play ${describeVideo(v)} video`
    : !audioOk && sound ? `This TV can't play ${named(sound.codec)} sound`
      : !containerOk ? `Plex streams ${p.container ? p.container.toUpperCase() : 'these'} files so you can skip through them`
        : null;
  return { direct: videoOk && audioOk && containerOk, plan, reason };
}

/** For a file that said it would play and then didn't: the sound made again, the picture copied where it can be. */
export async function fallbackPlan(p: PlexPlayback): Promise<ConvertPlan> {
  const sound = soundOf(p);
  const surround = sound ? await surroundFor(sound.channels) : [];
  return { video: 'copy', videoCodec: null, audio: surround.length ? 'surround' : 'stereo', audioCodec: null, surround };
}

export const describeCodec = named;
