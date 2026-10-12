import React from 'react';
import { Image, View } from 'react-native';
import type { PlexChapter, PlexOnlineSubtitle, PlexStream } from '../../api/plex';
import type { Playing, Prefs } from '../../app/store';
import { describeCodec } from '../../player/formats';
import { MinusGlyph, MoonGlyph, PlusGlyph, SearchGlyph } from '../glyphs';
import { MenuHeading, MenuItem, MenuLayer, MenuSection } from '../menus';
import { Text } from '../text';
import { color, dp, type } from '../theme';
import { clock, clockTime, currentChapter } from './controls';

/*
 * The player's panels (PlayerScreen.kt's TrackPanel, ChapterPanel, SleepPanel,
 * StatsPanel and SubtitleSearchPanel): a menu from the right over the picture, opening
 * on what's chosen now. Back closes it, and the cursor goes back to its button.
 */

const Note = (props: { text: string }) => (
  <Text style={[type.meta, { paddingLeft: dp(4), paddingBottom: dp(8) }]}>{props.text}</Text>
);

export function SubtitlesPanel(props: {
  streams: PlexStream[]; prefs: Prefs; onClose: () => void; none?: string;
  onPick: (id: string) => void; onFind: (() => void) | null; onSize: (step: 1 | -1) => void; onBackground: () => void;
}) {
  const on = props.streams.find((s) => s.selected) ?? null;
  return (
    <MenuLayer onClose={props.onClose}>
      <MenuHeading title="Subtitles" />
      {!props.streams.length ? <Note text={props.none ?? 'No subtitles are available for this video.'} /> : null}
      {props.streams.length ? <MenuItem label="Off" checked={!on} preferred={!on} onPress={() => props.onPick('0')} /> : null}
      {props.streams.map((s) => <MenuItem key={s.id} label={s.label} checked={s === on} preferred={s === on} onPress={() => props.onPick(s.id)} />)}
      {props.onFind ? <MenuItem label="Find subtitles online" icon={(c) => <SearchGlyph color={c} size={20} />} onPress={props.onFind} preferred={!props.streams.length} /> : null}
      {/* The size it is now heads the two that change it, rather than sitting on one of them. */}
      <MenuSection label={`Size  ·  ${Math.round(props.prefs.subtitleScale * 100)}%`} />
      <MenuItem label="Increase size" icon={(c) => <PlusGlyph color={c} size={18} />} onPress={() => props.onSize(1)} />
      <MenuItem label="Decrease size" icon={(c) => <MinusGlyph color={c} size={18} />} onPress={() => props.onSize(-1)} />
      <MenuSection label="Appearance" />
      <MenuItem label="Background" value={props.prefs.subtitleBackground ? 'On' : 'Off'} onPress={props.onBackground} />
    </MenuLayer>
  );
}

export function AudioPanel(props: { streams: PlexStream[]; onPick: (id: string) => void; onClose: () => void }) {
  const chosen = props.streams.find((s) => s.selected) ?? props.streams[0];
  return (
    <MenuLayer onClose={props.onClose}>
      <MenuHeading title="Audio" />
      {props.streams.length <= 1 ? <Note text="There's only one audio track." /> : null}
      {props.streams.map((s) => <MenuItem key={s.id} label={s.label} checked={s === chosen} preferred={s === chosen} onPress={() => props.onPick(s.id)} />)}
    </MenuLayer>
  );
}

/** The film's chapters, each with the server's picture of it; opens on the one playing. */
export function ChaptersPanel(props: { chapters: PlexChapter[]; positionMs: number; onPick: (ms: number) => void; onClose: () => void }) {
  const now = currentChapter(props.chapters, props.positionMs);
  return (
    <MenuLayer onClose={props.onClose} width={dp(500)}>
      <MenuHeading title="Chapters" />
      {props.chapters.map((c, i) => (
        <MenuItem key={`${c.startMs}:${i}`} label={c.title} detail={clock(c.startMs)} checked={i === now} preferred={i === now}
          icon={c.thumbUrl ? () => <Image source={{ uri: c.thumbUrl! }} resizeMode="cover" style={{ width: dp(64), height: dp(36), borderRadius: dp(6) }} /> : undefined}
          onPress={() => props.onPick(c.startMs)} />
      ))}
    </MenuLayer>
  );
}

export type Sleep = { atMs: number | null; endOfEpisode: boolean } | null;
const SLEEP_MINUTES = [30, 60, 90];

/**
 * The sleep timer: off, a time from now, or the end of what's playing. The choice made is
 * marked, with when it will stop.
 */
