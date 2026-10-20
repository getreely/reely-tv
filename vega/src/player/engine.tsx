import React, { useEffect, useRef, useState } from 'react';
import { View, type StyleProp, type ViewStyle } from 'react-native';
import { KeplerCaptionsView, KeplerVideoSurfaceView, VideoPlayer } from '@amazon-devices/react-native-w3cmedia';
import * as HlsLib from '../hls/player/hlsjsplayer/dist/hls.mjs';
import Document from '../hls/polyfills/DocumentPolyfill';
import Element from '../hls/polyfills/ElementPolyfill';
import MiscPolyfill from '../hls/polyfills/MiscPolyfill';
import TextDecoderPolyfill from '../hls/polyfills/TextDecoderPolyfill';
import W3CMediaPolyfill from '../hls/polyfills/W3CMediaPolyfill';
import { letGoOf, playersLetGo } from './release';

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

/**
 * How far ahead hls.js keeps, in seconds: Settings' Buffer, Normal or Larger. Kept short,
 * as Amazon's own hls.js player for Vega keeps it: every second of it is unpacked on the
 * JavaScript thread the remote's presses wait on, and filling a long one froze the controls.
 */
let bufferSeconds = 12;
export function setBufferSeconds(seconds: number) {
  bufferSeconds = seconds;
}

