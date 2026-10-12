import React, { useEffect, useState } from 'react';
import { Image, View } from 'react-native';
import Svg, { Path } from '@amazon-devices/react-native-svg';
import { TVFocusGuideView } from '@amazon-devices/react-native-kepler';
import type { PlexHomeUser } from '../api/plex';
import { Press, useAccent, pillColors } from './focus';
import { GearGlyph, SearchGlyph } from './glyphs';
import { BACKDROP_RISE, Gradient } from './surfaces';
import { Text } from './text';
import { color, dp } from './theme';

/*
 * The bar along the top (ReelyApp.kt's TopBar): the mark, the tabs together in a frosted
 * capsule, whose profile this is, Settings' gear in a capsule of its own, and the time.
 * It floats over the page, so a title's backdrop runs to the top of the screen behind it.
 */

export type TabId = 'search' | 'home' | 'movie' | 'show' | 'live' | 'requests' | 'settings';

export const TABS: Array<{ id: TabId; label: string; icon?: 'search' }> = [
  { id: 'search', label: 'Search', icon: 'search' },
  { id: 'home', label: 'Home' },
  { id: 'movie', label: 'Movies' },
  { id: 'show', label: 'TV Shows' },
  { id: 'live', label: 'Live TV' },
  { id: 'requests', label: 'Request' },
];

/** The mark, as on the launcher, in the accent chosen in Settings. */
export function Mark(props: { height: number }) {
  const accent = useAccent();
  const h = props.height;
  return (
    <Svg width={(h * 22) / 46} height={h} viewBox="43.1 30 22 46">
      <Path fill={accent.color} d="M43.19 66V30H52.72L52.99 37.19Q54.07 33.43 56.15 31.71Q58.23 30 61.52 30H64.81V38.33H61.52Q57.29 38.33 55.28 40.04Q53.26 41.75 53.26 45.78V66Z" />
      <Path fill={accent.color} d="M47.5,72h13a2,2 0 0 1 2,2v0a2,2 0 0 1 -2,2h-13a2,2 0 0 1 -2,-2v0a2,2 0 0 1 2,-2z" />
    </Svg>
  );
}

/** The time, quietly, at the far right. */
function Clock() {
  const [now, setNow] = useState(() => new Date());
  useEffect(() => {
    const t = setInterval(() => setNow(new Date()), 20_000);
    return () => clearInterval(t);
  }, []);
  const h = now.getHours();
  const label = `${h % 12 || 12}:${String(now.getMinutes()).padStart(2, '0')} ${h < 12 ? 'AM' : 'PM'}`;
  return <Text style={{ color: color.faint, fontSize: dp(16), lineHeight: dp(20), marginLeft: dp(18) }}>{label}</Text>;
}

/**
 * A tab. A white pill means focus, here as everywhere; the page you're on is bold text
 * over a short line in the accent, a different shape entirely, so it isn't read as focus.
 */
const NavTab = React.forwardRef<View, { label: string; icon?: 'search' | 'gear'; selected: boolean; onFocus: () => void; onPress: () => void; onLayout?: (x: number, width: number) => void }>(
  function NavTab(props, ref) {
    const accent = useAccent();
    const [focused, setFocused] = useState(false);
    const tint = focused ? color.ink : props.selected ? color.chalk : color.muted;
    return (
      <Press ref={ref} onPress={props.onPress} onFocusChange={(on) => { setFocused(on); if (on) props.onFocus(); }}
        onLayout={(e) => props.onLayout?.(e.nativeEvent.layout.x, e.nativeEvent.layout.width)}
        style={{ borderRadius: 999, backgroundColor: focused ? color.chalk : 'transparent', paddingHorizontal: dp(props.icon ? 13 : 18), paddingVertical: dp(9) }}>
        {() => (
          <>
            {props.icon === 'search' ? <SearchGlyph color={tint} size={20} />
              : props.icon === 'gear' ? <GearGlyph color={tint} size={20} />
              : <Text style={{ color: tint, fontSize: dp(16), lineHeight: dp(21), fontWeight: props.selected ? '600' : '400' }}>{props.label}</Text>}
            {props.selected && !focused ? (
              <View style={{ position: 'absolute', left: '50%', bottom: dp(2), width: dp(18), height: dp(3), marginLeft: -dp(9), borderRadius: dp(1.5), backgroundColor: accent.color }} />
            ) : null}
          </>
        )}
      </Press>
    );
  },
);