export function SleepPanel(props: { sleep: Sleep; onPick: (minutes: number | null) => void; onClose: () => void; live?: boolean }) {
  const chosen = !props.sleep ? null : props.sleep.endOfEpisode ? 0 : -1;
  // A channel has no end to wait for.
  const options: Array<[number | null, string]> = [[null, 'Off'], ...SLEEP_MINUTES.map((m): [number, string] => [m, `In ${m} minutes`]),
    ...(props.live ? [] : [[0, 'At the end of this episode'] as [number, string]])];
  return (
    <MenuLayer onClose={props.onClose}>
      <MenuHeading title="Sleep timer" subtitle={props.sleep?.atMs ? `Stops at ${clockTime(props.sleep.atMs)}.` : null} />
      {options.map(([minutes, label], i) => (
        <MenuItem key={label} label={label} checked={minutes === chosen} preferred={i === 0}
          icon={minutes == null ? undefined : (c) => <MoonGlyph color={c} size={20} />} onPress={() => props.onPick(minutes)} />
      ))}
    </MenuLayer>
  );
}

/** What's playing and how, said the way a streaming app says it. Nothing to press. */
export function InfoPanel(props: { playing: Playing; onClose: () => void }) {
  const p = props.playing.playback;
  const audio = p.audioStreams.find((s) => s.selected) ?? p.audioStreams[0];
  const rows: Array<[string, string | null]> = [
    ['Quality', props.playing.direct ? 'Original' : 'Adjusted for this TV'],
    // Why Plex converts it, when the file itself is what this TV can't play.
    ['Converted because', props.playing.direct ? null : props.playing.reason ?? null],
    ['Picture', p.videoCodec ? describeCodec(p.videoCodec) : null],
    ['Sound', audio?.label ?? (p.audioCodec ? `${p.audioCodec.toUpperCase()}${p.audioChannels ? `  ·  ${channelLayout(p.audioChannels)}` : ''}` : null)],
    ['File', p.container ? p.container.toUpperCase() : null],
    ['Subtitles', props.playing.textSubtitle ? 'On' : p.subtitleStreams.some((s) => s.selected) ? 'On, drawn in by Plex' : 'Off'],
  ];
  return (
    <MenuLayer onClose={props.onClose}>
      <MenuHeading title="Playback info" />
      {/* The panel holds the cursor itself: one row it can be on, the rest read-only. */}
      <View style={{ gap: dp(14), paddingLeft: dp(4) }}>
        {rows.filter(([, v]) => v).map(([label, value]) => (
          <View key={label} style={{ flexDirection: 'row', justifyContent: 'space-between' }}>
            <Text style={type.meta}>{label}</Text>
            <Text style={[type.meta, { color: color.chalk }]}>{value}</Text>
          </View>
        ))}
      </View>
      <View style={{ height: dp(16) }} />
      <MenuItem label="Close" preferred onPress={props.onClose} />
    </MenuLayer>
  );
}

const channelLayout = (n: number) => (n === 1 ? 'Mono' : n === 2 ? 'Stereo' : n === 6 ? '5.1' : n === 8 ? '7.1' : `${n} channels`);

export interface Finding { language: string; results: PlexOnlineSubtitle[] | null; error: string | null; adding: string | null }

const LANGUAGES: Record<string, string> = { en: 'English', es: 'Spanish', fr: 'French', de: 'German', it: 'Italian', pt: 'Portuguese', nl: 'Dutch', ja: 'Japanese' };

/** Subtitles the server found online, in the TV's language: one press adds them. */
export function FindSubtitlesPanel(props: { finding: Finding; onAdd: (s: PlexOnlineSubtitle) => void; onClose: () => void }) {
  const f = props.finding;
  const language = LANGUAGES[f.language] ?? f.language.toUpperCase();
  const results = f.results ?? [];
  return (
    <MenuLayer onClose={props.onClose} width={dp(500)}>
      <MenuHeading title="Find subtitles" />
      <Note text={f.error ?? (f.results == null ? `Looking for ${language} subtitles…` : f.adding ? 'Adding them…'
        : results.length ? `${language}, found by your Plex server.` : `No ${language} subtitles were found for this.`)} />
      {results.map((s, i) => (
        <MenuItem key={s.key} label={s.title} checked={f.adding === s.key} preferred={i === 0}
          detail={[s.provider, s.hearingImpaired ? 'For the hard of hearing' : null, s.forced ? 'Forced' : null].filter(Boolean).join('  ·  ') || null}
          onPress={() => { if (!f.adding) props.onAdd(s); }} />
      ))}
      {/* Something for the cursor to be on while it looks, or when it found nothing. */}
      {!results.length ? <MenuItem label="Close" preferred onPress={props.onClose} /> : null}
    </MenuLayer>
  );
}
