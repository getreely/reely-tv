import React, { useEffect, useRef, useState } from 'react';
import { Animated, Easing, Image, View } from 'react-native';
import { TVFocusGuideView, useTVEventHandler } from '@amazon-devices/react-native-kepler';
import type { PlexItem } from '../../api/plex';
import { ActionButton } from '../controls';
import { Press, pillColors, useAccent } from '../focus';
import { Gradient, TitleArt } from '../surfaces';
import { Text } from '../text';
import { color, dp, type } from '../theme';

/*
 * Up Next (UpNext.kt): the next episode across the screen, the credits carrying on in a
 * window in the top right, a countdown on Play next. Any press but OK on Play next stops
 * the countdown: somebody reaching for the remote is making up their mind.
 */

/** Where the finished episode goes while the next one is offered: top right, in the safe area. */
export const WINDOW_WIDTH = dp(336);
export const WINDOW_TOP = dp(27);
export const WINDOW_END = dp(48);

/** "Season 2 · Episode 6 · 44 min", from whichever of those the item has. */
const upNextLine = (item: PlexItem) => [
  item.parentIndex != null ? `Season ${item.parentIndex}` : null,
  item.index != null ? `Episode ${item.index}` : null,
  item.durationMs > 0 ? `${Math.max(1, Math.round(item.durationMs / 60_000))} min` : null,
].filter(Boolean).join('  ·  ') || null;

export function UpNextScreen(props: {
  item: PlexItem; still: string | null; logo: string | null; nowTitle: string; ended: boolean; seconds: number;
  onPlay: () => void; onDecline: () => void;
}) {
  const accent = useAccent();
  const { item } = props;
  const [held, setHeld] = useState(false);
  const playFocused = useRef(false);
  useTVEventHandler((event) => {
    if (event.eventKeyAction !== 0) return;
    if (!(event.eventType === 'select' && playFocused.current)) setHeld(true);
  });
  return (
    <View style={{ position: 'absolute', left: 0, right: 0, top: 0, bottom: 0, backgroundColor: color.ink }}>
      {props.still ? <Image source={{ uri: props.still }} resizeMode="cover" style={{ position: 'absolute', left: 0, right: 0, top: 0, bottom: 0 }} /> : null}
      {/* Dark enough on the left and along the bottom for the text; the still shows through at the top right. */}
      <Gradient id="upNextSide" stops={[[0, color.ink, 0.9], [0.5, color.ink, 0.55], [1, color.ink, 0.1]]} />
      <Gradient id="upNextFoot" vertical stops={[[0.5, color.ink, 0], [1, color.ink, 0.85]]} />
      {/* A hairline frame just outside the window, so it reads as a window. */}
      <View style={{
        position: 'absolute', top: WINDOW_TOP - dp(2), right: WINDOW_END - dp(2), width: WINDOW_WIDTH + dp(4), height: (WINDOW_WIDTH * 9) / 16 + dp(4),
        borderRadius: dp(4), backgroundColor: 'rgba(242, 244, 247, 0.22)',
      }} />
      <Text numberOfLines={1} style={[type.label, { color: color.muted, position: 'absolute', top: WINDOW_TOP + (WINDOW_WIDTH * 9) / 16 + dp(10), right: WINDOW_END, maxWidth: WINDOW_WIDTH }]}>
        {`${props.ended ? 'Finished' : 'Credits'}  ·  ${props.nowTitle}`}
      </Text>
      <View style={{ position: 'absolute', left: dp(48), bottom: dp(27), maxWidth: dp(560), gap: dp(10) }}>
        <Text style={[type.label, { color: accent.color, fontWeight: '600', letterSpacing: dp(1.6) }]}>UP NEXT</Text>
        <TitleArt url={props.logo} title={item.grandparentTitle ?? item.title} />
        {upNextLine(item) ? <Text style={type.meta}>{upNextLine(item)}</Text> : null}
        {item.grandparentTitle ? <Text numberOfLines={1} style={type.headline}>{item.title}</Text> : null}
        {item.summary ? <Text numberOfLines={3} style={[type.body, { color: color.muted }]}>{item.summary}</Text> : null}
        <TVFocusGuideView autoFocus trapFocusUp trapFocusDown trapFocusLeft trapFocusRight style={{ flexDirection: 'row', gap: dp(12), paddingTop: dp(8) }}>
          <CountdownButton itemKey={item.ratingKey} seconds={props.seconds} running={!held}
            onFocusChange={(on) => { playFocused.current = on; }}
            onPlay={() => { setHeld(true); props.onPlay(); }} />
          <ActionButton label={props.ended ? 'Close' : 'Watch credits'} onPress={props.onDecline} />
        </TVFocusGuideView>
      </View>
    </View>
  );
}

/**
 * Play, filling up as the countdown runs, and playing when it's full. Zero seconds is
 * the countdown switched off; a stopped one empties and stays empty.
 */
function CountdownButton(props: { itemKey: string; seconds: number; running: boolean; onPlay: () => void; onFocusChange: (on: boolean) => void }) {
  const accent = useAccent();
  const [focused, setFocused] = useState(false);
  const progress = useRef(new Animated.Value(0)).current;
  const [left, setLeft] = useState(props.seconds);
  const play = useRef(props.onPlay);
  play.current = props.onPlay;
  const counting = props.running && props.seconds > 0;
  useEffect(() => {
    progress.setValue(0);
    if (!counting) return;
    const started = Date.now();
    setLeft(props.seconds);
    const tick = setInterval(() => setLeft(Math.max(0, Math.ceil(props.seconds - (Date.now() - started) / 1000))), 250);
    const run = Animated.timing(progress, { toValue: 1, duration: props.seconds * 1000, easing: Easing.linear, useNativeDriver: false });
    run.start(({ finished }) => { if (finished) play.current(); });
    return () => {
      clearInterval(tick);
      run.stop();
    };
  }, [props.itemKey, props.seconds, counting]);
  const c = pillColors(focused, accent.color);
  return (
    <Press preferred onPress={props.onPlay} onFocusChange={(on) => { setFocused(on); props.onFocusChange(on); }}
      style={{ borderRadius: 999, overflow: 'hidden', backgroundColor: c.fill, transform: [{ scale: focused ? 1.04 : 1 }] }}>
      {() => (
        <>
          {counting ? (
            <Animated.View style={{
              position: 'absolute', left: 0, top: 0, bottom: 0, backgroundColor: accent.color, opacity: focused ? 0.32 : 0.4,
              width: progress.interpolate({ inputRange: [0, 1], outputRange: ['0%', '100%'] }),
            }} />
          ) : null}
          <Text numberOfLines={1} style={[type.meta, { color: c.text, fontWeight: focused ? '600' : '500', paddingHorizontal: dp(26), paddingVertical: dp(12) }]}>
            {counting ? `Play next  ·  ${left}` : 'Play next'}
          </Text>
        </>
      )}
    </Press>
  );
}
