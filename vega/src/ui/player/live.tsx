import React, { useEffect, useRef, useState } from 'react';
import { View } from 'react-native';
import { TVFocusGuideView, useTVEventHandler } from '@amazon-devices/react-native-kepler';
import type { App, AppState } from '../../app/store';
import type { XtreamChannel } from '../../api/xtream';
import { isOnAt } from '../../api/xtream';
import { REFUSED } from '../../core/multiview';
import { liveEngine, playLive, stopLive } from '../../player/live';
import { VideoSurface } from '../../player/engine';
import { ActionButton } from '../controls';
import { Press, useAccent } from '../focus';
import { InfoGlyph, PauseGlyph, PlayGlyph, RestartGlyph, SkipGlyph } from '../glyphs';
import { useBack } from '../menus';
import { Gradient, TransportButton } from '../surfaces';
import { Text } from '../text';
import { color, dp, type } from '../theme';
import { timeRange } from '../screens/live';

/*
 * A channel full screen (PlayerScreen.kt, live): the picture, and the controls when OK is
 * pressed. Left and right change channel while they're away; OK brings them up; Back
 * leaves the channel for the guide it was chosen from.
 */

/** How long the controls stay up without a press. */
const CONTROLS_MS = 5_000;
const NOT_PLAYING = "This channel isn't playing. It may be off air, or your provider's connection limit reached.";
const REFUSED_SAYS = "Your provider won't open this channel right now: it may be at its limit of connections. Close Reely on any other screen and try again.";
/** A dropped channel is tried this many more times before it's given up on. */
const RETRIES = 3;

