import type { PlexPlayback } from "../api/plex";

/*
 * Whether the TV plays a file as it is, or Plex converts it. The TV's own player plays
 * a great deal (H.264, HEVC, AAC, Dolby Digital, in MP4, MKV or TS); whatever it says it
 * can't, Plex converts to HLS — as the Fire TV does when its own decoders say no.
 */

export type CanPlay = (mime: string) => boolean;

const CONTAINERS: Record<string, string> = {
  mp4: "video/mp4",
  m4v: "video/mp4",
  mov: "video/mp4",
  mkv: "video/x-matroska",
  webm: "video/webm",
  ts: "video/mp2t",
  mpegts: "video/mp2t",
};

const VIDEO: Record<string, string> = { h264: "avc1.640028", hevc: "hev1.1.6.L120.90", h265: "hev1.1.6.L120.90", vp9: "vp09.00.10.08", av1: "av01.0.08M.08" };
const AUDIO: Record<string, string> = { aac: "mp4a.40.2", mp3: "mp4a.69", ac3: "ac-3", eac3: "ec-3", opus: "opus", flac: "flac" };

export interface Plan {
  direct: boolean;
  /** Why it's being converted, for Playback info. */
  reason: string | null;
}

export function plan(p: Pick<PlexPlayback, "container" | "videoCodec" | "audioCodec">, canPlay: CanPlay): Plan {
  const container = p.container ? CONTAINERS[p.container] : undefined;
  if (!container) return { direct: false, reason: `The TV can't open ${p.container ? p.container.toUpperCase() : "this"} files` };
  const video = p.videoCodec ? VIDEO[p.videoCodec] : undefined;
  if (p.videoCodec && !video) return { direct: false, reason: `The TV can't play ${p.videoCodec.toUpperCase()} video` };
  const audio = p.audioCodec ? AUDIO[p.audioCodec] : undefined;
  if (p.audioCodec && !audio) return { direct: false, reason: `The TV can't play ${p.audioCodec.toUpperCase()} sound` };
  const codecs = [video, audio].filter(Boolean).join(", ");
  const mime = codecs ? `${container}; codecs="${codecs}"` : container;
  return canPlay(mime) ? { direct: true, reason: null } : { direct: false, reason: "The TV can't play this file as it is" };
}

/** What the browser's own player says it can do: "probably" and "maybe" both count. */
export function browserCanPlay(video: HTMLVideoElement): CanPlay {
  return (mime) => video.canPlayType(mime) !== "";
}

/** Near enough the end is watched, as Plex counts it. */
export const WATCHED_AT = 0.9;
/** How often where playback is gets told to the server. */
export const REPORT_EVERY_MS = 10_000;
export const SKIP_MS = 10_000;
