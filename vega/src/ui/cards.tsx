import React, { useState } from 'react';
import { Image, View, type StyleProp, type ViewStyle } from 'react-native';
import { Text } from './text';
import { CardLift, CardRing, Press, useAccent } from './focus';
import { CheckGlyph } from './glyphs';
import { color, dp } from './theme';

/*
 * The Fire TV app's cards (MediaCards.kt): posters, the wide Continue Watching card, an
 * episode in a rail, a face from the cast, a channel. Sizes are its dp, doubled.
 */

export const POSTER_CORNER = dp(12);
export const WIDE_CORNER = dp(14);
const POSTER_CAPTION_HEIGHT = dp(40);
const EPISODE_CAPTION_HEIGHT = dp(44);

/** How far through something is: a rounded bar just inside the bottom of the artwork. */
export function ProgressStrip(props: { fraction: number; style?: StyleProp<ViewStyle> }) {
  const accent = useAccent();
  const f = Math.max(0, Math.min(1, props.fraction));
  return (
    <View style={[{ position: 'absolute', left: dp(10), right: dp(10), bottom: dp(9), height: dp(4), borderRadius: dp(2), backgroundColor: 'rgba(242, 244, 247, 0.28)' }, props.style]}>
      <View style={{ width: `${f * 100}%`, height: dp(4), borderRadius: dp(2), backgroundColor: accent.color }} />
    </View>
  );
}

const Art = (props: { uri: string | null | undefined; fallback?: string; fit?: 'cover' | 'contain' }) =>
  props.uri ? (
    <Image source={{ uri: props.uri }} resizeMode={props.fit ?? 'cover'} style={{ width: '100%', height: '100%' }} />
  ) : props.fallback ? (
    <View style={{ flex: 1, alignItems: 'center', justifyContent: 'center' }}>
      <Text style={{ color: color.faint, fontSize: dp(34), lineHeight: dp(42) }}>{props.fallback}</Text>
    </View>
  ) : null;

/** The green tick for something watched: "finished" must never compete with "focused". */
const Watched = () => (
  <View style={{ position: 'absolute', top: dp(6), right: dp(6), width: dp(18), height: dp(18), borderRadius: dp(9), backgroundColor: color.good, alignItems: 'center', justifyContent: 'center' }}>
    <CheckGlyph color={color.ink} size={11} />
  </View>
);

/** A poster's tag in the colour of what it says: here is green, on its way warm, asked quiet. */
function tagColors(tag: string, accent: { color: string; on: string }): [string, string] {
  if (tag === 'In library') return [color.good, color.ink];
  if (tag === 'Downloading' || tag === 'Partial') return [accent.color, accent.on];
  if (tag === 'Declined') return ['rgba(8, 9, 11, 0.85)', color.faint];
  return ['rgba(8, 9, 11, 0.85)', color.chalk];
}

/** A count for the round badge: as it is up to 999, then "1K", "12K". */
export const badgeText = (count: number) => (count < 1000 ? String(count) : `${Math.floor(count / 1000)}K`);

export interface PosterProps {
  title: string;
  subtitle?: string | null;
  image?: string | null;
  onPress: () => void;
  onHold?: () => void;
  onFocus?: () => void;
  badge?: number | null;
  progress?: number | null;
  watched?: boolean;
  /** Fire TV dp. */
  width?: number;
  tag?: string | null;
  preferred?: boolean; first?: boolean; focusKey?: string;
}

