import React, { createContext, useContext, useEffect, useRef, useState } from 'react';
import { Animated, BackHandler, Easing, Image, ScrollView, View, type StyleProp, type ViewStyle } from 'react-native';
import { Text } from './text';
import { TVFocusGuideView, useTVEventHandler } from '@amazon-devices/react-native-kepler';
import { Press, useAccent } from './focus';
import { CheckGlyph } from './glyphs';
import { Overlay } from './overlay';
import { Gradient } from './surfaces';
import { color, dp, type } from './theme';

/*
 * The one way the app draws a menu (Menus.kt): a panel sliding in from the right edge,
 * the full height of the screen, over a shade that darkens towards it, with a plain list
 * of full-width rows. The row the cursor is on turns white with dark text. Back closes it.
 */

const PANEL_FILL = 'rgba(16, 18, 22, 0.97)';
export const MENU_WIDTH = dp(460);

/** Back, for whatever's showing: the newest handler that says it took the press wins. */
export function useBack(handler: () => boolean, active = true) {
  const latest = useRef(handler);
  latest.current = handler;
  useEffect(() => {
    if (!active) return;
    const sub = BackHandler.addEventListener('hardwareBackPress', () => latest.current());
    return () => sub.remove();
  }, [active]);
}

/**
 * Whether OK has been pressed afresh since the menu opened. Most menus open on a held OK
 * and arrive with it still down: its release mustn't press the first row. Vega doesn't
 * always pass OK's going down to the app, so a moment after opening counts as afresh too.
 */
const FreshPress = createContext<() => boolean>(() => true);

/** How long after a menu opens a release of OK is still the one that opened it. */
const HELD_RELEASE_MS = 700;

export function MenuScrim(props: { strength?: number }) {
  const s = props.strength ?? 1;
  return <Gradient id="menuScrim" stops={[[0, color.ink, 0.25 * s], [0.55, color.ink, 0.55 * s], [1, color.ink, 0.9 * s]]} />;
}

/**
 * A menu over the screen: the shade, and the panel on the right with the cursor kept in
 * it. [onClose] is Back.
 */
export function MenuLayer(props: { onClose: () => void; width?: number; scrim?: number; children: React.ReactNode; style?: StyleProp<ViewStyle> }) {
  const arrival = useRef(new Animated.Value(1)).current;
  useEffect(() => {
    Animated.timing(arrival, { toValue: 0, duration: 220, easing: Easing.bezier(0.4, 0, 0.2, 1), useNativeDriver: true }).start();
  }, [arrival]);
  useBack(() => {
    props.onClose();
    return true;
  });
  const fresh = useRef(false);
  const openedAt = useRef(Date.now());
  useTVEventHandler((event) => {
    if ((event.eventType === 'select' || event.eventType === 'enter') && event.eventKeyAction === 0) fresh.current = true;
  });
  const pressedAfresh = () => fresh.current || Date.now() - openedAt.current > HELD_RELEASE_MS;
  const width = props.width ?? MENU_WIDTH;
  return (
    <Overlay>
    <View style={{ position: 'absolute', left: 0, right: 0, top: 0, bottom: 0, zIndex: 50 }}>
      <MenuScrim strength={props.scrim} />
      <Animated.View
        style={[{
          position: 'absolute', right: 0, top: 0, bottom: 0, width, backgroundColor: PANEL_FILL,
          borderLeftWidth: dp(1), borderLeftColor: color.glassEdge,
          opacity: arrival.interpolate({ inputRange: [0, 1], outputRange: [1, 0] }),
          transform: [{ translateX: arrival.interpolate({ inputRange: [0, 1], outputRange: [0, dp(64)] }) }],
        }, props.style]}>
        <FreshPress.Provider value={pressedAfresh}>
          <TVFocusGuideView autoFocus trapFocusUp trapFocusDown trapFocusLeft trapFocusRight style={{ flex: 1 }}>
            <ScrollView contentContainerStyle={{ paddingHorizontal: dp(28), paddingVertical: dp(28), gap: dp(4) }}>
              {props.children}
            </ScrollView>
          </TVFocusGuideView>
        </FreshPress.Provider>
      </Animated.View>
    </View>
    </Overlay>
  );
}

