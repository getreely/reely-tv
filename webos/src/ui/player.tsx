import { useEffect, useRef, useState } from "preact/hooks";
import type { PlexChapter, PlexItem, PlexStream } from "../api/plex";
import type { App, Playing, Prefs } from "../app/store";
import { browserCanPlay, plan, REPORT_EVERY_MS, SKIP_MS } from "../app/playback";
import { focus, onKeys } from "./focus";
import { ask } from "../core/http";
import { cueAt, parseSubtitles, type Cue } from "../core/subtitles";
import type { PlexOnlineSubtitle } from "../api/plex";
import { Spinner } from "./parts";
import { IPTV_SOURCE } from "../api/vod";

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
// After the connection drops: fresh tries, the wait growing by this each time, then OK.
const RECONNECT_TRIES = 3;
const RECONNECT_WAIT_MS = 3_000;

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
  const [panel, setPanel] = useState<Panel | null>(null);
  // Up Next over the screen, the credits in a window in the corner. Held: any press but
  // Play next stops the countdown, as on the Fire TV: somebody reaching for the remote is
  // making up their mind.
  const [upNext, setUpNext] = useState<{ next: PlexItem; endsAt: number | null; held: boolean } | null>(null);
  // Where the cursor is among the controls: the bar, a button by its place in the row, or
  // nowhere (the controls hidden, or only the bar shown by a skip).
  const [cursor, setCursor] = useState<"bar" | number | null>(null);
  const [, tick] = useState(0);
  const [sleep, setSleep] = useState<Sleep>(null);
  const hideAt = useRef(0);
  const introSkipped = useRef<string | null>(null);
  const creditsOffered = useRef<string | null>(null);
  const leaving = useRef(false);
  /*
   * The connection dropped: tries at a fresh stream so far, the next one waiting, and
   * whether it's given up and waits for OK. Plex's own app picks up again the same way.
   */
  const drops = useRef(0);
  const dropTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const stuckAt = useRef<number | null>(null);
  // Skipping: held (pressed again and again), it goes further each time; a picture of where it lands.
  const skips = useRef({ at: 0, count: 0 });
  const [preview, setPreview] = useState<{ ms: number; until: number } | null>(null);
  // Text subtitles, read once and drawn over the picture at the size and background chosen.
  const [cues, setCues] = useState<Cue[]>([]);
  const textSub = playing.textSubtitle;
  useEffect(() => {
    setCues([]);
    if (!textSub) return;
    let live = true;
    void ask(textSub.url, { timeoutMs: 30_000 })
      .then((r) => r.text())
      .then((text) => { if (live) setCues(parseSubtitles(text, textSub.codec)); })
      .catch(() => undefined);
    return () => { live = false; };
  }, [textSub?.url]);
  // Finding subtitles online: what was found, and which is being added.
  const [finding, setFinding] = useState<{ language: string; results: PlexOnlineSubtitle[] | null; error: string | null; adding: string | null } | null>(null);
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

  // Another episode: whatever was offered for the last one goes. The same one again (a
  // sound track or subtitles chosen, which reloads it) keeps Up Next if it's up.
  const shownKey = useRef(key);
  useEffect(() => {
    leaving.current = false;
    if (shownKey.current !== key) {
      setUpNext(null);
      drops.current = 0;
    }
    shownKey.current = key;
    setError(null);
    stuckAt.current = null;
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
  }, [playing.url, playing.attempt]);
  useEffect(() => () => { if (dropTimer.current) clearTimeout(dropTimer.current); }, []);

  useEffect(() => {
    const v = video.current!;
    const time = () => setPosition(v.currentTime * 1000);
    const meta = () => isFinite(v.duration) && v.duration > 0 && setDuration(v.duration * 1000);
    const pause = () => { setPaused(true); if (!leaving.current) void app.report(now(), total(), "paused"); };
    const play = () => setPaused(false);
    const wait = () => setWaiting(true);
    const ready = () => setWaiting(false);
    // Playing again: the next drop gets its full set of tries.
    const going = () => { drops.current = 0; };
    const fail = () => {
      if (v.error?.code === MediaError.MEDIA_ERR_NETWORK) {
        const at = now();
        if (drops.current < RECONNECT_TRIES) {
          drops.current++;
          setError("Reconnecting…");
          if (dropTimer.current) clearTimeout(dropTimer.current);
          dropTimer.current = setTimeout(() => void app.reopen(at), RECONNECT_WAIT_MS * drops.current);
        } else {
          stuckAt.current = at;
          setError(playing.base === IPTV_SOURCE
            ? "Lost the connection to your IPTV provider. Press OK to try again."
            : "Lost the connection to your Plex server. Press OK to try again.");
        }
        return;
      }
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
      ["waiting", wait], ["playing", ready], ["canplay", ready], ["playing", going], ["error", fail], ["ended", ended]];
    on.forEach(([e, f]) => v.addEventListener(e, f));
    const report = setInterval(() => { if (!v.paused && !leaving.current) void app.report(now(), total(), "playing"); }, REPORT_EVERY_MS);
    const hide = setInterval(() => {
      if (Date.now() > hideAt.current && !v.paused) {
        setControls(false);
        setCursor(null);
      }
    }, 500);
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
    else setUpNext({ next, endsAt: prefs.upNextSeconds > 0 ? Date.now() + prefs.upNextSeconds * 1000 : null, held: false });
  }, [inCredits]);
  useEffect(() => {
    if (!upNext?.endsAt || upNext.held) return;
    const t = setInterval(() => {
      if (Date.now() >= upNext.endsAt!) playNext(upNext.next);
      else tick((n) => n + 1);
    }, 250);
    return () => clearInterval(t);
  }, [upNext]);

  // The row of buttons under the bar, in order: the episodes either side and play in the
  // middle, then a panel each for chapters, subtitles, sound, the sleep timer and what's playing.
  const queueAt = playing.queue.findIndex((q) => q.ratingKey === key);
  const previous = queueAt > 0 ? playing.queue[queueAt - 1] : null;
  const episodes = playing.item.type === "episode" && playing.queue.length > 1;
  const buttons: ControlButton[] = [
    ...(episodes ? [{ id: "previous", label: "Previous episode", glyph: "previous" as const, disabled: !previous, onPress: () => previous && playNext(previous) }] : []),
    { id: "play", label: paused ? "Play" : "Pause", glyph: paused ? "play" as const : "pause" as const, primary: true, onPress: () => togglePlay() },
    ...(episodes ? [{ id: "next", label: "Next episode", glyph: "next" as const, disabled: !next, onPress: () => next && playNext(next) }] : []),
    ...(playing.playback.chapters.length ? [{ id: "chapters", label: "Chapters", glyph: "chapters" as const, onPress: () => setPanel("chapters") }] : []),
    { id: "subtitles", label: "Subtitles", glyph: "subtitles" as const, onPress: () => setPanel("subtitles") },
    { id: "audio", label: "Audio", glyph: "audio" as const, onPress: () => setPanel("audio") },
    { id: "sleep", label: "Sleep timer", glyph: "sleep" as const, on: !!sleep, onPress: () => setPanel("sleep") },
    { id: "info", label: "Playback info", glyph: "info" as const, onPress: () => setPanel("info") },
  ];
  const playAt = buttons.findIndex((b) => b.id === "play");
  const togglePlay = () => {
    const v = video.current;
    if (v) { if (v.paused) void v.play(); else v.pause(); }
  };
  /** One along the row from [from], past any that can't be pressed. */
  const along = (from: number, step: 1 | -1) => {
    for (let i = from + step; i >= 0 && i < buttons.length; i += step) if (!buttons[i].disabled) return i;
    return from;
  };

  useEffect(
    () =>
      onKeys((a) => {
        const v = video.current;
        if (!v) return true;
        nudge();
        if (finding) {
          if (a === "back") { setFinding(null); return true; }
          return !(a === "up" || a === "down" || a === "left" || a === "right" || a === "ok");
        }
        if (panel) {
          // The panel's own cursor moves and presses; Back closes it.
          if (a === "back") { setPanel(null); return true; }
          return !(a === "up" || a === "down" || a === "left" || a === "right" || a === "ok");
        }
        if (upNext) {
          const onPlay = (document.activeElement as HTMLElement | null)?.classList.contains("play-next");
          if (!(a === "ok" && onPlay) && !upNext.held) setUpNext({ ...upNext, held: true });
          if (a === "back") { setUpNext(null); return true; }
          // Its two buttons: the cursor moves between them and OK presses.
          return !(a === "left" || a === "right" || a === "ok");
        }
        if (a === "stop") { stop(); return true; }
        // Given up after the connection dropped: OK tries again from there.
        if (stuckAt.current !== null && (a === "ok" || a === "play" || a === "playPause")) {
          const at = stuckAt.current;
          stuckAt.current = null;
          drops.current = 0;
          setError("Reconnecting…");
          void app.reopen(at);
          return true;
        }
        // In the controls: along the row, up to the bar, OK presses; Back puts them away.
        if (cursor !== null) {
          if (a === "back") { setCursor(null); setControls(false); return true; }
          if (cursor === "bar") {
            if (a === "down") { setCursor(playAt); return true; }
            if (a === "ok") { togglePlay(); return true; }
            if (a !== "left" && a !== "right" && a !== "rewind" && a !== "forward") return true;
          } else {
            if (a === "left" || a === "right") { setCursor(along(cursor, a === "left" ? -1 : 1)); return true; }
            if (a === "up") { setCursor("bar"); return true; }
            if (a === "ok") { buttons[cursor]?.onPress(); return true; }
            if (a === "down") return true;
          }
        }
        switch (a) {
          case "back":
            stop();
            return true;
          case "ok":
            if (inIntro && intro) { introSkipped.current = key; v.currentTime = intro.endMs / 1000; return true; }
            togglePlay();
            return true;
          case "playPause":
            togglePlay();
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
            // The controls, the cursor on play.
            setCursor(playAt);
            return true;
        }
        return true;
      }),
    [playing.url, panel, upNext, inIntro, !!finding, cursor, paused, buttons.length],
  );

  // Up Next arriving: the cursor on Play next.
  useEffect(() => {
    if (upNext) focus(document.querySelector<HTMLElement>(".play-next"));
  }, [!!upNext]);

  // The cursor follows: onto the button it's on, back to it when a panel closes.
  const controlsRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (panel || finding || upNext) return;
    const root = controlsRef.current;
    if (!root) return;
    if (cursor === null) {
      if (root.contains(document.activeElement)) (document.activeElement as HTMLElement).blur();
      return;
    }
    focus(root.querySelector<HTMLElement>(cursor === "bar" ? ".scrub-focus" : `[data-at="${cursor}"]`));
  }, [cursor, panel, !!finding, !!upNext, buttons.length]);

  const choose = (audio: string | undefined, subtitle: string | undefined) => {
    setPanel(null);
    void app.chooseStreams(audio, subtitle, now(), (p) => playDirect(video.current ?? document.createElement("video"), p));
  };
  const find = async () => {
    setPanel(null);
    setFinding({ language: "", results: null, error: null, adding: null });
    const found = await app.findSubtitles();
    setFinding((f) => f && { ...f, language: found.language, results: found.results, error: found.error });
  };
  const add = async (s: PlexOnlineSubtitle) => {
    if (!finding) return;
    setFinding({ ...finding, adding: s.key, error: null });
    const problem = await app.addFoundSubtitle(s, finding.language, now(), (p) => playDirect(video.current ?? document.createElement("video"), p));
    if (problem) setFinding((f) => f && { ...f, adding: null, error: problem });
    else setFinding(null);
  };
  const setSleepFor = (minutes: number) => {
    setPanel(null);
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
  const words = textSub ? cueAt(cues, position) : null;
  const seconds = upNext?.endsAt ? Math.max(0, Math.ceil((upNext.endsAt - Date.now()) / 1000)) : null;
  const upNextArt = upNext ? app.image(upNext.next.serverBase, upNext.next.thumb ?? upNext.next.art, 1280, 720) : null;
  const upNextLogo = upNext && upNext.next.logo ? app.image(upNext.next.serverBase, upNext.next.logo, 600, 240) : null;
  const countdown = upNext?.endsAt && !upNext.held && prefs.upNextSeconds > 0
    ? { left: seconds ?? 0, fraction: Math.min(1, 1 - (upNext.endsAt - Date.now()) / (prefs.upNextSeconds * 1000)) }
    : null;
  return (
    <div class="player" data-layer>
      <video ref={video} class={"video" + (upNext ? " windowed" : "")} playsInline />
      {waiting && !error && !upNext ? <div class="player-wait"><Spinner /></div> : null}
      {error ? <div class="player-wait"><p class="note error">{error}</p></div> : null}
      {words && !upNext ? (
        <div class={"subtitle-line" + (prefs.subtitleBackground ? " boxed" : "")} style={{ fontSize: `${2.2 * prefs.subtitleScale}rem` }}>
          {words.split("\n").map((l, i) => <span key={i}>{l}</span>)}
        </div>
      ) : null}
      {/* Over the intro, OK skips it: low in the corner, or above the bar while the controls are up. */}
      {inIntro && !upNext && !panel ? <div class={"skip-prompt" + (controls || paused || cursor !== null ? " raised" : "")}>Skip Intro</div> : null}
      {upNext ? (
        <div class="post-play">
          {upNextArt ? <div class="post-art" style={{ backgroundImage: `url("${upNextArt}")` }} /> : null}
          <div class="post-shade" />
          <div class="post-window" />
          <div class="post-now">{`Credits  ·  ${title}`}</div>
          <div class="post-text">
            <div class="post-label">Up next</div>
            {upNextLogo ? <img class="title-logo" src={upNextLogo} alt={upNext.next.grandparentTitle ?? upNext.next.title} />
              : <div class="title-name">{upNext.next.grandparentTitle ?? upNext.next.title}</div>}
            {upNextLine(upNext.next) ? <div class="facts">{upNextLine(upNext.next)}</div> : null}
            {upNext.next.grandparentTitle ? <div class="post-title">{upNext.next.title}</div> : null}
            {upNext.next.summary ? <div class="summary">{upNext.next.summary}</div> : null}
            <div class="post-actions">
              <button class="pill primary play-next" data-focus data-autofocus onClick={() => playNext(upNext.next)}>
                {countdown ? <i class="countdown" style={{ width: `${countdown.fraction * 100}%` }} /> : null}
                <span>{countdown ? `Play next  ·  ${countdown.left}` : "Play next"}</span>
              </button>
              <button class="pill" data-focus onClick={() => setUpNext(null)}>Watch credits</button>
            </div>
          </div>
        </div>
      ) : null}
      <div ref={controlsRef} class={"player-bar" + ((controls || paused || cursor !== null) && !upNext ? " on" : "")}>
        <div class={"scrub-focus" + (cursor === "bar" ? " on" : "")} data-focus={cursor !== null ? "" : undefined} tabIndex={-1}>
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
        <div class="player-row">
          <div class="player-what">
            <div class="player-title">{title}</div>
            {sub ? <div class="facts">{sub}</div> : null}
          </div>
          <div class="transport">
            {buttons.map((b, i) => (b.id === "previous" || b.id === "play" || b.id === "next") ? (
              <ControlKey key={b.id} button={b} at={i} active={cursor !== null} />
            ) : null)}
          </div>
          <div class="player-options">
            {buttons.map((b, i) => (b.id === "previous" || b.id === "play" || b.id === "next") ? null : (
              <ControlKey key={b.id} button={b} at={i} active={cursor !== null} />
            ))}
          </div>
        </div>
      </div>
      {panel ? <div class="menu-shade player-shade" /> : null}
      {panel === "audio" ? (
        <TrackPanel title="Audio" empty="There's only one audio track." streams={playing.playback.audioStreams} off={false}
          onPick={(id) => choose(id, undefined)} />
      ) : null}
      {panel === "subtitles" ? (
        <SubtitlesPanel
          playing={playing}
          prefs={prefs}
          onPick={(id) => choose(undefined, id)}
          onFind={playing.base !== "iptv:" ? () => void find() : null}
          onSize={(step) => app.nudgeSubtitleScale(step)}
          onBackground={() => app.setSubtitleBackground(!prefs.subtitleBackground)}
        />
      ) : null}
      {panel === "chapters" ? (
        <ChaptersPanel chapters={playing.playback.chapters} positionMs={position}
          onPick={(ms) => { setPanel(null); if (video.current) video.current.currentTime = ms / 1000; }} />
      ) : null}
      {panel === "sleep" ? <SleepPanel sleep={sleep} episode={playing.item.type === "episode"} onPick={setSleepFor} /> : null}
      {panel === "info" ? <InfoPanel playing={playing} /> : null}
      {finding ? <FindSubtitles finding={finding} onAdd={(s) => void add(s)} onClose={() => setFinding(null)} /> : null}
    </div>
  );
}

