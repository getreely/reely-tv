import React, { useEffect, useRef, useState } from 'react';
import { ScrollView, View } from 'react-native';
import { TVFocusGuideView, useTVEventHandler } from '@amazon-devices/react-native-kepler';
import type { App, AppState } from '../../app/store';
import type { Programme, XtreamCategory, XtreamChannel } from '../../api/xtream';
import { catchUpProgramme, isOnAt } from '../../api/xtream';
import { Press, focusWhenReady, useAccent } from '../focus';
import { ClockGlyph, HeartGlyph, PlayGlyph, TilesGlyph } from '../glyphs';
import { MenuHeading, MenuItem, MenuLayer, useBack } from '../menus';
import { Gradient } from '../surfaces';
import { Text } from '../text';
import { color, dp } from '../theme';
import { CHANNEL_COLUMN, GuideRow, NowLine, ROW_HEIGHT, RowsAbove, TABLE_REST_MS, WINDOW, clock, rowsNear, timeRange, widthFor } from '../screens/live';

/*
 * The guide, raised over a channel that keeps playing behind it (GuideOverlay.kt). Nothing
 * here is opaque: the rows are the guide page's translucent blocks, over a shade that
 * deepens towards the bottom of the screen, so the next channel is picked while the one
 * on is still watched. The categories are a row above the channels, reached with up;
 * Back closes the guide. Opened to add a channel beside the one playing, or to put one in
 * a tile, it says so, and OK does that instead.
 */

/** Half an hour per press of left or right, which is how a guide is read. */
const TIME_STEP = 1800;
const GRID_HEIGHT = dp(254);
const RULER = dp(22);
const ROW_GAP = dp(6);

export type GuideMode = { kind: 'browse' } | { kind: 'add' } | { kind: 'replace'; tile: number };

