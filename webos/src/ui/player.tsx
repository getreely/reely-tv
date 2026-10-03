import { useEffect, useRef, useState } from "preact/hooks";
import type { PlexItem } from "../api/plex";
import type { App, Playing, Prefs } from "../app/store";
import { browserCanPlay, plan, REPORT_EVERY_MS, SKIP_MS } from "../app/playback";
import { focus, onKeys } from "./focus";
import { Spinner } from "./parts";

const clock = (ms: number) => {
  const total = Math.max(0, Math.floor(ms / 1000));
  const h = Math.floor(total / 3600), m = Math.floor((total % 3600) / 60), s = total % 60;
  const two = (n: number) => String(n).padStart(2, "0");
  return h > 0 ? `${h}:${two(m)}:${two(s)}` : `${m}:${two(s)}`;
};

const episodeLine = (i: PlexItem) =>
  [i.parentIndex != null ? `S${i.parentIndex}` : null, i.index != null ? `E${i.index}` : null, i.title].filter(Boolean).join(" · ");

/** Minutes the sleep timer offers, as the Fire TV's does; -1 is the end of this episode. */
export const SLEEP_CHOICES = [0, 15, 30, 45, 60, 90, -1];
const END_OF_EPISODE = -1;

type Sleep = { atMs: number | null; endOfEpisode: boolean } | null;

/**
 * The player: the TV's own, full screen. OK pauses and plays; left and right skip ten
 * seconds; down opens sound, subtitles, chapters and the sleep timer; Back stops,
 * telling Plex where it got to. Skip Intro over an intro, and Up Next when the credits
 * start, as on the Fire TV. A file the TV turns out not to be able to play after all is
 * handed to Plex to convert, from the same point.
 */