type Panel = "subtitles" | "audio" | "chapters" | "sleep" | "info";
type ControlGlyph = "previous" | "play" | "pause" | "next" | "chapters" | "subtitles" | "audio" | "sleep" | "info";

interface ControlButton {
  id: string;
  label: string;
  glyph: ControlGlyph;
  primary?: boolean;
  /** Lit: a sleep timer is set. */
  on?: boolean;
  disabled?: boolean;
  onPress: () => void;
}

/** "Season 2  ·  Episode 6  ·  44 min", from whichever of those the item has. */
const upNextLine = (i: PlexItem) =>
  [i.parentIndex != null ? `Season ${i.parentIndex}` : null, i.index != null ? `Episode ${i.index}` : null,
    i.durationMs > 0 ? `${Math.max(1, Math.round(i.durationMs / 60_000))} min` : null].filter(Boolean).join("  ·  ");

/** One of the round buttons under the bar. Only takes the cursor while the controls have it. */
function ControlKey(props: { button: ControlButton; at: number; active: boolean }) {
  const b = props.button;
  return (
    <button
      class={"ctl" + (b.primary ? " primary" : "") + (b.on ? " on" : "")}
      data-focus={props.active && !b.disabled ? "" : undefined}
      data-at={props.at}
      aria-label={b.label}
      aria-disabled={b.disabled ? "true" : undefined}
      tabIndex={-1}
      onClick={() => { if (!b.disabled) b.onPress(); }}
    >
      <ControlIcon glyph={b.glyph} />
    </button>
  );
}

