// Amazon's media players, for the browser preview: nothing plays.
export class AudioPlayer {
  volume = 1;
  src = '';
  async initialize() {}
  async play() {}
  pause() {}
  async deinitialize() {}
}

/** The stick's decoders, as a typical Vega stick answers: the preview's stand-in. */
const DECODES = ['avc1', 'hvc1.1', 'hvc1.2', 'vp09', 'mp4a.40', 'mp4a.69', 'ac-3', 'ec-3', 'opus', 'flac'];
export async function decodingInfo(config: { video?: { contentType: string }; audio?: { contentType: string } }) {
  const type = config.video?.contentType ?? config.audio?.contentType ?? '';
  const codec = /codecs="([^"]*)"/.exec(type)?.[1] ?? '';
  return { supported: DECODES.some((d) => codec.startsWith(d)), smooth: true, powerEfficient: true };
}