export function LivePlayer(props: { app: App; state: AppState; channel: XtreamChannel }) {
  const { app, state, channel } = props;
  const accent = useAccent();
  const live = state.live;
  const engine = liveEngine();
  const [controls, setControls] = useState(false);
  const [playing, setPlaying] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);
  const [now, setNow] = useState(() => Math.floor(Date.now() / 1000));
  const lastPress = useRef(Date.now());
  const tries = useRef(0);

  const url = live.catchUp?.url ?? app.channelUrl(channel);
  useEffect(() => {
    if (!url) return;
    setFailure(null);
    tries.current = 0;
    playLive(url);
  }, [url]);

  useEffect(() => {
    const off = [
      engine.on('playing', () => { setPlaying(true); setFailure(null); tries.current = 0; }),
      engine.on('pause', () => setPlaying(false)),
      engine.onFailure((f) => {
        // A provider at its limit says so; anything else is tried again a few times.
        if (f.status != null && REFUSED.includes(f.status)) {
          setFailure(REFUSED_SAYS);
          return;
        }
        if (tries.current < RETRIES) {
          tries.current++;
          setTimeout(() => void engine.reload(), 3_000 * tries.current);
          return;
        }
        setFailure(NOT_PLAYING);
      }),
    ];
    return () => off.forEach((o) => o());
  }, [engine]);

  useEffect(() => {
    const t = setInterval(() => {
      setNow(Math.floor(Date.now() / 1000));
      if (Date.now() - lastPress.current > CONTROLS_MS) setControls(false);
    }, 1_000);
    return () => clearInterval(t);
  }, []);

  const listing = live.table[channel.streamId] ?? live.guide[channel.streamId] ?? [];
  const onNow = live.catchUp?.programme ?? listing.find((p) => isOnAt(p, now)) ?? null;
  const startOver = !live.catchUp && onNow && app.canCatchUp(channel, onNow) ? onNow : null;

  useTVEventHandler((event) => {
    if (event.eventKeyAction !== 0) return;
    lastPress.current = Date.now();
    if (controls) return;
    switch (event.eventType) {
      // Steering live TV isn't using the controls: left and right change channel, and
      // they stay away, so a second press changes channel again.
      case 'left': app.stepChannel(-1); break;
      case 'right': app.stepChannel(1); break;
      case 'up': setControls(true); break;
      case 'playpause': if (engine.paused) engine.play(); else engine.pause(); break;
    }
  });

  useBack(() => {
    if (controls) {
      setControls(false);
      return true;
    }
    // Off the channel, back to the guide; the guide's preview picks it up again.
    stopLive();
    app.stopLive();
    return true;
  });

  const number = channel.number > 0 ? `${channel.number}  ` : '';
  return (
    <View style={{ position: 'absolute', left: 0, right: 0, top: 0, bottom: 0, backgroundColor: '#000000', zIndex: 40 }}>
      <VideoSurface engine={engine} style={{ flex: 1 }} captions />
      {/* The picture itself holds the cursor while the controls are away: OK brings them up. */}
      {!controls && !failure ? (
        <Press preferred onPress={() => { lastPress.current = Date.now(); setControls(true); }}
          style={{ position: 'absolute', left: 0, right: 0, top: 0, bottom: 0 }}>
          {() => null}
        </Press>
      ) : null}
      {failure ? (
        <View style={{ position: 'absolute', left: 0, right: 0, top: 0, bottom: 0, alignItems: 'center', justifyContent: 'center', gap: dp(16) }}>
          <Text style={[type.body, { textAlign: 'center', maxWidth: dp(640) }]}>{failure}</Text>
          <ActionButton label="Try again" emphasised preferred onPress={() => { setFailure(null); tries.current = 0; void engine.reload(); }} />
        </View>
      ) : null}
      {controls ? (
        <View style={{ position: 'absolute', left: 0, right: 0, bottom: 0 }}>
          <Gradient id="liveControls" vertical stops={[[0, color.ink, 0], [0.45, color.ink, 0.85], [1, color.ink, 0.97]]} />
          <TVFocusGuideView autoFocus style={{ paddingHorizontal: dp(48), paddingTop: dp(20), paddingBottom: dp(24), gap: dp(12) }}>
            <View style={{ flexDirection: 'row', alignItems: 'center' }}>
              <View style={{ flex: 1, paddingRight: dp(16) }}>
                <View style={{ flexDirection: 'row', alignItems: 'center' }}>
                  {!live.catchUp ? (
                    <Text style={[type.label, { color: color.ink, fontWeight: '700', letterSpacing: dp(1.2), marginRight: dp(8), borderRadius: dp(6), overflow: 'hidden', backgroundColor: accent.color, paddingHorizontal: dp(7), paddingVertical: dp(1) }]}>LIVE</Text>
                  ) : null}
                  <Text numberOfLines={1} style={[type.body, { fontWeight: '600' }]}>{number + channel.name}</Text>
                </View>
                {onNow ? <Text numberOfLines={1} style={[type.label, { color: color.muted }]}>{`${onNow.title}  ·  ${timeRange(onNow)}`}</Text> : null}
              </View>
              <View style={{ flexDirection: 'row', alignItems: 'center', gap: dp(16) }}>
                <TransportButton diameter={36} onPress={() => { lastPress.current = Date.now(); app.stepChannel(-1); }} glyph={(t) => <SkipGlyph color={t} forward={false} size={16} />} />
                <TransportButton diameter={44} filled preferred onPress={() => { lastPress.current = Date.now(); if (engine.paused) engine.play(); else engine.pause(); }}
                  glyph={(t) => (playing ? <PauseGlyph color={t} size={20} /> : <PlayGlyph color={t} size={20} />)} />
                <TransportButton diameter={36} onPress={() => { lastPress.current = Date.now(); app.stepChannel(1); }} glyph={(t) => <SkipGlyph color={t} forward size={16} />} />
              </View>
              <View style={{ flex: 1, flexDirection: 'row', justifyContent: 'flex-end', gap: dp(10) }}>
                <TransportButton diameter={36} onPress={() => { lastPress.current = Date.now(); }} glyph={(t) => <InfoGlyph color={t} size={16} />} />
                {startOver ? (
                  <TransportButton diameter={36} onPress={() => { lastPress.current = Date.now(); if (live.watching != null) app.playCatchUp(live.watching, startOver); }}
                    glyph={(t) => <RestartGlyph color={t} size={16} />} />
                ) : null}
              </View>
            </View>
          </TVFocusGuideView>
        </View>
      ) : null}
    </View>
  );
}