function ControlIcon(props: { glyph: ControlGlyph }) {
  const stroke = { fill: "none", stroke: "currentColor", "stroke-width": 8, "stroke-linecap": "round" as const, "stroke-linejoin": "round" as const };
  const svg = (children: preact.ComponentChildren) => <svg class="glyph" viewBox="0 0 100 100" aria-hidden="true">{children}</svg>;
  switch (props.glyph) {
    case "play": return svg(<path d="M34 22 L78 50 L34 78 Z" fill="currentColor" />);
    case "pause": return svg(<><rect x="28" y="24" width="14" height="52" rx="3" fill="currentColor" /><rect x="58" y="24" width="14" height="52" rx="3" fill="currentColor" /></>);
    case "previous": return svg(<><path d="M72 26 L36 50 L72 74 Z" fill="currentColor" /><rect x="24" y="26" width="9" height="48" rx="3" fill="currentColor" /></>);
    case "next": return svg(<><path d="M28 26 L64 50 L28 74 Z" fill="currentColor" /><rect x="67" y="26" width="9" height="48" rx="3" fill="currentColor" /></>);
    case "chapters": return svg(<><path d="M26 30 H74 M26 50 H74 M26 70 H56" {...stroke} /></>);
    case "subtitles": return svg(<><rect x="16" y="24" width="68" height="52" rx="10" {...stroke} /><path d="M30 58 H46 M54 58 H70 M30 44 H58" {...stroke} /></>);
    case "audio": return svg(<><path d="M22 40 H36 L54 24 V76 L36 60 H22 Z" fill="currentColor" /><path d="M66 36 Q76 50 66 64 M74 26 Q92 50 74 74" {...stroke} /></>);
    case "sleep": return svg(<path d="M64 20 A32 32 0 1 0 80 62 A26 26 0 0 1 64 20 Z" fill="currentColor" />);
    case "info": return svg(<><circle cx="50" cy="50" r="32" {...stroke} /><path d="M50 46 V68" {...stroke} /><circle cx="50" cy="33" r="5" fill="currentColor" /></>);
  }
}

