import React, { useEffect, useRef, useState } from 'react';
import { Image, ScrollView, View } from 'react-native';
import { TVFocusGuideView, useTVEventHandler } from '@amazon-devices/react-native-kepler';
import type { App, AppState } from '../../app/store';
import type { Programme, XtreamCategory, XtreamChannel } from '../../api/xtream';
import { isOnAt } from '../../api/xtream';
import { channelLabel, channelTint } from '../cards';
import { ActionButton, Chip, EmptyNote, ErrorNote, SectionHeading, Spinner, TextField } from '../controls';
import { CardLift, CardRing, FocusRow, Press, TintContext, focusWhenReady, useAccent } from '../focus';
import { ClockGlyph, HeartGlyph, PlayGlyph } from '../glyphs';
import { MenuHeading, MenuItem, MenuLayer, useBack } from '../menus';
import { glass } from '../surfaces';
import { Text } from '../text';
import { color, dp, type } from '../theme';
import { VideoSurface } from '../../player/engine';
import { liveEngine, playLive, stopLive } from '../../player/live';

/*
 * Live TV (LiveScreen.kt, GuideScreen.kt, GuideGrid.kt): signing in to a provider, its
 * categories as tiles, and a category's guide, with the highlighted channel playing in a
 * window beside it.
 */

export function LiveSignIn(props: { app: App; state: AppState }) {
  const { app, state } = props;
  const live = state.live;
  const [playlist, setPlaylist] = useState(false);
  const [host, setHost] = useState('');
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [playlistUrl, setPlaylistUrl] = useState('');
  const [guideUrl, setGuideUrl] = useState('');
  return (
    <View style={{ flex: 1, flexDirection: 'row', gap: dp(48), paddingHorizontal: dp(48), paddingVertical: dp(27) }}>
      <View style={{ flex: 1, paddingTop: dp(12), gap: dp(14) }}>
        <SectionHeading text="Live TV" />
        <Text style={[type.display, { fontSize: dp(30), lineHeight: dp(36) }]}>Sign in to your live TV provider</Text>
        <Text style={[type.body, { color: color.muted }]}>
          {playlist
            ? 'Enter the playlist address from your provider. Some providers give a TV guide address as well. Both are stored only on this device.'
            : "Enter the details from your provider. They're stored only on this device."}
        </Text>
        {live.error ? <ErrorNote message={live.error} /> : null}
      </View>
      <View style={[glass(), { width: dp(440), padding: dp(20), gap: dp(14), alignSelf: 'flex-start' }]}>
        <View style={{ flexDirection: 'row', gap: dp(8) }}>
          <Chip label="Xtream Codes login" selected={!playlist} first onPress={() => setPlaylist(false)} />
          <Chip label="M3U playlist" selected={playlist} onPress={() => setPlaylist(true)} />
        </View>
        {playlist ? (
          <>
            <TextField label="Playlist address" value={playlistUrl} onChange={setPlaylistUrl} placeholder="http://provider.example.com/playlist.m3u" keyboard="url" />
            <TextField label="TV guide address (optional)" value={guideUrl} onChange={setGuideUrl} placeholder="http://provider.example.com/guide.xml" keyboard="url" />
          </>
        ) : (
          <>
            <TextField label="Server" value={host} onChange={setHost} placeholder="provider.example.com:8080" keyboard="url" />
            <TextField label="Username" value={username} onChange={setUsername} />
            <TextField label="Password" value={password} onChange={setPassword} password />
          </>
        )}
        <ActionButton label={live.busy ? 'Connecting…' : 'Connect'} emphasised style={{ marginTop: dp(4) }}
          onPress={() => void (playlist ? app.signInPlaylist(playlistUrl, guideUrl) : app.signInXtream(host, username, password))} />
      </View>
    </View>
  );
}

const TILE_HEIGHT = dp(92);