const isHls = (url: string) => /\.m3u8(\?|#|$)/i.test(url) || /\/video\/:\/transcode\/universal\/start\.m3u8/.test(url);

export type EngineEvent = 'playing' | 'pause' | 'waiting' | 'timeupdate' | 'ended' | 'error' | 'loadedmetadata' | 'captions';

/** How long a stream the player opens itself waits for its picture to have somewhere to go. */
const SURFACE_WAIT_MS = 2_000;

/** [p] let go, counted among the players a new one waits for (release.ts). */
function letGo(p: VideoPlayer): Promise<void> {
  return letGoOf(p.deinitialize());
}

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
let made = 0;

/** Where a stream comes from, for the log: the host and the kind of file, never who's asking. */
function described(url: string) {
  const m = /^[a-z]+:\/\/([^/?#:]+)[^?#]*?(\.[a-z0-9]{2,4})?(?:[?#]|$)/i.exec(url);
  return m ? `${m[1]} ${m[2] ?? ''}`.trim() : 'stream';
}

/**
 * To the stick's log, as a warning: it holds an app to 300 lines a second and drops its
 * ordinary ones first, and Amazon's player alone can pass that while a stream starts.
 */
function say(line: string) {
  console.warn(`[reely] ${line}`);
}

function safeText(value: unknown) {
  try {
    return value instanceof Error ? `${value.name}: ${value.message}` : JSON.stringify(value);
  } catch {
    return String(value);
  }
}

export class Engine {
  /** Which player this is: a surface belongs to one, and a new one gets a new surface. */
  readonly id = ++made;
  /** Replaced by a fresh one for each stream it opens itself (see renew). */
  player = new VideoPlayer();
  private hls: HlsInstance | null = null;
  private ready: Promise<void>;
  private surface: string | null = null;
  /** The surface the player itself has been given, which can be behind [surface]. */
  private bound: string | null = null;
  /** Whether this player has been given a stream to open itself (not through hls.js). */
  private sourced = false;
  /** Whether this player has had a stream at all, either way: the next one gets a fresh player. */
  private used = false;
  /** Where that stream was opened from, for opening it again on another surface. */
  private openedFrom = 0;
  /** Opens one at a time, each counted, so one asked for meanwhile replaces the one waiting. */
  private opening: Promise<void> = Promise.resolve();
  private asked = 0;
  private inFlight = 0;
  private reportUntil = 0;
  private url: string | null = null;
  private listeners = new Map<EngineEvent, Set<() => void>>();
  private failureListeners = new Set<(f: EngineFailure) => void>();
  private recovered = false;
  private gone = false;
  /** Whether the picture's moving: told as 'playing' when it starts, as Vega's player doesn't always say. */
  private flowing = false;
  private lastTime = -1;
  private watch: ReturnType<typeof setInterval> | null = null;
  /** While hls.js feeds the player: how long the JavaScript thread the remote waits on is held up. */
  private lagProbe: ReturnType<typeof setInterval> | null = null;
  private openedAt = 0;
  private ticks = 0;
  /** The stream's own closed captions, chosen in Subtitles: off until they are. */
  private captionChoice = -1;

  constructor(options: { muted?: boolean } = {}) {
    this.mutedNow = !!options.muted;
    this.ready = this.setUp(this.player);
  }

  private mutedNow = false;
  private captionsHandle: string | null = null;

  /** [p] made ready, and listened to for as long as it's the one in use. */
  private setUp(p: VideoPlayer): Promise<void> {
    return playersLetGo().then(() => p.initialize()).then(() => {
      p.autoplay = false;
      p.muted = this.mutedNow;
      for (const event of ['playing', 'pause', 'waiting', 'timeupdate', 'ended', 'loadedmetadata'] as EngineEvent[]) {
        p.addEventListener(event, () => {
          if (p !== this.player) return;
          if (event === 'playing') {
            if (!this.flowing) say(`player ${this.id} playing`);
            this.flowing = true;
          }
          // A stall part-way, to the log as it stands, and how it goes for a few seconds after:
          // a glitch well into a stream otherwise leaves nothing there.
          if (event === 'waiting' && this.flowing) {
            say(`player ${this.id} stalls`);
            this.report();
            this.reportUntil = Date.now() + 10_000;
          }
          if (event === 'waiting' || event === 'pause') this.flowing = false;
          this.emit(event);
        });
      }
      // A caption track the stream brings is kept hidden unless it's the one chosen.
      try {
        (p.textTracks as unknown as { addEventListener?: (t: string, h: () => void) => void }).addEventListener?.('addtrack', () => { if (p === this.player) this.applyCaptions(); });
      } catch {
        // No captions here.
      }
      p.addEventListener('error', () => {
        if (p !== this.player) return;
        const detail = p.error?.message ?? 'The player stopped.';
        // The stick's network reader giving up part-way ("GstSoupHTTPSrc ... streaming
        // stopped, reason error (-5)") is the connection, not the file: the screen picks
        // the stream up again from there rather than calling the title unplayable.
        const network = /souphttpsrc|httpsrc|streaming stopped|could not (read|connect)|timed? ?out|connection|http/i.test(detail);
        this.fail({ kind: network ? 'network' : 'media', status: null, detail });
      });
    });
  }

  /**
   * A fresh player on the same surface, the old one let go first. Vega's player opens one
   * stream: given a second, it carries the first's position into it ("resumed
   * position") and a live channel never got past buffering, as a channel opened without its
   * surface never did. A fresh player, its surface given before the stream, starts each time.
   */
  private async renew() {
    const old = this.player;
    try {
      old.pause();
    } catch {
      // Not started.
    }
    this.unloadSource();
    if (this.bound) {
      try {
        old.clearSurfaceHandle(this.bound);
      } catch {
        // Let go already.
      }
    }
    this.bound = null;
    this.sourced = false;
    this.used = false;
    void letGo(old);
    const fresh = new VideoPlayer();
    this.player = fresh;
    this.ready = this.setUp(fresh).then(() => {
      if (this.gone || fresh !== this.player) return;
      if (this.surface) this.bind(this.surface);
      if (this.captionsHandle) (fresh as unknown as { setCaptionViewHandle?: (h: string) => void }).setCaptionViewHandle?.(this.captionsHandle);
    });
    await this.ready;
    say(`player ${this.id} renewed`);
  }

  /** The player given [handle] to draw on. */
  private bind(handle: string) {
    try {
      this.player.setSurfaceHandle(handle);
      this.bound = handle;
    } catch (e) {
      console.warn('[reely] surface', e);
    }
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
    say(`player ${this.id} stopped: ${f.kind} ${f.status ?? ''} ${f.detail}`);
    this.failureListeners.forEach((h) => h(f));
  }

  /** Plays [url] from [startMs] in: HLS through hls.js, anything else as it is. */
  open(url: string, startMs = 0): Promise<void> {
    const ticket = ++this.asked;
    this.inFlight++;
    const run = this.opening.then(() => this.openNow(ticket, url, startMs)).finally(() => { this.inFlight--; });
    this.opening = run.catch(() => undefined);
    return run;
  }

  private async openNow(ticket: number, url: string, startMs: number) {
    const stale = () => this.gone || ticket !== this.asked;
    await this.ready;
    if (stale()) return;
    // Each stream on a fresh player, through hls.js as much as not: one that has played to
    // its end and been given the next episode's never started, where a fresh one, as
    // leaving and coming back gives, did.
    if (this.used) await this.renew();
    if (stale()) return;
    this.unloadSource();
    this.used = true;
    this.url = url;
    this.recovered = false;
    this.flowing = false;
    this.lastTime = -1;
    this.openedAt = Date.now();
    this.ticks = 0;
    this.captionChoice = -1;
    this.emit('captions');
    this.watchFlow();
    say(`player ${this.id} opens ${described(url)}${isHls(url) ? ' (HLS)' : ''}${this.surface ? '' : ', no surface yet'}`);
    if (isHls(url)) {
      polyfill();
      (globalThis as unknown as { gmedia: unknown }).gmedia = this.player;
      const Hls = (HlsLib as unknown as { Hls: new (config: object) => HlsInstance; Events: Record<string, string>; ErrorTypes: Record<string, string>; XhrLoader: unknown });
      const hls = new Hls.Hls({
        enableWorker: false,
        lowLatencyMode: false,
        backBufferLength: 30,
        maxBufferLength: bufferSeconds,
        maxMaxBufferLength: bufferSeconds * 2,
        // And no more than this much of it in hand, however high the bitrate.
        maxBufferSize: 30 * 1000 * 1000,
        startPosition: startMs > 0 ? startMs / 1000 : -1,
        // Segments through XMLHttpRequest: fetch's arrayBuffer() never settles on the stick,
        // so with Amazon's default no segment ever arrived (scripts/hlsjs.sh exports it).
        fLoader: Hls.XhrLoader,
      });
      // Each step once, to the log: where a stream that won't start got to.
      const steps = ['MEDIA_ATTACHED', 'MANIFEST_PARSED', 'BUFFER_CREATED', 'FRAG_LOADED', 'FRAG_BUFFERED'];
      for (const step of steps) {
        let told = false;
        hls.on(Hls.Events[step], () => {
          if (told) return;
          told = true;
          say(`player ${this.id} hls ${step}`);
        });
      }
      hls.on(Hls.Events.ERROR, (_e, data) => {
        if (!data?.fatal) {
          say(`player ${this.id} hls ${data?.type} ${data?.details}${data?.error ? `: ${String(data.error)}` : ''}`);
          return;
        }
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
      this.probeLag();
    } else {
      // A stream the player opens itself waits for its picture to have somewhere to go:
      // opened first, it got as far as "playing after buffering" and stayed there.
      await this.surfaceReady(SURFACE_WAIT_MS, stale);
      if (stale()) return;
      if (this.surface && this.bound !== this.surface) this.bind(this.surface);
      this.sourced = true;
      this.openedFrom = startMs;
      this.player.src = url;
      if (startMs > 0) this.player.currentTime = startMs / 1000;
    }
    this.player.play();
  }

  /** Resolves once a surface is attached, or after [ms] regardless. */
  private async surfaceReady(ms: number, stale: () => boolean) {
    const until = Date.now() + ms;
    while (!this.surface && !stale() && Date.now() < until) {
      await new Promise<void>((done) => setTimeout(() => done(), 50));
    }
    if (!this.surface) say(`player ${this.id} opens with no surface yet`);
    // The handle's given to the player once it's ready; it is by now, but a turn for it.
    await this.ready;
  }

  /** The same address again: a dropped connection's fresh stream. */
  async reload(startMs = 0) {
    if (this.url) await this.open(this.url, startMs);
  }

  play() { this.player.play(); }
  pause() { this.player.pause(); }
  get paused() { return this.player.paused; }
  /** Whether the picture's moving now: for a screen that starts listening after 'playing' was said. */
  get isPlaying() { return this.flowing; }
  get positionMs() {
    return Math.max(0, Math.floor((this.player.currentTime || 0) * 1000));
  }
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
  seek(ms: number) {
    // Each jump, and how the player stands for a few seconds after, to the log.
    say(`player ${this.id} seeks to ${Math.round(ms / 1000)}s from ${Math.round(this.positionMs / 1000)}s`);
    this.reportUntil = Date.now() + 10_000;
    this.player.currentTime = Math.max(0, ms / 1000);
  }
  /** Before the player's ready this is kept, and set once it is: Vega's player isn't there to be told yet. */
  set muted(on: boolean) {
    this.mutedNow = on;
    void this.ready.then(() => {
      if (this.gone) return;
      try {
        this.player.muted = on;
      } catch {
        // Let go meanwhile.
      }
    });
  }

  /** Sound tracks hls.js found in the stream, for a channel with more than one language. */
  get audioTracks() { return this.hls?.audioTracks ?? []; }
  set audioTrack(id: number) { if (this.hls) this.hls.audioTrack = id; }
  get audioTrack() { return this.hls?.audioTrack ?? -1; }
  /** Subtitles a channel's stream carries, where hls.js found any; -1 is off. */
  get subtitleTracks() { return this.hls?.subtitleTracks ?? []; }
  get subtitleTrack() { return this.hls?.subtitleTrack ?? -1; }
  set subtitleTrack(id: number) {
    if (this.hls) this.hls.subtitleTrack = id;
    this.emit('captions');
  }
  /** The picture's size once its first frame is in, for Playback info. */
  get videoSize(): { width: number; height: number } | null {
    try {
      const v = this.player as unknown as { videoWidth?: number; videoHeight?: number };
      return v.videoWidth && v.videoHeight ? { width: v.videoWidth, height: v.videoHeight } : null;
    } catch {
      return null;
    }
  }

  /**
   * The picture counted as playing once its clock moves, and as stalled when it stops while
   * it should be going: Vega's player can play on without ever saying 'playing'.
   */
  private watchFlow() {
    if (this.watch) return;
    this.watch = setInterval(() => {
      if (this.gone) return;
      // How it stands, every two seconds for a stream's first half-minute: for the log.
      if (++this.ticks % 4 === 0 && (Date.now() - this.openedAt < 30_000 || Date.now() < this.reportUntil)) this.report();
      let now = -1;
      try {
        now = this.player.paused ? -1 : this.player.currentTime || 0;
      } catch {
        return;
      }
      if (now < 0) {
        this.lastTime = -1;
        return;
      }
      const moved = this.lastTime >= 0 && now > this.lastTime + 0.05;
      this.lastTime = now;
      if (moved && !this.flowing) {
        this.flowing = true;
        say(`player ${this.id} playing`);
        this.emit('playing');
      }
    }, 500);
  }

  private report() {
    try {
      const p = this.player as unknown as { paused: boolean; currentTime: number; readyState?: number; seeking?: boolean; buffered: { length: number; start(i: number): number; end(i: number): number } };
      const ranges: string[] = [];
      for (let i = 0; i < p.buffered.length; i++) ranges.push(`${p.buffered.start(i).toFixed(1)}-${p.buffered.end(i).toFixed(1)}`);
      const h = this.hls as unknown as { streamController?: { state?: string }; currentLevel?: number; levels?: unknown[] } | null;
      say(`player ${this.id} at ${(p.currentTime || 0).toFixed(1)}s ${p.paused ? 'paused' : 'going'}${p.seeking ? ' seeking' : ''} ready=${p.readyState ?? '?'} buffered=[${ranges.join(',')}]` +
        (h ? ` hls=${h.streamController?.state ?? '?'} level=${h.currentLevel ?? '?'}/${h.levels?.length ?? 0}` : '') + (this.surface ? '' : ' no surface'));
    } catch (e) {
      say(`player ${this.id} status unreadable: ${safeText(e)}`);
    }
  }

  /** The stream's own closed captions (a channel's, in MPEG-TS): their names, in order. */
  get captionTracks(): Array<{ id: number; name: string; lang?: string }> {
    return this.nativeCaptions().map((t, i) => ({ id: i, name: t.label ?? '', lang: t.language || undefined }));
  }
  get captionTrack() { return this.captionChoice; }
  /** One of [captionTracks] shown, or -1 for none. */
  set captionTrack(i: number) {
    this.captionChoice = i;
    this.applyCaptions();
    this.emit('captions');
  }
  /** Whether anything's to be drawn in the captions view: hls.js's subtitles or the stream's captions. */
  get captionsShowing() { return this.captionChoice >= 0 || (this.hls?.subtitleTrack ?? -1) >= 0; }

  private nativeCaptions(): Array<{ kind?: string; label?: string; language?: string; mode?: string }> {
    try {
      const list = this.player.textTracks as unknown as ArrayLike<{ kind?: string; label?: string; language?: string; mode?: string }>;
      const out = [];
      for (let i = 0; i < (list?.length ?? 0); i++) {
        const t = list[i];
        if (t && (t.kind === 'captions' || t.kind === 'subtitles')) out.push(t);
      }
      return out;
    } catch {
      return [];
    }
  }

  private applyCaptions() {
    // hls.js's own subtitle tracks are its to show; these are the stream's.
    if (this.hls) return;
    this.nativeCaptions().forEach((t, i) => {
      try {
        const want = i === this.captionChoice ? 'showing' : 'hidden';
        if (t.mode !== want) t.mode = want;
      } catch {
        // Gone meanwhile.
      }
    });
  }

  /** The surface it's drawn on: given once the player's ready, as Vega's player needs. */
  attachSurface(handle: string) {
    this.surface = handle;
    void this.ready.then(() => {
      if (this.gone || this.surface !== handle || this.bound === handle) return;
      // A stream the player opened itself stays with the surface it started on: moved to
      // another (the guide's preview going full screen) it stood still, so it starts again
      // there, on a fresh player.
      // (One being opened binds whatever surface there is by then itself.)
      if (this.sourced && this.url && this.inFlight === 0) {
        say(`player ${this.id} moves to another surface`);
        void this.open(this.url, this.openedFrom);
        return;
      }
      this.bind(handle);
    });
  }

  /** A surface going: let go only if it's the one in use, not an old one closing after a new one opened. */
  detachSurface(handle: string) {
    if (this.surface !== handle) return;
    this.surface = null;
    void this.ready.then(() => {
      if (this.gone || this.bound !== handle) return;
      this.bound = null;
      try {
        this.player.clearSurfaceHandle(handle);
      } catch {
        // Let go already.
      }
    });
  }

  attachCaptions(handle: string) {
    this.captionsHandle = handle;
    void this.ready.then(() => {
      if (this.gone) return;
      (this.player as unknown as { setCaptionViewHandle?: (h: string) => void }).setCaptionViewHandle?.(handle);
    });
  }

  /**
   * Every ten seconds, to the log: the longest the JavaScript thread was held up, and how
   * often by more than a remote press can wait unnoticed (150 ms). hls.js works on that
   * thread, so this is how smooth the controls are while it feeds the player.
   */
  private probeLag() {
    if (this.lagProbe) clearInterval(this.lagProbe);
    const TICK = 100;
    let last = Date.now();
    let worst = 0;
    let stalls = 0;
    let since = last;
    this.lagProbe = setInterval(() => {
      const now = Date.now();
      const late = now - last - TICK;
      last = now;
      if (late > worst) worst = late;
      if (late > 150) stalls++;
      if (now - since >= 10_000) {
        say(`player ${this.id} js thread: longest hold-up ${Math.max(0, Math.round(worst))}ms, ${stalls} over 150ms in ${Math.round((now - since) / 1000)}s, ` +
          `${((this.bufferedMs - this.positionMs) / 1000).toFixed(1)}s ahead${this.droppedFrames()}${this.player.paused ? ' (paused)' : ''}`);
        worst = 0;
        stalls = 0;
        since = now;
      }
    }, TICK);
  }

  /** The frames the player has dropped so far, where it counts them, for the log. */
  private droppedFrames(): string {
    try {
      const q = (this.player as unknown as { getVideoPlaybackQuality?: () => { droppedVideoFrames?: number; totalVideoFrames?: number } }).getVideoPlaybackQuality?.();
      return q && typeof q.droppedVideoFrames === 'number' ? `, ${q.droppedVideoFrames}/${q.totalVideoFrames ?? '?'} frames dropped` : '';
    } catch {
      return '';
    }
  }

  private unloadSource() {
    if (this.lagProbe) clearInterval(this.lagProbe);
    this.lagProbe = null;
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
    if (this.watch) clearInterval(this.watch);
    this.watch = null;
    try {
      this.player.pause();
    } catch {
      // Not started.
    }
    this.unloadSource();
    await this.ready.catch(() => undefined);
    await letGo(this.player);
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

/** Where [engine]'s picture is drawn, and its captions over it when some are chosen. */
export function VideoSurface(props: { engine: Engine; style?: StyleProp<ViewStyle>; captions?: boolean }) {
  const { engine } = props;
  const [showing, setShowing] = useState(engine.captionsShowing);
  useEffect(() => {
    setShowing(engine.captionsShowing);
    return engine.on('captions', () => setShowing(engine.captionsShowing));
  }, [engine]);
  return (
    <View style={[{ backgroundColor: '#000000', overflow: 'hidden' }, props.style]} pointerEvents="none">
      <KeplerVideoSurfaceView key={`video:${engine.id}`} style={{ position: 'absolute', left: 0, right: 0, top: 0, bottom: 0 }}
        onSurfaceViewCreated={(handle: string) => engine.attachSurface(handle)}
        onSurfaceViewDestroyed={(handle: string) => engine.detachSurface(handle)} />
      {props.captions ? (
        <KeplerCaptionsView key={`captions:${engine.id}`} style={{ position: 'absolute', left: 0, right: 0, top: 0, bottom: 0 }} show={showing}
          onCaptionViewCreated={(handle: string) => engine.attachCaptions(handle)} />
      ) : null}
    </View>
  );
}
