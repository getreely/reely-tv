import { useEffect, useRef, useState } from "preact/hooks";
import type { App, Playing } from "../app/store";
import { browserCanPlay, plan, REPORT_EVERY_MS, SKIP_MS } from "../app/playback";
import { onKeys } from "./focus";
import { Spinner } from "./parts";

const clock = (ms: number) => {
  const total = Math.max(0, Math.floor(ms / 1000));
  const h = Math.floor(total / 3600), m = Math.floor((total % 3600) / 60), s = total % 60;
  const two = (n: number) => String(n).padStart(2, "0");
  return h > 0 ? `${h}:${two(m)}:${two(s)}` : `${m}:${two(s)}`;
};

/**
 * The player: the TV's own, full screen. OK pauses and plays; left and right skip ten
 * seconds; Back stops, telling Plex where it got to. A file the TV turns out not to be
 * able to play after all is handed to Plex to convert, from the same point.
 */
export function Player(props: { app: App; playing: Playing }) {
  const { app, playing } = props;
  const video = useRef<HTMLVideoElement>(null);
  const [position, setPosition] = useState(playing.startMs);
  const [duration, setDuration] = useState(playing.item.durationMs);
  const [paused, setPaused] = useState(false);
  const [waiting, setWaiting] = useState(true);
  const [controls, setControls] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const hideAt = useRef(0);

  const nudge = () => {
    setControls(true);
    hideAt.current = Date.now() + 4_000;
  };
  const now = () => (video.current ? video.current.currentTime * 1000 : position);
  const total = () => (video.current && isFinite(video.current.duration) && video.current.duration > 0 ? video.current.duration * 1000 : duration);

  useEffect(() => {
    const v = video.current;
    if (!v) return;
    v.src = playing.url;
    const start = () => {
      if (playing.startMs > 0) v.currentTime = playing.startMs / 1000;
      void v.play().catch(() => setPaused(true));
    };
    v.addEventListener("loadedmetadata", start, { once: true });
    v.load();
    return () => v.removeEventListener("loadedmetadata", start);
  }, [playing.url]);

  useEffect(() => {
    const v = video.current!;
    const time = () => setPosition(v.currentTime * 1000);
    const meta = () => isFinite(v.duration) && v.duration > 0 && setDuration(v.duration * 1000);
    const pause = () => { setPaused(true); void app.report(now(), total(), "paused"); };
    const play = () => setPaused(false);
    const wait = () => setWaiting(true);
    const ready = () => setWaiting(false);
    const fail = () => {
      // The TV said it could and then couldn't: Plex converts it.
      if (app.convert(now())) return;
      setError("This didn't play. Check the connection to your Plex server and try again.");
    };
    const ended = () => {
      const next = app.nextInQueue();
      void app.report(total(), total(), "stopped").then(() => {
        if (next) void app.play(next, false, (p) => playDirect(v, p), playing.queue);
        else void app.stop(total(), total());
      });
    };
    const on: Array<[string, () => void]> = [["timeupdate", time], ["durationchange", meta], ["pause", pause], ["play", play],
      ["waiting", wait], ["playing", ready], ["canplay", ready], ["error", fail], ["ended", ended]];
    on.forEach(([e, f]) => v.addEventListener(e, f));
    const report = setInterval(() => { if (!v.paused) void app.report(now(), total(), "playing"); }, REPORT_EVERY_MS);
    const hide = setInterval(() => { if (Date.now() > hideAt.current && !v.paused) setControls(false); }, 500);
    nudge();
    return () => {
      on.forEach(([e, f]) => v.removeEventListener(e, f));
      clearInterval(report);
      clearInterval(hide);
    };
  }, [playing.url]);

  useEffect(
    () =>
      onKeys((a) => {
        const v = video.current;
        if (!v) return true;
        nudge();
        switch (a) {
          case "back":
          case "stop":
            void app.stop(now(), total());
            return true;
          case "ok":
          case "playPause":
            if (v.paused) void v.play(); else v.pause();
            return true;
          case "play":
            void v.play();
            return true;
          case "pause":
            v.pause();
            return true;
          case "left":
          case "rewind":
            v.currentTime = Math.max(0, v.currentTime - SKIP_MS / 1000);
            return true;
          case "right":
          case "forward":
            v.currentTime = Math.min(v.duration || Infinity, v.currentTime + SKIP_MS / 1000);
            return true;
        }
        return true;
      }),
    [playing.url],
  );

  const title = playing.item.type === "episode" ? playing.item.grandparentTitle ?? playing.item.title : playing.item.title;
  const sub = playing.item.type === "episode"
    ? [playing.item.parentIndex != null ? `S${playing.item.parentIndex}` : null, playing.item.index != null ? `E${playing.item.index}` : null, playing.item.title].filter(Boolean).join(" · ")
    : null;
  const fraction = duration > 0 ? Math.min(1, position / duration) : 0;
  return (
    <div class="player" data-layer>
      <video ref={video} class="video" playsInline />
      {waiting && !error ? <div class="player-wait"><Spinner /></div> : null}
      {error ? <div class="player-wait"><p class="note error">{error}</p></div> : null}
      <div class={"player-bar" + (controls || paused ? " on" : "")}>
        <div class="player-title">{title}</div>
        {sub ? <div class="facts">{sub}</div> : null}
        <div class="scrub"><i style={{ width: `${fraction * 100}%` }} /></div>
        <div class="player-times">
          <span>{paused ? "Paused  ·  " : ""}{clock(position)}</span>
          <span>−{clock(Math.max(0, duration - position))}</span>
        </div>
      </div>
    </div>
  );
}

/** As the store asks it: the file as it is when the TV says it can play it. */
export function playDirect(v: HTMLVideoElement, p: Parameters<typeof plan>[0]) {
  return plan(p, browserCanPlay(v)).direct;
}