/** A category, in its own colour. Focus is a poster's: a white ring, a lift, a glow in its colour. */
function CategoryCard(props: { category: XtreamCategory; onPress: () => void; first: boolean }) {
  const [focused, setFocused] = useState(false);
  const tint = channelTint(props.category.id);
  return (
    // Three across, as the Fire TV's grid of 240 dp tiles fits them.
    <Press onPress={props.onPress} first={props.first} focusKey={`cat:${props.category.id}`} onFocusChange={setFocused} style={{ width: '33.333%', padding: dp(9) }}>
      {() => (
        <TintContext.Provider value={tint}>
        <CardLift focused={focused}>
          <CardRing focused={focused} corner={dp(14)} style={{ height: TILE_HEIGHT }}>
            <View style={{ flex: 1, backgroundColor: color.surfaceRaised }}>
              <View style={{ position: 'absolute', left: 0, right: 0, top: 0, bottom: 0, backgroundColor: tint, opacity: focused ? 0.62 : 0.46 }} />
              <View style={{ flex: 1, justifyContent: 'center', paddingHorizontal: dp(20) }}>
                <Text numberOfLines={2} style={{ color: color.chalk, fontSize: dp(22), lineHeight: dp(27), fontWeight: '600' }}>{props.category.name}</Text>
              </View>
            </View>
          </CardRing>
        </CardLift>
        </TintContext.Provider>
      )}
    </Press>
  );
}

/** Picking a category comes first; its guide is what it opens into. */
export function LiveCategories(props: { app: App; state: AppState }) {
  const { app, state } = props;
  const live = state.live;
  const shown = app.shownCategories();
  return (
    <ScrollView contentContainerStyle={{ paddingHorizontal: dp(48), paddingTop: dp(20), paddingBottom: dp(27) }}>
      <View style={{ paddingBottom: dp(8), paddingHorizontal: dp(9) }}>
        <Text style={type.display}>Live TV</Text>
        <Text style={[type.meta, { marginTop: dp(4) }]}>
          {`${live.categories.length} categories` + (live.favorites.length ? `  ·  ${live.favorites.length} favorites` : '')}
        </Text>
        {live.error ? <ErrorNote message={live.error} style={{ marginTop: dp(12) }} /> : null}
        {!live.categories.length && !live.busy ? <EmptyNote text="Your provider didn't list any channels." style={{ marginTop: dp(12) }} /> : null}
      </View>
      {live.busy && !live.categories.length ? <View style={{ padding: dp(40), alignItems: 'center' }}><Spinner /></View> : null}
      <FocusRow>
        <TVFocusGuideView autoFocus style={{ flexDirection: 'row', flexWrap: 'wrap' }}>
          {shown.map((c, i) => <CategoryCard key={c.id} category={c} first={i === 0} onPress={() => void app.openCategory(c)} />)}
        </TVFocusGuideView>
      </FocusRow>
    </ScrollView>
  );
}

// The guide's scale (GuideGrid.kt): a minute is 5 dp wide.
const MINUTE = dp(5);
export const CHANNEL_COLUMN = dp(132);
export const ROW_HEIGHT = dp(78);
const CARD_GAP = dp(5);
/** How much of a block stays for its label before the label is pushed off its end. */
const LABEL_MIN_WIDTH = dp(130);
const WINDOW = 6 * 3600;

const widthFor = (seconds: number) => (Math.max(0, seconds) / 60) * MINUTE;

export function clock(epochSeconds: number): string {
  const d = new Date(epochSeconds * 1000);
  const h = d.getHours();
  return `${h % 12 || 12}:${String(d.getMinutes()).padStart(2, '0')} ${h < 12 ? 'AM' : 'PM'}`;
}

export const timeRange = (p: Programme) => `${clock(p.start)} – ${clock(p.stop)}`;

