import type { PlexPlayback } from '../api/plex';
import { plan, type CanPlay } from '../app/playback';

/*
 * What the stick's own player opens as it is (Vega's media pipeline): H.264 and HEVC
 * video, AAC, AC-3, E-AC-3 and MP3 sound, in MP4, MKV or MPEG-TS. Anything else Plex
 * converts first, as the Fire TV does when its decoders say no.
 */
const VIDEO = ['avc1', 'hev1', 'hvc1'];
const AUDIO = ['mp4a', 'ac-3', 'ec-3'];
const CONTAINERS = ['video/mp4', 'video/x-matroska', 'video/mp2t'];

export const vegaCanPlay: CanPlay = (mime) => {
  const [container, rest] = mime.split(';');
  if (!CONTAINERS.includes(container.trim())) return false;
  const codecs = /codecs="([^"]*)"/.exec(rest ?? '')?.[1]?.split(',').map((c) => c.trim()) ?? [];
  return codecs.every((c) => VIDEO.some((v) => c.startsWith(v)) || AUDIO.some((a) => c.startsWith(a)));
};

/** Whether Plex's file plays as it is here: the question app.play asks. */
export const canDirect = (p: PlexPlayback) => plan(p, vegaCanPlay).direct;