/** A panel down the right, as every menu here: its heading, then its choices. */
function SidePanel(props: { title: string; children: preact.ComponentChildren }) {
  const panel = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const first = panel.current?.querySelector<HTMLElement>("[data-autofocus]") ?? panel.current?.querySelector<HTMLElement>("[data-focus]");
    focus(first);
  }, []);
  return (
    <div class="options player-panel" data-layer ref={panel}>
      <div class="panel-title">{props.title}</div>
      {props.children}
    </div>
  );
}

function Choice(props: { label: string; on?: boolean; detail?: string | null; value?: string | null; icon?: string; autofocus?: boolean; onPress: () => void }) {
  return (
    <button class={"option" + (props.on ? " on" : "")} data-focus data-autofocus={props.autofocus ? "" : undefined} onClick={props.onPress}>
      <span class="tick">{props.on ? "✓" : props.icon ?? ""}</span>
      <span class="option-text">
        {props.label}
        {props.detail ? <span class="option-note">{props.detail}</span> : null}
      </span>
      {props.value ? <span class="facts">{props.value}</span> : null}
    </button>
  );
}

function TrackPanel(props: { title: string; empty: string; streams: PlexStream[]; off: boolean; onPick: (id: string) => void }) {
  const chosen = props.streams.find((s) => s.selected) ?? props.streams[0];
  return (
    <SidePanel title={props.title}>
      {props.streams.length <= 1 ? <p class="note panel-note">{props.empty}</p> : null}
      {props.streams.map((s) => (
        <Choice key={s.id} label={s.label} on={s === chosen} autofocus={s === chosen} onPress={() => props.onPick(s.id)} />
      ))}
    </SidePanel>
  );
}

