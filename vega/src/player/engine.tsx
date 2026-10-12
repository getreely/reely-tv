import React, { useEffect, useRef } from 'react';
import { View, type StyleProp, type ViewStyle } from 'react-native';
import { KeplerCaptionsView, KeplerVideoSurfaceView, VideoPlayer } from '@amazon-devices/react-native-w3cmedia';
import * as HlsLib from '../hls/player/hlsjsplayer/dist/hls.mjs';
import Document from '../hls/polyfills/DocumentPolyfill';
import Element from '../hls/polyfills/ElementPolyfill';
import MiscPolyfill from '../hls/polyfills/MiscPolyfill';
import TextDecoderPolyfill from '../hls/polyfills/TextDecoderPolyfill';
import W3CMediaPolyfill from '../hls/polyfills/W3CMediaPolyfill';

/*
 * Video on Vega, in Amazon's own player (the W3C Media API: VideoPlayer, drawn on a
 * KeplerVideoSurfaceView). HLS (a provider's channels, Plex's conversions) goes through
 * Amazon's build of hls.js, which feeds the player; a file Plex sends as it is plays
 * straight from its address. Either way the requests are the app's own, so a provider
 * that turns away a web page has nothing to turn away.
 */

let polyfilled = false;

/** hls.js expects a browser around it: Amazon's stand-ins, once. */
function polyfill() {
  if (polyfilled) return;
  polyfilled = true;
  Document.install();
  Element.install();
  TextDecoderPolyfill.install();
  W3CMediaPolyfill.install();
  MiscPolyfill.install();
}

/** How far ahead hls.js keeps, in seconds: Settings' Buffer, Normal or Larger. */
let bufferSeconds = 50;
export function setBufferSeconds(seconds: number) {
  bufferSeconds = seconds;
}

