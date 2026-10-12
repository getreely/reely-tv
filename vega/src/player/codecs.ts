import type { PlexVideoInfo } from '../api/plex';
import { playsAudio, playsVideo } from './formats';

/*
 * What this TV plays, for Settings: the same questions each title is asked when it plays
 * (formats.ts), put about the formats a library has most, so what Settings lists is what
 * playback does. A title's own answer is about it exactly, at its size and HDR.
 */

const video = (codec: string, profile: string, height: number, extra: Partial<PlexVideoInfo> = {}): PlexVideoInfo => ({
  codec, profile, level: null, width: Math.round((height * 16) / 9), height, bitDepth: 8, frameRate: 24, bitrateKbps: 0, transfer: null, bt2020: false, dovi: null, ...extra,
});

interface Format { label: string; kind: 'video' | 'audio'; plays: () => Promise<boolean> }

export const FORMATS: Format[] = [
  { label: 'H.264', kind: 'video', plays: () => playsVideo(video('h264', 'high', 1080)) },
  { label: 'HEVC', kind: 'video', plays: () => playsVideo(video('hevc', 'main', 1080)) },
  { label: '4K HDR', kind: 'video', plays: () => playsVideo(video('hevc', 'main 10', 2160, { bitDepth: 10, transfer: 'pq', bt2020: true })) },
  { label: 'Dolby Vision', kind: 'video', plays: () => playsVideo(video('hevc', 'main 10', 2160, { bitDepth: 10, transfer: 'pq', bt2020: true, dovi: { profile: 5, level: 6, compat: 0 } })) },
  { label: 'VP9', kind: 'video', plays: () => playsVideo(video('vp9', 'profile 0', 1080)) },
  { label: 'AV1', kind: 'video', plays: () => playsVideo(video('av1', 'main', 1080)) },
  { label: 'AAC', kind: 'audio', plays: () => playsAudio('aac', 2) },
  { label: 'MP3', kind: 'audio', plays: () => playsAudio('mp3', 2) },
  { label: 'Dolby Digital 5.1', kind: 'audio', plays: () => playsAudio('ac3', 6) },
  { label: 'Dolby Digital Plus 5.1', kind: 'audio', plays: () => playsAudio('eac3', 6) },
  { label: 'FLAC', kind: 'audio', plays: () => playsAudio('flac', 2) },
  { label: 'Opus', kind: 'audio', plays: () => playsAudio('opus', 2) },
  { label: 'DTS', kind: 'audio', plays: () => playsAudio('dca', 6) },
  { label: 'Dolby TrueHD', kind: 'audio', plays: () => playsAudio('truehd', 8) },
];

let answers: Record<string, boolean> | null = null;
let asking: Promise<Record<string, boolean>> | null = null;
const listeners = new Set<() => void>();

/** Each format asked about, one after another. */
export function checkCodecs(): Promise<Record<string, boolean>> {
  if (asking) return asking;
  asking = (async () => {
    const found: Record<string, boolean> = {};
    for (const f of FORMATS) found[f.label] = await f.plays();
    answers = found;
    console.warn(`[reely] this TV plays: ${FORMATS.map((f) => `${f.label}=${found[f.label] ? 'yes' : 'no'}`).join(', ')}`);
    listeners.forEach((l) => l());
    return found;
  })();
  return asking;
}

/** The TV's answers, or null before they're in. */
export function codecAnswers(): Record<string, boolean> | null {
  return answers;
}

export function onCodecs(listener: () => void): () => void {
  listeners.add(listener);
  return () => { listeners.delete(listener); };
}