/** One channel and what it's showing across the window. */
function GuideRow(props: {
  channel: XtreamChannel; listing: Programme[]; windowStart: number; windowEnd: number; now: number; focusTime: number;
  current: boolean; catchUpFrom: number | null; reminded: Set<number>; favorite: boolean;
  /** How far along in time the grid has slid, in width. */
  offset: number;
}) {
  const accent = useAccent();
  const { channel, listing, windowStart, windowEnd, now } = props;
  const blocks: React.ReactNode[] = [];
  let cursor = windowStart;
  // Each block's own left edge in the row, so a block that began before the part on show
  // can keep its label at the edge of what's on show.
  let edge = 0;
  for (const p of listing) {
    const start = Math.max(p.start, windowStart);
    const stop = Math.min(p.stop, windowEnd);
    if (stop <= start) continue;
    if (start > cursor) {
      blocks.push(<View key={`gap${p.start}`} style={{ width: widthFor(start - cursor) - CARD_GAP }} />);
      edge += widthFor(start - cursor);
    }
    const blockWidth = widthFor(stop - start) - CARD_GAP;
    const labelShift = Math.min(Math.max(0, props.offset - edge), Math.max(0, blockWidth - LABEL_MIN_WIDTH));
    edge += widthFor(stop - start);
    const selected = props.current && isOnAt(p, props.focusTime);
    const past = p.stop <= now;
    const replay = props.catchUpFrom != null && past && p.start >= props.catchUpFrom;
    const reminder = props.reminded.has(p.start);
    const faded = past && !selected && !replay;
    blocks.push(
      <View key={p.start} style={{
        width: blockWidth, height: '100%', borderRadius: dp(14), justifyContent: 'center', paddingRight: dp(14), paddingLeft: dp(14) + labelShift,
        backgroundColor: selected ? `${accent.color}4D` : past ? 'rgba(242, 244, 247, 0.05)' : 'rgba(242, 244, 247, 0.1)',
        borderWidth: selected ? dp(2) : 0, borderColor: accent.color,
      }}>
        <Text numberOfLines={1} style={{ color: faded ? 'rgba(242, 244, 247, 0.55)' : color.chalk, fontSize: dp(15), lineHeight: dp(19), fontWeight: faded ? '400' : '500' }}>{p.title}</Text>
        <Text numberOfLines={1} style={{ color: 'rgba(242, 244, 247, 0.45)', fontSize: dp(14), lineHeight: dp(18) }}>
          {replay ? `Watch again  ·  ${timeRange(p)}` : reminder ? `Reminder set  ·  ${timeRange(p)}` : timeRange(p)}
        </Text>
      </View>,
    );
    cursor = stop;
  }
  return (
    <View style={{ flexDirection: 'row', height: ROW_HEIGHT }}>
      <View style={{
        width: CHANNEL_COLUMN - dp(6), height: '100%', borderRadius: dp(14), overflow: 'hidden', alignItems: 'center', justifyContent: 'center',
        borderWidth: props.current ? dp(2) : 0, borderColor: color.chalk,
      }}>
        <View style={{ position: 'absolute', left: 0, right: 0, top: 0, bottom: 0, backgroundColor: channelTint(String(channel.streamId)), opacity: props.current ? 0.95 : 0.7 }} />
        {channel.icon ? <Image source={{ uri: channel.icon }} resizeMode="contain" style={{ width: dp(54), height: dp(54), borderRadius: dp(8) }} />
          : <Text numberOfLines={2} style={{ color: color.chalk, fontSize: dp(13), lineHeight: dp(16), fontWeight: '700', textAlign: 'center', paddingHorizontal: dp(8) }}>{channelLabel(channel.name)}</Text>}
        {props.favorite ? <View style={{ position: 'absolute', top: dp(6), right: dp(7) }}><HeartGlyph color={accent.color} size={14} /></View> : null}
      </View>
      <View style={{ width: dp(6) }} />
      <View style={{ flex: 1, overflow: 'hidden' }}>
      <View style={{ flexDirection: 'row', gap: CARD_GAP, height: '100%', transform: [{ translateX: -props.offset }] }}>
        {blocks.length ? blocks : (
          <View style={{ width: widthFor(windowEnd - windowStart), height: '100%', borderRadius: dp(14), justifyContent: 'center', paddingHorizontal: dp(14), backgroundColor: 'rgba(242, 244, 247, 0.05)' }}>
            <Text style={{ color: 'rgba(242, 244, 247, 0.55)', fontSize: dp(15) }}>No guide data</Text>
          </View>
        )}
      </View>
      </View>
    </View>
  );
}