function SubtitlesPanel(props: {
  playing: Playing;
  prefs: Prefs;
  onPick: (id: string) => void;
  onFind: (() => void) | null;
  onSize: (step: 1 | -1) => void;
  onBackground: () => void;
}) {
  const streams = props.playing.playback.subtitleStreams;
  const on = streams.find((s) => s.selected) ?? null;
  return (
    <SidePanel title="Subtitles">
      {!streams.length ? <p class="note panel-note">No subtitles are available for this video.</p> : null}
      {streams.length ? <Choice label="Off" on={!on} autofocus={!on} onPress={() => props.onPick("0")} /> : null}
      {streams.map((s) => <Choice key={s.id} label={s.label} on={s === on} autofocus={s === on} onPress={() => props.onPick(s.id)} />)}
      {props.onFind ? <Choice label="Find subtitles online" icon="⌕" onPress={props.onFind} /> : null}
      {/* The size it is now heads the two that change it, rather than sitting on one of them. */}
      <h3>Size  ·  {Math.round(props.prefs.subtitleScale * 100)}%</h3>
      <Choice label="Increase size" icon="+" onPress={() => props.onSize(1)} />
      <Choice label="Decrease size" icon="−" onPress={() => props.onSize(-1)} />
      <h3>Appearance</h3>
      <Choice label="Background" value={props.prefs.subtitleBackground ? "On" : "Off"} onPress={props.onBackground} />
    </SidePanel>
  );
}