/** A menu's title and, under it, what it's about. */
export const MenuHeading = (props: { title: string; subtitle?: string | null }) => (
  <View style={{ paddingLeft: dp(4), paddingBottom: dp(14), gap: dp(4) }}>
    <Text numberOfLines={2} style={type.headline}>{props.title}</Text>
    {props.subtitle ? <Text numberOfLines={2} style={type.meta}>{props.subtitle}</Text> : null}
  </View>
);

/** A title at the top of its menu: a small picture, how far along it is, its logo or name, and what it is. */
export function MenuArtHeader(props: { title: string; meta?: string | null; image?: string | null; logo?: string | null; progress?: number | null }) {
  const accent = useAccent();
  return (
    <View style={{ flexDirection: 'row', alignItems: 'center', gap: dp(16), paddingLeft: dp(4), paddingBottom: dp(16) }}>
      <View style={{ width: dp(150), aspectRatio: 16 / 9, borderRadius: dp(10), overflow: 'hidden', backgroundColor: 'rgba(242, 244, 247, 0.06)' }}>
        {props.image ? <Image source={{ uri: props.image }} resizeMode="cover" style={{ width: '100%', height: '100%' }} /> : null}
        {props.progress != null && props.progress > 0 ? (
          <View style={{ position: 'absolute', left: 0, right: 0, bottom: 0, height: dp(4), backgroundColor: 'rgba(8, 9, 11, 0.6)' }}>
            <View style={{ height: '100%', width: `${Math.min(1, props.progress) * 100}%`, backgroundColor: accent.color }} />
          </View>
        ) : null}
      </View>
      <View style={{ flex: 1, gap: dp(6) }}>
        {props.logo ? <Image source={{ uri: props.logo }} resizeMode="contain" style={{ height: dp(36), width: '100%' }} />
          : <Text numberOfLines={2} style={type.headline}>{props.title}</Text>}
        {props.meta ? <Text numberOfLines={2} style={type.meta}>{props.meta}</Text> : null}
      </View>
    </View>
  );
}

/** A small heading inside a menu, over the rows that belong to it. */
export const MenuSection = (props: { label: string }) => (
  <Text style={[type.section, { paddingLeft: dp(16), paddingTop: dp(16), paddingBottom: dp(6) }]}>{props.label}</Text>
);

/** One row of a menu: a glyph, the words, and a value or a check on the right. */
export function MenuItem(props: {
  label: string; onPress: () => void; icon?: (tint: string) => React.ReactNode; detail?: string | null; value?: string | null;
  checked?: boolean; onFocus?: () => void; preferred?: boolean;
}) {
  const accent = useAccent();
  const fresh = useContext(FreshPress);
  const [focused, setFocused] = useState(false);
  const text = focused ? color.ink : 'rgba(242, 244, 247, 0.92)';
  const quiet = focused ? 'rgba(8, 9, 11, 0.62)' : color.muted;
  return (
    <Press
      preferred={props.preferred}
      onPress={() => { if (fresh()) props.onPress(); }}
      onFocusChange={(on) => { setFocused(on); if (on) props.onFocus?.(); }}
      style={{
        minHeight: dp(50), flexDirection: 'row', alignItems: 'center', gap: dp(16), borderRadius: dp(12),
        paddingHorizontal: dp(16), paddingVertical: dp(10), backgroundColor: focused ? color.chalk : 'transparent',
        transform: [{ scale: focused ? 1.02 : 1 }],
      }}>
      {() => (
        <>
          {props.icon ? <View style={{ minWidth: dp(24), minHeight: dp(24), alignItems: 'center', justifyContent: 'center' }}>{props.icon(focused ? color.ink : 'rgba(242, 244, 247, 0.8)')}</View> : null}
          <View style={{ flex: 1, gap: dp(2) }}>
            <Text numberOfLines={2} style={{ color: text, fontSize: dp(18), lineHeight: dp(23), fontWeight: props.checked || focused ? '600' : '500' }}>{props.label}</Text>
            {props.detail ? <Text numberOfLines={2} style={[type.label, { color: quiet }]}>{props.detail}</Text> : null}
          </View>
          {props.value != null ? <Text numberOfLines={1} style={[type.meta, { color: quiet }]}>{props.value}</Text> : null}
          {props.checked ? <CheckGlyph color={focused ? color.ink : accent.color} size={20} /> : null}
        </>
      )}
    </Press>
  );
}