/** How long the cursor rests on a channel before its preview starts: walking past doesn't open each. */
const PREVIEW_DELAY_MS = 900;

/**
 * A category's guide. The grid is one place for the cursor: up and down change channel,
 * left and right step programme by programme, OK plays, a held OK the channel's menu.
 */
export function Guide(props: { app: App; state: AppState }) {
  const { app, state } = props;
  const live = state.live;
  const channels = live.channels;
  const [now, setNow] = useState(() => Math.floor(Date.now() / 1000));
  useEffect(() => {
    const t = setInterval(() => setNow(Math.floor(Date.now() / 1000)), 30_000);
    return () => clearInterval(t);
  }, []);
  const [index, setIndex] = useState(0);
  const [focusTime, setFocusTime] = useState(() => Math.floor(Date.now() / 1000));
  const [windowStart, setWindowStart] = useState(() => Math.floor(Date.now() / 1000 / 1800) * 1800 - 1800);
  const windowEnd = windowStart + WINDOW;
  const [gridFocused, setGridFocused] = useState(false);
  const [menu, setMenu] = useState(false);
  const rows = useRef<ScrollView>(null);
  const grid = useRef<View>(null);
  const buttons = useRef<View>(null);

  // The listings for these channels, the guide's own where it has them.
  useEffect(() => {
    if (channels.length) void app.loadTable(channels);
  }, [channels]);
  const listingOf = (c: XtreamChannel) => live.table[c.streamId] ?? live.guide[c.streamId] ?? [];
  const channel = channels[Math.min(index, channels.length - 1)] ?? null;
  const listing = channel ? listingOf(channel) : [];
  const selected = listing.find((p) => isOnAt(p, focusTime)) ?? null;

  // The time window follows the cursor, a little behind it; the rows keep its channel in view.
  const offset = Math.max(0, widthFor(focusTime - windowStart) - dp(150));
  useEffect(() => {
    rows.current?.scrollTo({ y: Math.max(0, index * (ROW_HEIGHT + dp(6)) - (ROW_HEIGHT + dp(6))), animated: true });
  }, [index]);

  // The highlighted channel plays beside the grid, once the cursor rests on it, in the
  // player the channel goes on in full screen.
  const watching = live.watching != null;
  useEffect(() => {
    if (!channel || !state.prefs.guidePreview || watching) return;
    const url = app.channelUrl(channel);
    if (!url) return;
    const t = setTimeout(() => playLive(url), PREVIEW_DELAY_MS);
    return () => clearTimeout(t);
  }, [channel?.streamId, watching]);
  // Leaving the guide stops the preview.
  useEffect(() => () => stopLive(), []);

  const moveTime = (delta: number) => {
    const at = listing.findIndex((p) => isOnAt(p, focusTime));
    const target = at >= 0 ? listing[at + delta] : undefined;
    const next = Math.max(windowStart, target?.start ?? focusTime + delta * 1800);
    setFocusTime(next);
    if (next > windowEnd - 3 * 3600 || next < windowStart + 3600) setWindowStart(Math.floor(next / 1800) * 1800 - 3600);
  };
  const jumpToNow = () => {
    const t = Math.floor(Date.now() / 1000);
    setFocusTime(t);
    setWindowStart(Math.floor(t / 1800) * 1800 - 3600);
  };
  const play = () => {
    if (!channel) return;
    const past = selected && selected.stop <= Math.floor(Date.now() / 1000) && app.canCatchUp(channel, selected) ? selected : null;
    app.watchIn(live.category!, channels, index, past);
  };

  useTVEventHandler((event) => {
    if (!gridFocused || menu || event.eventKeyAction !== 0) return;
    switch (event.eventType) {
      case 'up':
        if (index === 0) focusWhenReady(buttons);
        else setIndex(index - 1);
        break;
      case 'down':
        if (index < channels.length - 1) setIndex(index + 1);
        break;
      case 'left': moveTime(-1); break;
      case 'right': moveTime(1); break;
    }
  });

  useBack(() => {
    if (menu) {
      setMenu(false);
      return true;
    }
    app.closeCategory();
    return true;
  });

  const reminders = live.reminders;
  const upcoming = selected && selected.start > now ? selected : null;
  const reminded = !!(upcoming && channel && reminders.some((r) => r.streamId === channel.streamId && r.start === upcoming.start));
  const statusLine = selected && channel ? `${channel.name}  ·  ${timeRange(selected)}`
    : live.guideStatus.kind === 'updating' ? 'Updating the TV guide…'
    : live.guideStatus.kind === 'failed' ? live.guideStatus.message
    : 'TV guide';
  const nowX = widthFor(now - windowStart);

  return (
    <View style={{ flex: 1, paddingHorizontal: dp(28), paddingVertical: dp(12), gap: dp(10) }}>
      <View style={{ flexDirection: 'row', gap: dp(18) }}>
        <View style={{ flex: 1 }}>
          <Text numberOfLines={1} style={{ color: color.faint, fontSize: dp(14), lineHeight: dp(18), letterSpacing: dp(1.4), textTransform: 'uppercase' }}>{live.category?.name ?? ''}</Text>
          <Text numberOfLines={1} style={{ color: color.chalk, fontSize: dp(24), lineHeight: dp(30), fontWeight: '600' }}>{selected?.title ?? channel?.name ?? 'Guide'}</Text>
          <Text numberOfLines={1} style={{ color: color.muted, fontSize: dp(14), lineHeight: dp(18) }}>{statusLine}</Text>
          {selected?.description ? <Text numberOfLines={2} style={{ color: color.muted, fontSize: dp(14), lineHeight: dp(19), marginTop: dp(4) }}>{selected.description}</Text> : null}
          <TVFocusGuideView autoFocus style={{ flexDirection: 'row', gap: dp(10), marginTop: dp(10) }}>
            <ActionButton pressRef={buttons} label="Categories" onPress={() => app.closeCategory()} />
            <ActionButton label="Now" onPress={jumpToNow} />
            <ActionButton label={live.guideStatus.kind === 'updating' ? 'Loading…' : 'Refresh'} onPress={() => void app.refreshGuide()} />
          </TVFocusGuideView>
        </View>
        {state.prefs.guidePreview ? (
          <View style={{ width: dp(252), aspectRatio: 16 / 9, borderRadius: dp(10), overflow: 'hidden', backgroundColor: '#000000', borderWidth: dp(1), borderColor: color.glassEdge }}>
            {!watching ? <VideoSurface engine={liveEngine()} style={{ flex: 1 }} /> : null}
          </View>
        ) : null}
      </View>

      {!channels.length ? (live.busy ? <View style={{ padding: dp(40) }}><Spinner /></View> : <EmptyNote text="No channels in this category." />) : (
        // The grid keeps the cursor: the arrows move through it, and only up from the top
        // channel leaves it, for the buttons.
        <TVFocusGuideView trapFocusUp={index > 0} trapFocusDown trapFocusLeft trapFocusRight style={{ flex: 1 }}>
        <Press ref={grid} first onPress={play} onHold={() => { if (channel) setMenu(true); }}
          onFocusChange={setGridFocused} style={{ flex: 1 }}>
          {() => (
            <View style={{ flex: 1, overflow: 'hidden' }}>
              {/* Half-hour ticks along the top, in step with the rows. */}
              <View style={{ flexDirection: 'row', height: dp(22) }}>
                <View style={{ width: CHANNEL_COLUMN }} />
                <View style={{ flex: 1, overflow: 'hidden' }}>
                  <View style={{ flexDirection: 'row', transform: [{ translateX: -offset }] }}>
                    {Array.from({ length: WINDOW / 1800 }, (_, i) => (
                      <View key={i} style={{ width: widthFor(1800) }}>
                        <Text style={{ color: color.faint, fontSize: dp(14), lineHeight: dp(18), paddingLeft: dp(8) }}>{clock(windowStart + i * 1800)}</Text>
                      </View>
                    ))}
                  </View>
                </View>
              </View>
              <ScrollView ref={rows} scrollEnabled={false} showsVerticalScrollIndicator={false} contentContainerStyle={{ gap: dp(6) }}>
                {channels.map((c, i) => (
                  <GuideRow key={c.streamId} channel={c} listing={listingOf(c)} windowStart={windowStart} windowEnd={windowEnd} now={now}
                    focusTime={focusTime} current={i === index && gridFocused} catchUpFrom={c.archiveDays > 0 ? now - c.archiveDays * 86400 : null}
                    reminded={new Set(reminders.filter((r) => r.streamId === c.streamId).map((r) => r.start))}
                    favorite={live.favorites.includes(c.streamId)} offset={offset} />
                ))}
              </ScrollView>
              {nowX > 0 ? <NowLine left={CHANNEL_COLUMN + nowX - offset} /> : null}
            </View>
          )}
        </Press>
        </TVFocusGuideView>
      )}

      {menu && channel ? (
        <MenuLayer onClose={() => setMenu(false)}>
          <MenuHeading title={channel.name} subtitle={selected ? `${selected.title}  ·  ${timeRange(selected)}` : null} />
          <MenuItem preferred label="Watch" icon={(c) => <PlayGlyph color={c} size={20} />} onPress={() => { setMenu(false); play(); }} />
          <MenuItem label={live.favorites.includes(channel.streamId) ? 'Remove from Favorites' : 'Add to Favorites'}
            icon={(c) => <HeartGlyph color={c} size={20} />} onPress={() => { setMenu(false); app.toggleFavorite(channel); }} />
          {upcoming ? (
            <MenuItem label={reminded ? 'Cancel the reminder' : 'Remind me'} detail={reminded ? upcoming.title : `${upcoming.title}, ${timeRange(upcoming)}`}
              icon={(c) => <ClockGlyph color={c} size={20} />} onPress={() => { setMenu(false); app.toggleReminder(channel, upcoming); }} />
          ) : null}
        </MenuLayer>
      ) : null}
    </View>
  );
}

/** The line marking now, with the dot that makes a grid read as a guide. */
function NowLine(props: { left: number }) {
  if (props.left < CHANNEL_COLUMN) return null;
  return (
    <>
      <View pointerEvents="none" style={{ position: 'absolute', left: props.left, top: dp(22), bottom: 0, width: dp(2), backgroundColor: 'rgba(242, 244, 247, 0.8)' }} />
      <View pointerEvents="none" style={{ position: 'absolute', left: props.left - dp(4), top: dp(22), width: dp(10), height: dp(10), borderRadius: dp(5), backgroundColor: color.chalk }} />
    </>
  );
}

/** The Live TV tab: signing in, then the categories, then a category's guide. */
export function Live(props: { app: App; state: AppState }) {
  const { app, state } = props;
  if (!state.live.credentials) return <LiveSignIn app={app} state={state} />;
  if (!state.live.category) return <LiveCategories app={app} state={state} />;
  return <Guide app={app} state={state} />;
}