/** A poster. The badge carries "six new episodes", so a show that dropped a season reads as one arrival. */
export function PosterCard(props: PosterProps) {
  const accent = useAccent();
  const [focused, setFocused] = useState(false);
  const width = dp(props.width ?? 132);
  const badge = props.badge ?? null;
  return (
    <Press onPress={props.onPress} onHold={props.onHold} preferred={props.preferred} first={props.first} focusKey={props.focusKey}
      onFocusChange={(on) => { setFocused(on); if (on) props.onFocus?.(); }}>
      {() => (
        <CardLift focused={focused} style={{ width, padding: dp(4) }}>
          <CardRing focused={focused} corner={POSTER_CORNER} style={{ width: '100%', aspectRatio: 2 / 3 }}>
            <Art uri={props.image} fallback={props.title.slice(0, 1).toUpperCase()} />
            {props.watched && (badge == null || badge <= 1) ? <Watched /> : null}
            {props.tag ? (() => {
              const [fill, ink] = tagColors(props.tag!, accent);
              return (
                <View style={{ position: 'absolute', top: dp(6), left: dp(6), borderRadius: dp(50), backgroundColor: fill, paddingHorizontal: dp(8), paddingVertical: dp(3) }}>
                  <Text numberOfLines={1} style={{ color: ink, fontSize: dp(12), lineHeight: dp(15), fontWeight: '700' }}>{props.tag}</Text>
                </View>
              );
            })() : null}
            {badge != null && badge > 1 ? (
              <View style={{ position: 'absolute', top: dp(6), right: dp(6), width: dp(26), height: dp(26), borderRadius: dp(13), backgroundColor: accent.color, alignItems: 'center', justifyContent: 'center' }}>
                <Text numberOfLines={1} style={{ color: accent.on, fontSize: dp(badgeText(badge).length <= 2 ? 14 : 11), lineHeight: dp(18), fontWeight: '700', textAlign: 'center' }}>{badgeText(badge)}</Text>
              </View>
            ) : null}
            {props.progress != null ? <ProgressStrip fraction={props.progress} /> : null}
          </CardRing>
          {/* Every card exactly the same height, so down lands on the row below. */}
          <View style={{ height: POSTER_CAPTION_HEIGHT, paddingTop: dp(7) }}>
            <Text numberOfLines={1} style={{ color: focused ? color.chalk : color.muted, fontSize: dp(14), lineHeight: dp(18) }}>{props.title}</Text>
            <Text numberOfLines={1} style={{ color: color.faint, fontSize: dp(14), lineHeight: dp(18) }}>{props.subtitle ?? ''}</Text>
          </View>
        </CardLift>
      )}
    </Press>
  );
}

/** The landscape card for Continue Watching, where which episode matters more than the poster. */
export function WideCard(props: { title: string; subtitle?: string | null; image?: string | null; progress?: number | null; onPress: () => void; onHold?: () => void; onFocus?: () => void; preferred?: boolean; first?: boolean; focusKey?: string }) {
  const [focused, setFocused] = useState(false);
  return (
    <Press onPress={props.onPress} onHold={props.onHold} preferred={props.preferred} first={props.first} focusKey={props.focusKey}
      onFocusChange={(on) => { setFocused(on); if (on) props.onFocus?.(); }}>
      {() => (
        <CardLift focused={focused} style={{ width: dp(268), padding: dp(4) }}>
          <CardRing focused={focused} corner={WIDE_CORNER} style={{ width: '100%', aspectRatio: 16 / 9 }}>
            <Art uri={props.image} />
            {props.progress != null ? <ProgressStrip fraction={props.progress} /> : null}
          </CardRing>
          <Text numberOfLines={1} style={{ color: focused ? color.chalk : color.muted, fontSize: dp(14), lineHeight: dp(18), marginTop: dp(8) }}>{props.title}</Text>
          {props.subtitle ? <Text numberOfLines={1} style={{ color: color.faint, fontSize: dp(14), lineHeight: dp(19) }}>{props.subtitle}</Text> : null}
        </CardLift>
      )}
    </Press>
  );
}

/** An episode in a rail: still, number, title, runtime. The page's buttons act on the selected one. */
export function EpisodeTile(props: {
  number: string; title: string; duration?: string | null; image?: string | null; progress?: number | null; watched: boolean;
  onFocus: () => void; onPress: () => void; onHold?: () => void; selected?: boolean; preferred?: boolean; first?: boolean; focusKey?: string;
}) {
  const [focused, setFocused] = useState(false);
  const marked = focused || props.selected;
  const label = [props.number.trim() ? props.number : null, props.title].filter(Boolean).join('. ');
  return (
    <Press onPress={props.onPress} onHold={props.onHold} preferred={props.preferred} first={props.first} focusKey={props.focusKey}
      onFocusChange={(on) => { setFocused(on); if (on) props.onFocus(); }}>
      {() => (
        <CardLift focused={focused} style={{ width: dp(186), padding: dp(4) }}>
          <CardRing focused={focused} corner={WIDE_CORNER} style={{ width: '100%', aspectRatio: 16 / 9 }}>
            <Art uri={props.image} />
            {props.selected && !focused ? (
              <View pointerEvents="none" style={{ position: 'absolute', left: 0, right: 0, top: 0, bottom: 0, borderRadius: WIDE_CORNER, borderWidth: dp(2), borderColor: 'rgba(242, 244, 247, 0.5)' }} />
            ) : null}
            {props.watched ? <Watched /> : null}
            {props.progress != null ? <ProgressStrip fraction={props.progress} /> : null}
          </CardRing>
          <View style={{ height: EPISODE_CAPTION_HEIGHT, paddingTop: dp(7) }}>
            <Text numberOfLines={1} style={{ color: marked ? color.chalk : color.muted, fontSize: dp(14), lineHeight: dp(18) }}>{label}</Text>
            <Text numberOfLines={1} style={{ color: color.faint, fontSize: dp(14), lineHeight: dp(18) }}>{props.duration ?? ''}</Text>
          </View>
        </CardLift>
      )}
    </Press>
  );
}

