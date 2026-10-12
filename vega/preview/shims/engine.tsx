// The video engine, for the browser preview: a black picture that plays as far as the
// screens can tell, its position moving on while it isn't paused.
import React from 'react';
import { View } from 'react-native-web';

type Handler = () => void;

export class Engine {
  paused = true;
  positionMs = 0;
  durationMs = 0;
  bufferedMs = 0;
  audioTracks: Array<{ id: number; name: string; lang?: string }> = [];
  audioTrack = 0;
  subtitleTracks: Array<{ id: number; name: string; lang?: string }> = [];
  subtitleTrack = -1;
  videoSize: { width: number; height: number } | null = null;
  muted = false;
  url: string | null = null;
  private listeners = new Map<string, Set<Handler>>();
  private timer: ReturnType<typeof setInterval> | null = null;
  on(event: string, h: Handler) {
    let set = this.listeners.get(event);
    if (!set) this.listeners.set(event, (set = new Set()));
    set.add(h);
    return () => { set!.delete(h); };
  }
  onFailure(_h: (f: unknown) => void) { return () => undefined; }
  private emit(event: string) { this.listeners.get(event)?.forEach((h) => h()); }
  async open(url: string, startMs = 0) {
    this.url = url;
    this.videoSize = { width: 1920, height: 1080 };
    this.positionMs = startMs;
    this.emit('opened');
    this.durationMs = 6_000_000;
    if (this.timer) clearInterval(this.timer);
    this.timer = setInterval(() => {
      if (this.paused) return;
      this.positionMs += 500;
      this.bufferedMs = this.positionMs + 60_000;
    }, 500);
    setTimeout(() => this.play(), 300);
  }
  async reload() {}
  play() { this.paused = false; this.emit('playing'); }
  pause() { this.paused = true; this.emit('pause'); }
  seek(ms: number) { this.positionMs = ms; }
  async release() { if (this.timer) clearInterval(this.timer); }
}

export function useEngine() {
  const engine = React.useRef(new Engine()).current;
  React.useEffect(() => () => void engine.release(), []);
  return engine;
}

// A stand-in picture: each address its own colour, so tiles side by side can be told apart.
export function VideoSurface(props: { engine?: Engine; style?: object }) {
  const [url, setUrl] = React.useState(props.engine?.url ?? null);
  React.useEffect(() => props.engine?.on('opened', () => setUrl(props.engine!.url)), [props.engine]);
  let hash = 0;
  for (const ch of url ?? '') hash = (hash * 31 + ch.charCodeAt(0)) | 0;
  const hue = Math.abs(hash) % 360;
  const background = url ? `linear-gradient(135deg, hsl(${hue}, 45%, 22%), hsl(${(hue + 40) % 360}, 50%, 12%))` : '#000';
  return <View style={[{ backgroundColor: '#000', backgroundImage: background } as object, props.style]} />;
}

export function setBufferSeconds(_seconds: number) {}