const isHls = (url: string) => /\.m3u8(\?|#|$)/i.test(url) || /\/video\/:\/transcode\/universal\/start\.m3u8/.test(url);

export type EngineEvent = 'playing' | 'pause' | 'waiting' | 'timeupdate' | 'ended' | 'error' | 'loadedmetadata';

/** Why playback stopped for good: the network (try a fresh stream) or the media itself. */
export interface EngineFailure {
  kind: 'network' | 'media';
  /** The HTTP status that ended it, when there was one: 403 for a provider at its limit. */
  status: number | null;
  detail: string;
}

type HlsInstance = {
  loadSource(url: string): void;
  attachMedia(media: unknown): void;
  on(event: string, handler: (event: string, data: any) => void): void;
  recoverMediaError(): void;
  stopLoad(): void;
  detachMedia(): void;
  destroy(): void;
  audioTracks: Array<{ id: number; name: string; lang?: string }>;
  audioTrack: number;
  subtitleTracks?: Array<{ id: number; name: string; lang?: string }>;
  subtitleTrack?: number;
};

/**
 * One picture: its player, what it's playing, and the surface it's drawn on. Made by
 * [useEngine] for a screen, kept for as long as the screen is, and let go with it.
 */
export class Engine {
  readonly player = new VideoPlayer();
  private hls: HlsInstance | null = null;
  private ready: Promise<void>;
  private surface: string | null = null;
  private url: string | null = null;
  private listeners = new Map<EngineEvent, Set<() => void>>();
  private failureListeners = new Set<(f: EngineFailure) => void>();
  private recovered = false;
  private gone = false;

  constructor(options: { muted?: boolean } = {}) {
    this.ready = this.player.initialize().then(() => {
      this.player.autoplay = false;
      this.player.muted = !!options.muted;
      for (const event of ['playing', 'pause', 'waiting', 'timeupdate', 'ended', 'loadedmetadata'] as EngineEvent[]) {
        this.player.addEventListener(event, () => this.emit(event));
      }
      this.player.addEventListener('error', () => this.fail({ kind: 'media', status: null, detail: this.player.error?.message ?? 'The player stopped.' }));
    });
  }

  on(event: EngineEvent, handler: () => void): () => void {
    let set = this.listeners.get(event);
    if (!set) this.listeners.set(event, (set = new Set()));
    set.add(handler);
    return () => set!.delete(handler);
  }

  onFailure(handler: (f: EngineFailure) => void): () => void {
    this.failureListeners.add(handler);
    return () => this.failureListeners.delete(handler);
  }

  private emit(event: EngineEvent) {
    this.listeners.get(event)?.forEach((h) => h());
  }

  private fail(f: EngineFailure) {
    if (this.gone) return;
    this.failureListeners.forEach((h) => h(f));
  }

  /** Plays [url] from [startMs] in: HLS through hls.js, anything else as it is. */
  async open(url: string, startMs = 0) {
    await this.ready;
    if (this.gone) return;
    this.unloadSource();
    this.url = url;
    this.recovered = false;
    if (isHls(url)) {
      polyfill();
      (globalThis as unknown as { gmedia: unknown }).gmedia = this.player;
      const Hls = (HlsLib as unknown as { Hls: new (config: object) => HlsInstance; Events: Record<string, string>; ErrorTypes: Record<string, string> });
      const hls = new Hls.Hls({
        enableWorker: false,
        lowLatencyMode: false,
        backBufferLength: 30,
        maxBufferLength: bufferSeconds,
        maxMaxBufferLength: bufferSeconds * 2,
        startPosition: startMs > 0 ? startMs / 1000 : -1,
      });
      hls.on(Hls.Events.ERROR, (_e, data) => {
        if (!data?.fatal) return;
        // A decode hiccup gets one try at carrying on, as hls.js suggests; the network,
        // and anything after that, is the screen's to handle with a fresh stream.
        if (data.type === Hls.ErrorTypes.MEDIA_ERROR && !this.recovered) {
          this.recovered = true;
          hls.recoverMediaError();
          return;
        }
        this.fail({
          kind: data.type === Hls.ErrorTypes.NETWORK_ERROR ? 'network' : 'media',
          status: typeof data.response?.code === 'number' && data.response.code > 0 ? data.response.code : null,
          detail: String(data.details ?? data.type ?? 'error'),
        });
      });
      hls.loadSource(url);
      hls.attachMedia(this.player);
      this.hls = hls;
    } else {
      this.player.src = url;
      if (startMs > 0) this.player.currentTime = startMs / 1000;
    }
    this.player.play();
  }

  /** The same address again: a dropped connection's fresh stream. */
  async reload(startMs = 0) {
    if (this.url) await this.open(this.url, startMs);
  }

  play() { this.player.play(); }
  pause() { this.player.pause(); }
  get paused() { return this.player.paused; }
  get positionMs() { return Math.max(0, Math.floor((this.player.currentTime || 0) * 1000)); }
  get durationMs() {
    const d = this.player.duration;
    return Number.isFinite(d) && d > 0 ? Math.floor(d * 1000) : 0;
  }
  /** How far ahead of the playhead the picture is in hand. */
  get bufferedMs() {
    try {
      const b = this.player.buffered;
      const now = this.player.currentTime || 0;
      for (let i = 0; i < b.length; i++) if (b.start(i) <= now + 0.5 && b.end(i) >= now) return Math.floor(b.end(i) * 1000);
    } catch {
      // Not loaded.
    }
    return this.positionMs;
  }
  seek(ms: number) { this.player.currentTime = Math.max(0, ms / 1000); }
  set muted(on: boolean) { this.player.muted = on; }

  /** Sound tracks hls.js found in the stream, for a channel with more than one language. */
  get audioTracks() { return this.hls?.audioTracks ?? []; }
  set audioTrack(id: number) { if (this.hls) this.hls.audioTrack = id; }
  get audioTrack() { return this.hls?.audioTrack ?? -1; }
  /** Subtitles a channel's stream carries, where hls.js found any; -1 is off. */
  get subtitleTracks() { return this.hls?.subtitleTracks ?? []; }
  get subtitleTrack() { return this.hls?.subtitleTrack ?? -1; }
  set subtitleTrack(id: number) { if (this.hls) this.hls.subtitleTrack = id; }
  /** The picture's size once its first frame is in, for Playback info. */
  get videoSize(): { width: number; height: number } | null {
    const v = this.player as unknown as { videoWidth?: number; videoHeight?: number };
    return v.videoWidth && v.videoHeight ? { width: v.videoWidth, height: v.videoHeight } : null;
  }

  attachSurface(handle: string) {
    this.surface = handle;
    this.player.setSurfaceHandle(handle);
  }

  detachSurface(handle: string) {
    if (this.surface === handle) this.surface = null;
    this.player.clearSurfaceHandle(handle);
  }

  attachCaptions(handle: string) {
    (this.player as unknown as { setCaptionViewHandle?: (h: string) => void }).setCaptionViewHandle?.(handle);
  }

  private unloadSource() {
    if (this.hls) {
      try {
        this.hls.stopLoad();
        this.hls.detachMedia();
        this.hls.destroy();
      } catch {
        // Already gone.
      }
      this.hls = null;
    }
  }

  /** Stopped, and the player let go: the stick has only so many. */
  async release() {
    if (this.gone) return;
    this.gone = true;
    try {
      this.player.pause();
    } catch {
      // Not started.
    }
    this.unloadSource();
    await this.ready.catch(() => undefined);
    await this.player.deinitialize().catch(() => undefined);
  }
}

/** An engine for as long as the component using it is up. */
export function useEngine(options: { muted?: boolean } = {}): Engine {
  const engine = useRef<Engine | null>(null);
  if (!engine.current) engine.current = new Engine(options);
  useEffect(() => {
    const e = engine.current!;
    return () => void e.release();
  }, []);
  return engine.current;
}

/** Where [engine]'s picture is drawn, and its captions over it. */
export function VideoSurface(props: { engine: Engine; style?: StyleProp<ViewStyle>; captions?: boolean }) {
  const { engine } = props;
  return (
    <View style={[{ backgroundColor: '#000000', overflow: 'hidden' }, props.style]} pointerEvents="none">
      <KeplerVideoSurfaceView style={{ position: 'absolute', left: 0, right: 0, top: 0, bottom: 0 }}
        onSurfaceViewCreated={(handle: string) => engine.attachSurface(handle)}
        onSurfaceViewDestroyed={(handle: string) => engine.detachSurface(handle)} />
      {props.captions ? (
        <KeplerCaptionsView style={{ position: 'absolute', left: 0, right: 0, top: 0, bottom: 0 }} show
          onCaptionViewCreated={(handle: string) => engine.attachCaptions(handle)} />
      ) : null}
    </View>
  );
}