/** Someone's picture, or their initial on a colour of their own. */
export function Avatar(props: { user: PlexHomeUser; size: number }) {
  const s = dp(props.size);
  const palette = ['#2C3563', '#7A3B42', '#2F5E52', '#6A4E2A', '#473469', '#2B5066'];
  const tint = palette[Math.abs([...props.user.title].reduce((h, c) => h * 31 + c.charCodeAt(0), 7)) % palette.length];
  return (
    <View style={{ width: s, height: s, borderRadius: s / 2, overflow: 'hidden', backgroundColor: tint, alignItems: 'center', justifyContent: 'center' }}>
      {props.user.thumb ? <Image source={{ uri: props.user.thumb }} style={{ width: s, height: s }} />
        : <Text style={{ color: color.chalk, fontSize: s * 0.45, fontWeight: '600' }}>{props.user.title.slice(0, 1).toUpperCase()}</Text>}
    </View>
  );
}

/** Whose profile this is, top right. With others to switch to it opens "Who's watching?". */
function ProfileChip(props: { user: PlexHomeUser; onPress: (() => void) | null; onFocus: () => void }) {
  const accent = useAccent();
  const [focused, setFocused] = useState(false);
  const c = pillColors(focused, accent.color);
  const body = (
    <>
      <Avatar user={props.user} size={28} />
      <Text numberOfLines={1} style={{ color: focused ? c.text : color.muted, fontSize: dp(15), lineHeight: dp(19), fontWeight: focused ? '600' : '500' }}>{props.user.title}</Text>
    </>
  );
  const style = { flexDirection: 'row' as const, alignItems: 'center' as const, gap: dp(10), borderRadius: 999, paddingLeft: dp(6), paddingRight: dp(14), paddingVertical: dp(5), marginRight: dp(6) };
  if (!props.onPress) return <View style={style}>{body}</View>;
  return (
    <Press onPress={props.onPress} onFocusChange={(on) => { setFocused(on); if (on) props.onFocus(); }} style={[style, { backgroundColor: focused ? c.fill : 'transparent' }]}>
      {() => body}
    </Press>
  );
}

const capsule = {
  borderRadius: 999,
  backgroundColor: 'rgba(21, 23, 27, 0.78)',
  borderWidth: dp(1),
  borderColor: 'rgba(255, 255, 255, 0.08)',
  padding: dp(4),
};

export interface TopBarProps {
  current: TabId;
  tabRefs: Record<TabId, React.RefObject<View | null>>;
  onFocusTab: (tab: TabId) => void;
  onPressTab: (tab: TabId) => void;
  onTabLayout: (tab: TabId, x: number) => void;
  profile: PlexHomeUser | null;
  onProfile: (() => void) | null;
}

export function TopBar(props: TopBarProps) {
  const [capsuleX, setCapsuleX] = useState(0);
  const currentRef = props.tabRefs[props.current];
  return (
    // Up from a page lands on that page's own tab, not whichever is nearest.
    <TVFocusGuideView destinations={currentRef.current ? [currentRef.current] : []}
      style={{ position: 'absolute', left: 0, right: 0, top: 0, height: BACKDROP_RISE, zIndex: 10 }}>
      <Gradient id="topShade" vertical stops={[[0, color.ink, 0.7], [1, color.ink, 0]]} />
      <View style={{ flex: 1, flexDirection: 'row', alignItems: 'center', gap: dp(6), paddingHorizontal: dp(40) }}>
        <View style={{ marginRight: dp(22) }}><Mark height={dp(30)} /></View>
        <View style={[capsule, { flexDirection: 'row', alignItems: 'center', gap: dp(2) }]} onLayout={(e) => setCapsuleX(e.nativeEvent.layout.x)}>
          {TABS.map((tab) => (
            <NavTab key={tab.id} ref={props.tabRefs[tab.id]} label={tab.label} icon={tab.icon} selected={props.current === tab.id}
              onFocus={() => props.onFocusTab(tab.id)} onPress={() => props.onPressTab(tab.id)}
              onLayout={(x) => props.onTabLayout(tab.id, capsuleX + dp(4) + x)} />
          ))}
        </View>
        <View style={{ flex: 1 }} />
        {props.profile ? <ProfileChip user={props.profile} onPress={props.onProfile} onFocus={() => props.onFocusTab(props.current)} /> : null}
        <View style={capsule}>
          <NavTab ref={props.tabRefs.settings} label="Settings" icon="gear" selected={props.current === 'settings'}
            onFocus={() => props.onFocusTab('settings')} onPress={() => props.onPressTab('settings')} />
        </View>
        <Clock />
      </View>
    </TVFocusGuideView>
  );
}