export function GuideOverlay(props: {
  app: App; state: AppState; mode: GuideMode;
  /** Watch [channels][index], from the category browsed, or a programme that's over from its archive. */
  onWatch: (category: XtreamCategory | null, channels: XtreamChannel[], index: number, programme: Programme | null) => void;
  /** Beside what's playing, or into the tile asked about. */
  onPick: (channel: XtreamChannel) => void;
  canAddTile: boolean;
  onClose: () => void;
}) {
  const { app, state, mode } = props;
  const live = state.live;
  const adding = mode.kind !== 'browse';
  const verb = mode.kind === 'replace' ? 'Replace with' : 'Add';
  const categories = app.shownCategories();
  const [category, setCategory] = useState<XtreamCategory | null>(live.category);
  const [channels, setChannels] = useState<XtreamChannel[]>(live.channels);
  const [loading, setLoading] = useState(false);
  const playingId = live.watching != null ? live.channels[live.watching]?.streamId : null;
  const [index, setIndex] = useState(() => Math.max(0, live.channels.findIndex((c) => c.streamId === playingId)));
  const [now, setNow] = useState(() => Math.floor(Date.now() / 1000));
  const [focusTime, setFocusTime] = useState(now);
  const [windowStart, setWindowStart] = useState(() => Math.floor(now / 1800) * 1800 - 1800);
  const windowEnd = windowStart + WINDOW;
  const [inCategories, setInCategories] = useState(false);
  const [menu, setMenu] = useState<XtreamChannel | null>(null);
  const grid = useRef<View>(null);
  const categoryRefs = useRef(new Map<string, View | null>());
  const rows = useRef<ScrollView>(null);
  const hasCategories = categories.length > 1;

  useEffect(() => {
    const t = setInterval(() => setNow(Math.floor(Date.now() / 1000)), 30_000);
    return () => clearInterval(t);
  }, []);
  // Only the rows around the cursor, once it rests: see App.loadTable.
  useEffect(() => {
    if (!channels.length) return;
    const { from, to } = rowsNear(channels.length, index);
    const t = setTimeout(() => void app.loadTable(channels.slice(from, to)), TABLE_REST_MS);
    return () => clearTimeout(t);
  }, [channels, index]);
  useEffect(() => {
    rows.current?.scrollTo({ y: Math.max(0, index * (ROW_HEIGHT + ROW_GAP) - (ROW_HEIGHT + ROW_GAP) / 2), animated: true });
  }, [index, channels]);

  const listingOf = (c: XtreamChannel) => live.table[c.streamId] ?? live.guide[c.streamId] ?? [];
  const channel = channels[Math.min(index, channels.length - 1)] ?? null;
  const onNow = channel ? listingOf(channel).find((p) => isOnAt(p, focusTime)) ?? null : null;
  const offset = Math.max(0, widthFor(focusTime - windowStart) - dp(150));
  const near = rowsNear(channels.length, index);

  // Another category's channels, browsed here without leaving the one playing.
  const browse = async (c: XtreamCategory) => {
    setCategory(c);
    setInCategories(false);
    setIndex(0);
    if (c.id === live.category?.id) {
      setChannels(live.channels);
      focusWhenReady(grid);
      return;
    }
    setChannels([]);
    setLoading(true);
    try {
      const found = await app.channelsOf(c);
      setChannels(found);
    } finally {
      setLoading(false);
      focusWhenReady(grid);
    }
  };

  const pick = () => {
    if (!channel) return;
    if (adding) {
      props.onPick(channel);
      return;
    }
    const past = catchUpProgramme(channel, listingOf(channel), focusTime, Math.floor(Date.now() / 1000));
    props.onWatch(category ?? live.category, channels, index, past);
  };

  const moveTime = (delta: number) => {
    const next = Math.max(windowStart, Math.min(windowEnd - 60, focusTime + delta * TIME_STEP));
    setFocusTime(next);
    if (next > windowEnd - 3 * 3600 || next < windowStart + 1800) setWindowStart(Math.floor(next / 1800) * 1800 - 1800);
  };

  const toCategories = () => {
    setInCategories(true);
    const target = categoryRefs.current.get(category?.id ?? '') ?? categoryRefs.current.get(categories[0]?.id ?? '');
    if (target) focusWhenReady({ current: target });
  };

  useTVEventHandler((event) => {
    if (menu || event.eventKeyAction !== 0) return;
    const t = event.eventType;
    if (inCategories) {
      if (t === 'down') {
        setInCategories(false);
        focusWhenReady(grid);
      }
      return;
    }
    switch (t) {
      case 'up':
        if (index > 0) setIndex(index - 1);
        else if (hasCategories) toCategories();
        else props.onClose();
        break;
      case 'down':
        if (index < channels.length - 1) setIndex(index + 1);
        break;
      case 'left': moveTime(-1); break;
      case 'right': moveTime(1); break;
      case 'menu': if (channel) setMenu(channel); break;
    }
  });

  // Back closes the guide, full stop; the menu over it closes first, by its own.
  useBack(() => {
    props.onClose();
    return true;
  });

  const reminders = live.reminders;
  const upcoming = menu ? listingOf(menu).find((p) => isOnAt(p, focusTime)) : undefined;
  const remindable = upcoming && upcoming.start > now ? upcoming : null;
  const reminded = !!(remindable && menu && reminders.some((r) => r.streamId === menu.streamId && r.start === remindable.start));
  const nowX = widthFor(now - windowStart);
  const heading = !channels.length ? (loading ? 'Loading…' : 'Nothing in this category')
    : adding ? `${verb} — ${channel?.name ?? ''}` : channel?.name ?? '';
  const hint = !channels.length ? (loading ? '' : 'Pick another above')
    : adding ? `OK · ${verb.toLowerCase()} this channel`
    : onNow ? `${onNow.title}  ·  ${timeRange(onNow)}`
    : hasCategories ? 'OK to watch · hold OK for more · up for categories' : 'OK to watch · hold OK for more';

  return (
    <View style={{ position: 'absolute', left: 0, right: 0, top: 0, bottom: 0, zIndex: 20 }}>
      <View style={{ position: 'absolute', left: 0, right: 0, bottom: 0 }}>
        <Gradient id="guideOverlay" vertical stops={[[0, color.ink, 0], [0.18, color.ink, 0.82], [1, color.ink, 0.94]]} />
        <View style={{ paddingHorizontal: dp(28), paddingTop: dp(12), paddingBottom: dp(10), gap: dp(8) }}>
          {hasCategories ? (
            <TVFocusGuideView autoFocus style={{ opacity: inCategories ? 1 : 0.72 }}>
              <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={{ gap: dp(10), paddingVertical: dp(4) }}>
                {categories.map((c) => (
                  <CategoryChip key={c.id} label={c.name} selected={c.id === category?.id}
                    pressRef={(v) => { categoryRefs.current.set(c.id, v); }}
                    onFocus={() => setInCategories(true)} onPress={() => void browse(c)} />
                ))}
              </ScrollView>
            </TVFocusGuideView>
          ) : null}
          <View>
            <Text numberOfLines={1} style={{ color: color.chalk, fontSize: dp(17), lineHeight: dp(22), fontWeight: '600' }}>{heading}</Text>
            <Text numberOfLines={1} style={{ color: color.faint, fontSize: dp(14), lineHeight: dp(19) }}>{hint}</Text>
          </View>
          {/* The grid holds the cursor: the arrows move through it, and only up from the top leaves it. */}
          <TVFocusGuideView trapFocusUp={!hasCategories || index > 0} trapFocusDown trapFocusLeft trapFocusRight>
            <Press ref={grid} preferred onPress={pick} onHold={() => { if (channel) setMenu(channel); }}
              onFocusChange={(on) => { if (on) setInCategories(false); }} style={{ height: GRID_HEIGHT }}>
              {() => (
                <View style={{ height: GRID_HEIGHT, overflow: 'hidden' }}>
                  <View style={{ flexDirection: 'row', height: RULER }}>
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
                  <ScrollView ref={rows} scrollEnabled={false} showsVerticalScrollIndicator={false} contentContainerStyle={{ gap: ROW_GAP }}>
                    <RowsAbove rows={near.from} gap={ROW_GAP} />
                    {channels.slice(near.from, near.to).map((c, k) => (
                      <GuideRow key={c.streamId} channel={c} listing={listingOf(c)} windowStart={windowStart} windowEnd={windowEnd} now={now}
                        focusTime={focusTime} current={near.from + k === index && !inCategories} catchUpFrom={c.archiveDays > 0 ? now - c.archiveDays * 86400 : null}
                        reminded={new Set(reminders.filter((r) => r.streamId === c.streamId).map((r) => r.start))}
                        favorite={live.favorites.includes(c.streamId)} offset={offset} />
                    ))}
                  </ScrollView>
                  {nowX > 0 ? <NowLine left={CHANNEL_COLUMN + nowX - offset} top={RULER} /> : null}
                </View>
              )}
            </Press>
          </TVFocusGuideView>
        </View>
      </View>

      {menu ? (
        <MenuLayer onClose={() => { setMenu(null); focusWhenReady(grid); }}>
          <MenuHeading title={menu.name} />
          <MenuItem preferred label="Watch this channel" icon={(c) => <PlayGlyph color={c} size={20} />}
            onPress={() => {
              setMenu(null);
              const at = channels.findIndex((c) => c.streamId === menu.streamId);
              props.onWatch(category ?? live.category, channels, Math.max(0, at), null);
            }} />
          <MenuItem label={mode.kind === 'replace' ? 'Put it in that tile' : "Add beside what's playing"}
            detail={props.canAddTile || mode.kind === 'replace' ? null : 'You can watch up to four channels at once.'}
            icon={(c) => <TilesGlyph color={c} size={20} />}
            onPress={() => { if (props.canAddTile || mode.kind === 'replace') { setMenu(null); props.onPick(menu); } }} />
          {remindable ? (
            <MenuItem label={reminded ? 'Cancel the reminder' : 'Remind me'} detail={remindable.title} icon={(c) => <ClockGlyph color={c} size={20} />}
              onPress={() => { setMenu(null); app.toggleReminder(menu, remindable); focusWhenReady(grid); }} />
          ) : null}
          <MenuItem label={live.favorites.includes(menu.streamId) ? 'Remove from Favorites' : 'Add to Favorites'} icon={(c) => <HeartGlyph color={c} size={20} />}
            onPress={() => { setMenu(null); app.toggleFavorite(menu); focusWhenReady(grid); }} />
        </MenuLayer>
      ) : null}
    </View>
  );
}

/** A category above the channels: dim until the cursor's on it, the open one marked. */
function CategoryChip(props: { label: string; selected: boolean; onPress: () => void; onFocus: () => void; pressRef: (v: View | null) => void }) {
  const accent = useAccent();
  const [focused, setFocused] = useState(false);
  return (
    <Press ref={props.pressRef} onPress={props.onPress} onFocusChange={(on) => { setFocused(on); if (on) props.onFocus(); }}
      style={{
        borderRadius: dp(9), paddingHorizontal: dp(14), paddingVertical: dp(9),
        backgroundColor: focused ? `${accent.color}38` : 'rgba(8, 9, 11, 0.6)',
        borderWidth: focused ? dp(2) : dp(1),
        borderColor: focused ? accent.color : props.selected ? 'rgba(242, 244, 247, 0.5)' : color.line,
      }}>
      {() => (
        <Text numberOfLines={1} style={{ color: focused || props.selected ? color.chalk : color.muted, fontSize: dp(14), lineHeight: dp(18) }}>{props.label}</Text>
      )}
    </Press>
  );
}
