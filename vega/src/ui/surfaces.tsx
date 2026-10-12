import React, { useEffect, useRef, useState } from 'react';
import { Animated, Image, ScrollView, View, type StyleProp, type ViewStyle } from 'react-native';
import { Text } from './text';
import Svg, { Defs, LinearGradient, RadialGradient, Rect, Stop } from '@amazon-devices/react-native-svg';
import { Chip } from './controls';
import { Press, useAccent } from './focus';
import { PlayGlyph } from './glyphs';
import { color, dp, type } from './theme';

/*
 * The Fire TV app's surfaces (Surfaces.kt): the artwork behind a browse screen, the text
 * describing what's focused, ratings, the round and wide actions, and the transport
 * buttons.
 */

/** How far a page's backdrop reaches up behind the floating top bar. */
export const BACKDROP_RISE = dp(68);
const FADE_MS = 450;

/** A gradient filling its box, from stops of [offset, color, opacity]. */
export function Gradient(props: { id: string; vertical?: boolean; stops: Array<[number, string, number]>; style?: StyleProp<ViewStyle> }) {
  return (
    <View pointerEvents="none" style={[{ position: 'absolute', left: 0, right: 0, top: 0, bottom: 0 }, props.style]}>
      <Svg width="100%" height="100%" preserveAspectRatio="none">
        <Defs>
          <LinearGradient id={props.id} x1="0" y1="0" x2={props.vertical ? '0' : '1'} y2={props.vertical ? '1' : '0'}>
            {props.stops.map(([offset, c, opacity]) => <Stop key={offset} offset={offset} stopColor={c} stopOpacity={opacity} />)}
          </LinearGradient>
        </Defs>
        <Rect x="0" y="0" width="100%" height="100%" fill={`url(#${props.id})`} />
      </Svg>
    </View>
  );
}

/**
 * The artwork behind a browse screen, darkened until text sits comfortably on it, rising
 * behind the top bar. Moving between titles fades picture to picture: the next one loads
 * out of sight and only then fades in.
 */
export function HeroBackdrop(props: { url: string | null | undefined; scrimFromLeft?: boolean; glow?: string | null; style?: StyleProp<ViewStyle> }) {
  const [layers, setLayers] = useState<Array<{ url: string; key: number }>>([]);
  const fade = useRef(new Animated.Value(1)).current;
  const next = useRef(0);
  const pending = useRef<string | null>(null);
  useEffect(() => {
    if (!props.url || layers[layers.length - 1]?.url === props.url) return;
    pending.current = props.url;
    // Load first, then fade: picture to picture, never to black and back.
    Image.prefetch(props.url).catch(() => false).then(() => {
      if (pending.current !== props.url) return;
      setLayers((l) => [...l.slice(-1), { url: props.url!, key: next.current++ }]);
      fade.setValue(0);
      Animated.timing(fade, { toValue: 1, duration: FADE_MS, useNativeDriver: true }).start();
    });
  }, [props.url]);
  const scrimFromLeft = props.scrimFromLeft ?? true;
  return (
    <View pointerEvents="none" style={[{ position: 'absolute', left: 0, right: 0, top: -BACKDROP_RISE, bottom: 0 }, props.style]}>
      {layers.map((layer, i) => (
        <Animated.Image key={layer.key} source={{ uri: layer.url }} resizeMode="cover"
          style={{ position: 'absolute', left: 0, right: 0, top: 0, bottom: 0, opacity: i === layers.length - 1 ? fade : 1 }} />
      ))}
      {/* A flat dim first, so a bright poster can't overpower the text on top of it. */}
      <View style={{ position: 'absolute', left: 0, right: 0, top: 0, bottom: 0, backgroundColor: 'rgba(8, 9, 11, 0.45)' }} />
      {scrimFromLeft ? (
        <Gradient id="scrimLeft" stops={[[0, color.ink, 0.92], [0.42, color.ink, 0.62], [0.78, color.ink, 0.12], [1, color.ink, 0]]} />
      ) : null}
      <Gradient id="scrimDown" vertical stops={[[0, color.ink, 0.6], [0.3, color.ink, 0.25], [0.72, color.ink, 0.88], [1, color.ink, 1]]} />
      {props.glow ? (
        <View style={{ position: 'absolute', left: 0, right: 0, top: 0, bottom: 0 }}>
          <Svg width="100%" height="100%" preserveAspectRatio="none" viewBox="0 0 100 56.25">
            <Defs>
              <RadialGradient id="glowLow" cx="8" cy="58.5" r="75" gradientUnits="userSpaceOnUse">
                <Stop offset="0" stopColor={props.glow} stopOpacity={0.2} />
                <Stop offset="1" stopColor={props.glow} stopOpacity={0} />
              </RadialGradient>
              <RadialGradient id="glowHigh" cx="100" cy="0" r="55" gradientUnits="userSpaceOnUse">
                <Stop offset="0" stopColor={props.glow} stopOpacity={0.09} />
                <Stop offset="1" stopColor={props.glow} stopOpacity={0} />
              </RadialGradient>
            </Defs>
            <Rect x="0" y="0" width="100" height="56.25" fill="url(#glowLow)" />
            <Rect x="0" y="0" width="100" height="56.25" fill="url(#glowHigh)" />
          </Svg>
        </View>
      ) : null}
    </View>
  );
}