export function Player(props: { app: App; playing: Playing; prefs?: Prefs }) {
  const { app, playing } = props;
  const prefs = props.prefs ?? app.state.prefs;
  const video = useRef<HTMLVideoElement>(null);
  const [position, setPosition] = useState(playing.startMs);
  const [duration, setDuration] = useState(playing.item.durationMs);
  const [paused, setPaused] = useState(false);
  const [waiting, setWaiting] = useState(true);
  const [controls, setControls] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [panel, setPanel] = useState(false);
  const [upNext, setUpNext] = useState<{ next: PlexItem; endsAt: number | null } | null>(null);
  const [, tick] = useState(0);
  const [sleep, setSleep] = useState<Sleep>(null);
  const hideAt = useRef(0);
  const introSkipped = useRef<string | null>(null);
  const creditsOffered = useRef<string | null>(null);
  const leaving = useRef(false);
  // Skipping: held (pressed again and again), it goes further each time; a picture of where it lands.
  const skips = useRef({ at: 0, count: 0 });
  const [preview, setPreview] = useState<{ ms: number; until: number } | null>(null);
  // The sleep timer, read by the video's handlers without restarting them.
  const sleepRef = useRef<Sleep>(null);
  sleepRef.current = sleep;

  const nudge = () => {
    setControls(true);
    hideAt.current = Date.now() + 4_000;
  };
  const now = () => (video.current ? video.current.currentTime * 1000 : position);
  const total = () => (video.current && isFinite(video.current.duration) && video.current.duration > 0 ? video.current.duration * 1000 : duration);
  const key = playing.item.ratingKey;
  const next = app.nextInQueue();
  const intro = playing.playback.markers.find((m) => m.type === "intro") ?? null;
  const credits = playing.playback.markers.filter((m) => m.type === "credits").sort((a, b) => b.startMs - a.startMs)[0] ?? null;
  const inIntro = !!intro && position >= intro.startMs && position < intro.endMs - 1000;

  const stop = () => {
    if (leaving.current) return;
    leaving.current = true;
    void app.stop(now(), total());
  };
  /** On to [item], the stop told to Plex first. */
  const playNext = (item: PlexItem) => {
    if (leaving.current) return;
    leaving.current = true;
    setUpNext(null);
    void app.report(now(), total(), "stopped").then(() => app.play(item, false, (p) => playDirect(video.current ?? document.createElement("video"), p), playing.queue));
  };

  useEffect(() => {
    leaving.current = false;
    setUpNext(null);
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
    const pause = () => { setPaused(true); if (!leaving.current) void app.report(now(), total(), "paused"); };
    const play = () => setPaused(false);
    const wait = () => setWaiting(true);
    const ready = () => setWaiting(false);
    const fail = () => {
      // The TV said it could and then couldn't: Plex converts it.
      if (app.convert(now())) return;
      setError("This didn't play. Check the connection to your Plex server and try again.");
    };
    const ended = () => {
      // The sleep timer set for this episode's end: it ends here, rather than going on.
      const following = sleepRef.current?.endOfEpisode ? null : app.nextInQueue();
      if (following) playNext(following);
      else stop();
    };
    const on: Array<[string, () => void]> = [["timeupdate", time], ["durationchange", meta], ["pause", pause], ["play", play],
      ["waiting", wait], ["playing", ready], ["canplay", ready], ["error", fail], ["ended", ended]];
    on.forEach(([e, f]) => v.addEventListener(e, f));
    const report = setInterval(() => { if (!v.paused && !leaving.current) void app.report(now(), total(), "playing"); }, REPORT_EVERY_MS);
    const hide = setInterval(() => { if (Date.now() > hideAt.current && !v.paused) setControls(false); }, 500);
    nudge();
    return () => {
      on.forEach(([e, f]) => v.removeEventListener(e, f));
      clearInterval(report);
      clearInterval(hide);
    };
  }, [playing.url]);

  useEffect(() => {
    if (!sleep?.atMs) return;
    const t = setTimeout(stop, Math.max(0, sleep.atMs - Date.now()));
    return () => clearTimeout(t);
  }, [sleep]);

  // Past the intro by itself, when Settings says so; once, so going back into it stays.
  useEffect(() => {
    if (inIntro && prefs.skipIntros && intro && introSkipped.current !== key && video.current) {
      introSkipped.current = key;
      video.current.currentTime = intro.endMs / 1000;
    }
  }, [inIntro]);

  // Up Next when the credits start; or straight on, when Settings says so. Once an episode.
  const inCredits = !!credits && position >= credits.startMs;
  useEffect(() => {
    if (!inCredits || !next || creditsOffered.current === key) return;
    creditsOffered.current = key;
    if (sleepRef.current?.endOfEpisode) return;
    if (prefs.skipCredits) playNext(next);
    else setUpNext({ next, endsAt: prefs.upNextSeconds > 0 ? Date.now() + prefs.upNextSeconds * 1000 : null });
  }, [inCredits]);
  useEffect(() => {
    if (!upNext?.endsAt) return;
    const t = setInterval(() => {
      if (Date.now() >= upNext.endsAt!) playNext(upNext.next);
      else tick((n) => n + 1);
    }, 250);
    return () => clearInterval(t);
  }, [upNext]);

  useEffect(
    () =>
      onKeys((a) => {
        const v = video.current;
        if (!v) return true;
        nudge();
        if (panel) {
          // The panel's own cursor moves and presses; Back closes it.
          if (a === "back") { setPanel(false); return true; }
          return !(a === "up" || a === "down" || a === "left" || a === "right" || a === "ok");
        }
        if (upNext) {
          if (a === "ok") { playNext(upNext.next); return true; }
          if (a === "back") { setUpNext(null); return true; }
        }
        switch (a) {
          case "back":
          case "stop":
            stop();
            return true;
          case "ok":
            if (inIntro && intro) { introSkipped.current = key; v.currentTime = intro.endMs / 1000; return true; }
            if (v.paused) void v.play(); else v.pause();
            return true;
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
          case "right":
          case "forward": {
            const back = a === "left" || a === "rewind";
            const s = skips.current;
            s.count = Date.now() - s.at < 450 ? s.count + 1 : 0;
            s.at = Date.now();
            const step = (s.count < 4 ? SKIP_MS : s.count < 10 ? SKIP_MS * 3 : SKIP_MS * 6) / 1000;
            const to = back ? Math.max(0, v.currentTime - step) : Math.min(v.duration || Infinity, v.currentTime + step);
            v.currentTime = to;
            setPreview({ ms: Math.floor(to * 1000), until: Date.now() + 1500 });
            return true;
          }
          case "down":
          case "up":
          case "info":
            setPanel(true);
            return true;
        }
        return true;
      }),
    [playing.url, panel, upNext, inIntro],
  );

  const choose = (audio: string | undefined, subtitle: string | undefined) => {
    setPanel(false);
    void app.chooseStreams(audio, subtitle, now(), (p) => playDirect(video.current ?? document.createElement("video"), p));
  };
  const setSleepFor = (minutes: number) => {
    setPanel(false);
    setSleep(minutes === 0 ? null : minutes === END_OF_EPISODE ? { atMs: null, endOfEpisode: true } : { atMs: Date.now() + minutes * 60_000, endOfEpisode: false });
  };

  const title = playing.item.type === "episode" ? playing.item.grandparentTitle ?? playing.item.title : playing.item.title;
  const sub = playing.item.type === "episode" ? episodeLine(playing.item) : null;
  const fraction = duration > 0 ? Math.min(1, position / duration) : 0;
  const sleepNote = sleep?.endOfEpisode ? "Sleep at the end of this" : sleep?.atMs ? `Sleep in ${Math.max(1, Math.ceil((sleep.atMs - Date.now()) / 60_000))} min` : null;
  useEffect(() => {
    if (!preview) return;
    const t = setTimeout(() => setPreview(null), Math.max(0, preview.until - Date.now()));
    return () => clearTimeout(t);
  }, [preview]);
  const previewUrl = preview && playing.playback.previewUrl ? playing.playback.previewUrl.replace("{ms}", String(preview.ms)) : null;
  const seconds = upNext?.endsAt ? Math.max(0, Math.ceil((upNext.endsAt - Date.now()) / 1000)) : null;
  return (
    <div class="player" data-layer>
      <video ref={video} class="video" playsInline />
      {waiting && !error ? <div class="player-wait"><Spinner /></div> : null}
      {error ? <div class="player-wait"><p class="note error">{error}</p></div> : null}
      {inIntro && !upNext && !panel ? <div class="skip-prompt">Skip Intro</div> : null}
      {upNext ? (
        <div class="up-next">
          <div class="facts">Up next</div>
          <div class="player-title">{episodeLine(upNext.next)}</div>
          <div class="facts">{seconds != null ? `Playing in ${seconds}  ·  OK to play now  ·  Back to keep watching` : "OK to play  ·  Back to keep watching"}</div>
        </div>
      ) : null}
      <div class={"player-bar" + (controls || paused ? " on" : "")}>
        <div class="player-title">{title}</div>
        {sub ? <div class="facts">{sub}</div> : null}
        {preview ? (
          <div class="preview" style={{ left: `${Math.min(92, Math.max(8, (duration > 0 ? preview.ms / duration : 0) * 100))}%` }}>
            {previewUrl ? <img src={previewUrl} alt="" /> : null}
            <span>{clock(preview.ms)}</span>
          </div>
        ) : null}
        <div class="scrub"><i style={{ width: `${fraction * 100}%` }} /></div>
        <div class="player-times">
          <span>{paused ? "Paused  ·  " : ""}{clock(position)}{sleepNote ? `  ·  ${sleepNote}` : ""}</span>
          <span>−{clock(Math.max(0, duration - position))}</span>
        </div>
      </div>
      {panel ? (
        <Options
          playing={playing}
          sleep={sleep}
          onSound={(id) => choose(id, undefined)}
          onSubtitles={(id) => choose(undefined, id)}
          onChapter={(ms) => { setPanel(false); if (video.current) video.current.currentTime = ms / 1000; }}
          onSleep={setSleepFor}
        />
      ) : null}
    </div>
  );
}

