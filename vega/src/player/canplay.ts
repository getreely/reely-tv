import type { PlexPlayback } from '../api/plex';
import { plan, type CanPlay } from '../app/playback';
import { decodes } from './codecs';

/*
 * What the stick's own player opens as it is (Vega's media pipeline), in MP4, MKV, WebM or
 * MPEG-TS: the video and sound in it as the stick itself says it decodes them (codecs.ts),
 * H.264, HEVC, AAC and Dolby Digital until it's been asked. Anything else Plex converts
 * first, as the Fire TV does when its decoders say no.
 */
const CONTAINERS = ['video/mp4', 'video/x-matroska', 'video/webm', 'video/mp2t'];

export const vegaCanPlay: CanPlay = (mime) => {
  const [container, rest] = mime.split(';');
  if (!CONTAINERS.includes(container.trim())) return false;
  const codecs = /codecs="([^"]*)"/.exec(rest ?? '')?.[1]?.split(',').map((c) => c.trim()).filter(Boolean) ?? [];
  return codecs.every(decodes);
};

/** Whether Plex's file plays as it is here: the question app.play asks. */
export const canDirect = (p: PlexPlayback) => plan(p, vegaCanPlay).direct;

/** Why it doesn't, for Playback info; null when it does. */
export const whyConverted = (p: PlexPlayback) => plan(p, vegaCanPlay).reason;