/** The chapters, each with its picture where Plex has one; opens on the one playing. */
function ChaptersPanel(props: { chapters: PlexChapter[]; positionMs: number; onPick: (ms: number) => void }) {
  const now = props.chapters.reduce((at, c, i) => (c.startMs <= props.positionMs ? i : at), 0);
  return (
    <SidePanel title="Chapters">
      {props.chapters.map((c, i) => (
        <button key={c.startMs} class={"option chapter" + (i === now ? " on" : "")} data-focus data-autofocus={i === now ? "" : undefined} onClick={() => props.onPick(c.startMs)}>
          {c.thumbUrl ? <img class="chapter-thumb" src={c.thumbUrl} alt="" /> : <span class="chapter-thumb" />}
          <span class="option-text">
            {c.title}
            <span class="option-note">{clock(c.startMs)}</span>
          </span>
        </button>
      ))}
    </SidePanel>
  );
}

function SleepPanel(props: { sleep: Sleep; episode: boolean; onPick: (minutes: number) => void }) {
  const label = (m: number) => (m === 0 ? "Off" : m === END_OF_EPISODE ? "End of this episode" : `${m} minutes`);
  const on = (m: number) => (m === 0 ? !props.sleep : m === END_OF_EPISODE ? !!props.sleep?.endOfEpisode : false);
  const left = props.sleep?.atMs ? Math.max(1, Math.ceil((props.sleep.atMs - Date.now()) / 60_000)) : null;
  return (
    <SidePanel title="Sleep timer">
      {left ? <p class="note panel-note">Stops in {left} min.</p> : null}
      {SLEEP_CHOICES.filter((m) => m !== END_OF_EPISODE || props.episode).map((m) => (
        <Choice key={m} label={label(m)} on={on(m)} autofocus={m === 0} onPress={() => props.onPick(m)} />
      ))}
    </SidePanel>
  );
}