/** Sound, subtitles, chapters and the sleep timer, down the right of the picture. */
function Options(props: {
  playing: Playing;
  sleep: Sleep;
  onSound: (id: string) => void;
  onSubtitles: (id: string) => void;
  onChapter: (ms: number) => void;
  onSleep: (minutes: number) => void;
}) {
  const p = props.playing.playback;
  const panel = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const first = panel.current?.querySelector<HTMLElement>("[data-autofocus]") ?? panel.current?.querySelector<HTMLElement>("[data-focus]");
    focus(first);
  }, []);
  const subtitlesOn = p.subtitleStreams.find((s) => s.selected) ?? null;
  const chosen = (key: string, on: boolean, label: string, onPress: () => void, auto = false) => (
    <button key={key} class={"option" + (on ? " on" : "")} data-focus data-autofocus={auto ? "" : undefined} onClick={onPress}>
      <span class="tick">{on ? "✓" : ""}</span>
      {label}
    </button>
  );
  const sleepLabel = (m: number) => (m === 0 ? "Off" : m === END_OF_EPISODE ? "End of this episode" : `${m} minutes`);
  const sleepOn = (m: number) => (m === 0 ? !props.sleep : m === END_OF_EPISODE ? !!props.sleep?.endOfEpisode : false);
  return (
    <div class="options" data-layer ref={panel}>
      {p.audioStreams.length > 1 ? (
        <section>
          <h3>Sound</h3>
          {p.audioStreams.map((s, i) => chosen(`a${s.id}`, s.selected || (!p.audioStreams.some((x) => x.selected) && i === 0), s.label, () => props.onSound(s.id), i === 0))}
        </section>
      ) : null}
      {p.subtitleStreams.length ? (
        <section>
          <h3>Subtitles</h3>
          {chosen("s-off", !subtitlesOn, "Off", () => props.onSubtitles("0"), p.audioStreams.length <= 1)}
          {p.subtitleStreams.map((s) => chosen(`s${s.id}`, s.selected, s.label, () => props.onSubtitles(s.id)))}
        </section>
      ) : null}
      {p.chapters.length ? (
        <section>
          <h3>Chapters</h3>
          {p.chapters.map((c) => (
            <button key={c.startMs} class="option" data-focus onClick={() => props.onChapter(c.startMs)}>
              <span class="tick" />
              {c.title}  <span class="facts">{clock(c.startMs)}</span>
            </button>
          ))}
        </section>
      ) : null}
      <section>
        <h3>Sleep timer</h3>
        {SLEEP_CHOICES.filter((m) => m !== END_OF_EPISODE || props.playing.item.type === "episode").map((m) =>
          chosen(`z${m}`, sleepOn(m), sleepLabel(m), () => props.onSleep(m), !p.audioStreams.length && !p.subtitleStreams.length && m === 0),
        )}
      </section>
    </div>
  );
}

/** As the store asks it: the file as it is when the TV says it can play it. */
export function playDirect(v: HTMLVideoElement, p: Parameters<typeof plan>[0]) {
  return plan(p, browserCanPlay(v)).direct;
}