/** A translucent surface with a hairline edge: the stand-in for frosted glass. */
export const glass = (radius = 14): ViewStyle => ({
  borderRadius: dp(radius),
  overflow: 'hidden',
  backgroundColor: color.glass,
  borderWidth: dp(1),
  borderColor: color.glassEdge,
});

/** A panel laid over the picture: nearly opaque, so its text reads over anything. */
export const sheet = (radius = 20): ViewStyle => ({
  borderRadius: dp(radius),
  overflow: 'hidden',
  backgroundColor: 'rgba(21, 23, 27, 0.95)',
  borderWidth: dp(1),
  borderColor: color.glassEdge,
});

/** A small circular action with its name under it. The filled one is the only one in colour. */
export function IconAction(props: { label: string; filled?: boolean; onPress: () => void; glyph: (tint: string) => React.ReactNode; preferred?: boolean; first?: boolean; focusKey?: string; onFocus?: () => void }) {
  const accent = useAccent();
  const [focused, setFocused] = useState(false);
  return (
    <View style={{ width: dp(66), alignItems: 'center' }}>
      <Press onPress={props.onPress} preferred={props.preferred} first={props.first} focusKey={props.focusKey} onFocusChange={(on) => { setFocused(on); if (on) props.onFocus?.(); }}
        style={{
          width: dp(44), height: dp(44), borderRadius: dp(22), alignItems: 'center', justifyContent: 'center',
          transform: [{ scale: focused ? 1.08 : 1 }],
          backgroundColor: focused ? color.chalk : props.filled ? accent.color : color.glass,
          borderWidth: dp(1), borderColor: focused || props.filled ? 'transparent' : color.glassEdge,
        }}>
        {() => props.glyph(focused || props.filled ? color.ink : color.chalk)}
      </Press>
      <Text numberOfLines={1} style={{ color: focused ? color.chalk : color.muted, fontSize: dp(14), lineHeight: dp(18), textAlign: 'center', marginTop: dp(6) }}>{props.label}</Text>
    </View>
  );
}

/** The page's main action, wide: the symbol and what it plays, "Resume S2 · E3". */
export const PlayAction = React.forwardRef<View, { label: string; onPress: () => void; preferred?: boolean; first?: boolean; focusKey?: string; onFocus?: () => void }>(function PlayAction(props, ref) {
  const accent = useAccent();
  const [focused, setFocused] = useState(false);
  return (
    <Press ref={ref} onPress={props.onPress} preferred={props.preferred} first={props.first} focusKey={props.focusKey} onFocusChange={(on) => { setFocused(on); if (on) props.onFocus?.(); }}
      style={{
        height: dp(44), borderRadius: dp(22), flexDirection: 'row', alignItems: 'center', gap: dp(10), paddingHorizontal: dp(26),
        transform: [{ scale: focused ? 1.05 : 1 }], backgroundColor: focused ? color.chalk : accent.color,
      }}>
      {() => (
        <>
          <PlayGlyph color={focused ? color.ink : accent.on} size={18} />
          <Text numberOfLines={1} style={{ color: focused ? color.ink : accent.on, fontSize: dp(17), lineHeight: dp(21), fontWeight: '600' }}>{props.label}</Text>
        </>
      )}
    </Press>
  );
});

function Badge(props: { text: string; tint: string; emphasised?: boolean }) {
  return (
    <View style={{ borderRadius: dp(6), paddingHorizontal: dp(8), paddingVertical: dp(3), borderWidth: dp(1), borderColor: withOpacity(props.tint, 0.6), backgroundColor: props.emphasised ? withOpacity(props.tint, 0.2) : 'transparent' }}>
      <Text numberOfLines={1} style={{ color: color.chalk, fontSize: dp(14), lineHeight: dp(18), fontWeight: props.emphasised ? '600' : '400' }}>{props.text}</Text>
    </View>
  );
}

function withOpacity(c: string, a: number): string {
  if (c.startsWith('rgba')) return c;
  const [r, g, b] = [1, 3, 5].map((i) => parseInt(c.slice(i, i + 2), 16));
  return `rgba(${r}, ${g}, ${b}, ${a})`;
}