/** A face from the cast. Focusable even with nowhere to go, so the row can be reached. */
export function CastCircle(props: { name: string; role?: string | null; image?: string | null; onPress?: () => void }) {
  const [focused, setFocused] = useState(false);
  return (
    <Press onPress={props.onPress} onFocusChange={setFocused} style={{ width: dp(108), alignItems: 'center' }}>
      {() => (
        <>
          <View style={{ width: dp(84), height: dp(84), borderRadius: dp(42), overflow: 'hidden', backgroundColor: color.surfaceHigh, borderWidth: focused ? dp(3) : 0, borderColor: color.chalk, alignItems: 'center', justifyContent: 'center' }}>
            {props.image ? <Image source={{ uri: props.image }} resizeMode="cover" style={{ width: '100%', height: '100%' }} />
              : <Text style={{ color: color.faint, fontSize: dp(26), lineHeight: dp(32) }}>{props.name.slice(0, 1).toUpperCase()}</Text>}
          </View>
          <Text numberOfLines={2} style={{ color: color.chalk, fontSize: dp(14), lineHeight: dp(19), textAlign: 'center', marginTop: dp(8) }}>{props.name}</Text>
          {props.role ? <Text numberOfLines={1} style={{ color: color.faint, fontSize: dp(14), lineHeight: dp(18), textAlign: 'center' }}>{props.role}</Text> : null}
        </>
      )}
    </Press>
  );
}

/** A channel's own colour behind its logo, the same each time (Surfaces.kt). */
export function channelTint(id: string): string {
  const palette = ['#2C3563', '#7A3B42', '#2F5E52', '#6A4E2A', '#473469', '#2B5066', '#6B3559', '#3F5A2E'];
  let hash = 7;
  for (const c of id) hash = (Math.imul(hash, 31) + c.charCodeAt(0)) | 0;
  return palette[((hash % palette.length) + palette.length) % palette.length];
}

/** A channel's name without a provider's region or group prefix ("UK | ", "[DE] "). */
export function channelLabel(name: string): string {
  const trimmed = name.trim();
  return trimmed.replace(/^(\[[^\]]{1,6}\]|[A-Z0-9]{2,4}\s*[|:\-])\s*/, '') || trimmed;
}

/** A live channel as a search result: its logo, or its name on its own colour. */
export function ChannelCard(props: { name: string; number: number; logo?: string | null; onPress: () => void }) {
  const accent = useAccent();
  const [focused, setFocused] = useState(false);
  return (
    <Press onPress={props.onPress} onFocusChange={setFocused} style={{ width: dp(132), transform: [{ scale: focused ? 1.05 : 1 }] }}>
      {() => (
        <>
          <View style={{ height: dp(74), borderRadius: dp(12), backgroundColor: channelTint(props.name), borderWidth: dp(2), borderColor: focused ? accent.color : 'transparent', alignItems: 'center', justifyContent: 'center' }}>
            {props.logo ? <Image source={{ uri: props.logo }} resizeMode="contain" style={{ width: dp(56), height: dp(56), borderRadius: dp(6) }} />
              : <Text numberOfLines={2} style={{ color: color.chalk, fontSize: dp(15), lineHeight: dp(19), fontWeight: '700', textAlign: 'center', paddingHorizontal: dp(10) }}>{channelLabel(props.name)}</Text>}
          </View>
          <View style={{ height: dp(40), paddingTop: dp(6) }}>
            {props.logo ? <Text numberOfLines={1} style={{ color: focused ? color.chalk : color.muted, fontSize: dp(14), lineHeight: dp(18) }}>{props.name}</Text> : null}
            <Text numberOfLines={1} style={{ color: color.faint, fontSize: dp(14), lineHeight: dp(19) }}>{props.number > 0 ? `Channel ${props.number}` : 'Live'}</Text>
          </View>
        </>
      )}
    </Press>
  );
}
