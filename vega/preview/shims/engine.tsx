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
  muted = false;
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
  async open(_url: string, startMs = 0) {
    this.positionMs = startMs;
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

export function VideoSurface(props: { style?: object }) {
  return <View style={[{ backgroundColor: '#000' }, props.style]} />;
}