/** Critic score, audience score and certificate as separate marks; then the facts and what the file is. */
export function RatingBadges(props: { critic?: number | null; audience?: number | null; content?: string | null; trailing?: string[]; qualities?: string[]; style?: StyleProp<ViewStyle> }) {
  const accent = useAccent();
  return (
    <View style={[{ flexDirection: 'row', alignItems: 'center', gap: dp(8) }, props.style]}>
      {props.critic != null ? <Badge text={`★ ${props.critic.toFixed(1)}`} tint={accent.color} emphasised /> : null}
      {props.audience != null ? <Badge text={`${Math.floor(props.audience * 10)}%`} tint={color.good} /> : null}
      {props.content ? <Badge text={props.content} tint={color.glassEdge} /> : null}
      {(props.trailing ?? []).map((fact, i) => <Text key={`t${i}`} numberOfLines={1} style={type.meta}>{fact}</Text>)}
      {(props.qualities ?? []).map((q, i) => (
        <Text key={`q${i}`} numberOfLines={1}
          style={[type.label, { fontWeight: '600', marginLeft: i === 0 ? dp(6) : 0, borderRadius: dp(6), overflow: 'hidden', backgroundColor: 'rgba(242, 244, 247, 0.13)', paddingHorizontal: dp(8), paddingVertical: dp(2) }]}>{q}</Text>
      ))}
    </View>
  );
}

/** One pill per season, above the title. */
export function PillRail<T>(props: { items: T[]; label: (t: T) => string; selected: (t: T) => boolean; onSelect: (t: T) => void; itemKey: (t: T) => string }) {
  return (
    <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={{ gap: dp(8) }}>
      {props.items.map((item) => (
        <Chip key={props.itemKey(item)} label={props.label(item)} selected={props.selected(item)} onPress={() => props.onSelect(item)} />
      ))}
    </ScrollView>
  );
}

const LOGO_HEIGHT = dp(50);
const LOGO_WIDTH = dp(320);

/** A title's own logo in place of its name, or the name at display size when there's none. */
export function TitleArt(props: { url?: string | null; title: string }) {
  const [failed, setFailed] = useState(false);
  useEffect(() => setFailed(false), [props.url]);
  if (!props.url || failed) {
    return <Text numberOfLines={1} style={[type.display, { fontSize: dp(28), lineHeight: dp(34), letterSpacing: dp(-0.4) }]}>{props.title}</Text>;
  }
  return (
    <View style={{ width: LOGO_WIDTH, height: LOGO_HEIGHT, justifyContent: 'flex-end', alignItems: 'flex-start' }}>
      <Image source={{ uri: props.url }} resizeMode="contain" onError={() => setFailed(true)}
        style={{ width: LOGO_WIDTH, height: LOGO_HEIGHT }} />
    </View>
  );
}

/** The block of text describing whatever has focus. */
export function HeroText(props: {
  eyebrow?: string | null; title: string; critic?: number | null; audience?: number | null; content?: string | null;
  facts: string[]; summary?: string | null; summaryLines?: number; logo?: string | null; subtitle?: string | null; qualities?: string[];
  style?: StyleProp<ViewStyle>;
}) {
  const hasBadges = props.critic != null || props.audience != null || !!props.content || props.facts.length > 0 || (props.qualities ?? []).length > 0;
  return (
    <View style={[{ gap: dp(8) }, props.style]}>
      {props.eyebrow ? (
        <Text numberOfLines={1} style={{ color: color.faint, fontSize: dp(14), lineHeight: dp(18), letterSpacing: dp(1.5), fontWeight: '500', textTransform: 'uppercase' }}>{props.eyebrow}</Text>
      ) : null}
      <TitleArt url={props.logo} title={props.title} />
      {props.subtitle ? <Text numberOfLines={1} style={type.headline}>{props.subtitle}</Text> : null}
      {hasBadges ? <RatingBadges critic={props.critic} audience={props.audience} content={props.content} trailing={props.facts} qualities={props.qualities} /> : null}
      {props.summary ? (
        <Text numberOfLines={props.summaryLines ?? 3} style={{ color: color.muted, fontSize: dp(15), lineHeight: dp(21), maxWidth: dp(560) }}>{props.summary}</Text>
      ) : null}
    </View>
  );
}

/** A transport control: no caption, the universal shapes. */
export const TransportButton = React.forwardRef<View, { onPress: () => void; filled?: boolean; enabled?: boolean; diameter?: number; glyph: (tint: string) => React.ReactNode; preferred?: boolean; first?: boolean; focusKey?: string; onFocus?: () => void }>(function TransportButton(props, ref) {
  const accent = useAccent();
  const [focused, setFocused] = useState(false);
  const enabled = props.enabled ?? true;
  const d = dp(props.diameter ?? 44);
  const tint = !enabled ? 'rgba(242, 244, 247, 0.25)' : focused || props.filled ? color.ink : color.chalk;
  return (
    <Press ref={ref} onPress={enabled ? props.onPress : undefined} focusable={enabled} preferred={props.preferred} first={props.first} focusKey={props.focusKey}
      onFocusChange={(on) => { setFocused(on); if (on) props.onFocus?.(); }}
      style={{
        width: d, height: d, borderRadius: d / 2, alignItems: 'center', justifyContent: 'center', transform: [{ scale: focused ? 1.08 : 1 }],
        backgroundColor: !enabled ? 'transparent' : focused ? color.chalk : props.filled ? accent.color : color.glass,
        borderWidth: dp(1), borderColor: focused || props.filled || !enabled ? 'transparent' : color.glassEdge,
      }}>
      {() => props.glyph(tint)}
    </Press>
  );
});
