// The video engine, for the browser preview: a black picture. Screens are what's shown.
import React from 'react';
import { View } from 'react-native-web';

type Handler = () => void;

export class Engine {
  paused = true;
  positionMs = 0;
  durationMs = 0;
  audioTracks: Array<{ id: number; name: string; lang?: string }> = [];
  audioTrack = 0;
  muted = false;
  on(_event: string, _h: Handler) { return () => undefined; }
  onFailure(_h: (f: unknown) => void) { return () => undefined; }
  async open(_url: string, _startMs = 0) { this.paused = false; }
  async reload() {}
  play() { this.paused = false; }
  pause() { this.paused = true; }
  seek(ms: number) { this.positionMs = ms; }
  async release() {}
}

export function useEngine() {
  return React.useRef(new Engine()).current;
}

export function VideoSurface(props: { style?: object }) {
  return <View style={[{ backgroundColor: '#000' }, props.style]} />;
}