/** What's playing and how: the file as it is, or Plex converting it. */
function InfoPanel(props: { playing: Playing }) {
  const p = props.playing.playback;
  const audio = p.audioStreams.find((s) => s.selected) ?? p.audioStreams[0];
  const rows: Array<[string, string | null]> = [
    ["Playing", props.playing.direct ? "The original file" : "Converted by Plex"],
    ["Video", p.videoCodec ? p.videoCodec.toUpperCase() : null],
    ["Audio", audio?.label ?? (p.audioCodec ? `${p.audioCodec.toUpperCase()}${p.audioChannels ? ` · ${p.audioChannels} channels` : ""}` : null)],
    ["File", p.container ? p.container.toUpperCase() : null],
    ["Subtitles", props.playing.textSubtitle ? "Drawn by Reely" : p.subtitleStreams.some((s) => s.selected) ? "Burned in by Plex" : "Off"],
  ];
  return (
    <SidePanel title="Playback info">
      <div class="info-rows">
        {rows.filter(([, v]) => v).map(([k, v]) => (
          <div key={k} class="info-row"><span class="facts">{k}</span><span>{v}</span></div>
        ))}
      </div>
    </SidePanel>
  );
}

/** "English" for "en", where the TV's browser can say so (Chromium 81 on); else the code. */
function languageName(code: string): string {
  const names = (Intl as unknown as { DisplayNames?: new (l: string[], o: { type: string }) => { of(c: string): string | undefined } }).DisplayNames;
  if (!code) return "";
  try {
    return names ? new names([navigator.language || "en"], { type: "language" }).of(code) ?? code : code.toUpperCase();
  } catch {
    return code.toUpperCase();
  }
}

/** Subtitles found online by the Plex server, in the TV's language: one press adds them. */
function FindSubtitles(props: {
  finding: { language: string; results: PlexOnlineSubtitle[] | null; error: string | null; adding: string | null };
  onAdd: (s: PlexOnlineSubtitle) => void;
  onClose: () => void;
}) {
  const f = props.finding;
  const panel = useRef<HTMLDivElement>(null);
  useEffect(() => {
    focus(panel.current?.querySelector<HTMLElement>("[data-focus]"));
  }, [f.results != null]);
  const language = languageName(f.language);
  return (
    <div class="options" data-layer ref={panel}>
      <section>
        <h3>Find subtitles</h3>
        <p class="note">
          {f.error ?? (f.results == null ? `Looking for subtitles…` : f.adding ? "Adding them…" : f.results.length ? `${language}, found by your Plex server.` : `No ${language} subtitles were found for this.`)}
        </p>
        {(f.results ?? []).map((s) => (
          <button key={s.key} class="option" data-focus onClick={() => props.onAdd(s)}>
            <span class="tick">{f.adding === s.key ? "…" : ""}</span>
            <span>
              {s.title}
              <span class="facts" style={{ display: "block" }}>
                {[s.provider, s.hearingImpaired ? "For the hard of hearing" : null, s.forced ? "Forced" : null].filter(Boolean).join("  ·  ")}
              </span>
            </span>
          </button>
        ))}
        <button class="option" data-focus onClick={props.onClose}><span class="tick" />Close</button>
      </section>
    </div>
  );
}

/** As the store asks it: the file as it is when the TV says it can play it. */
export function playDirect(v: HTMLVideoElement, p: Parameters<typeof plan>[0]) {
  return plan(p, browserCanPlay(v)).direct;
}
