import React, { createContext, useContext, useEffect, useRef, useState } from 'react';
import { Animated, Easing, Pressable, View, type PressableProps, type StyleProp, type ViewStyle } from 'react-native';
import { FocusManager } from '@amazon-devices/react-native-kepler';
import { color, dp } from './theme';

/*
 * How focus looks and behaves, as the Fire TV app has it (FocusStyle.kt, SelectPress.kt).
 *
 * On a card, focus is light rather than an outline: the card lifts, a thin white ring
 * hugs the artwork, and it glows from behind in the colour of what's on screen. The rest
 * of its row steps back. On a button or a chip, focus is a white pill with dark text.
 */

export const LIFT = 1.08;
const STEPPED_BACK = 0.72;
const RING = dp(2);
const GLOW = dp(14);
const GLOW_STEPS = 12;
const GLOW_ALPHA = 0.06;
/** How long OK is held before it counts as held: the system's long-press time. */
export const HOLD_MS = 500;

/** The colour of what's on screen, for the glow behind a focused card; the accent until it's known. */
export const TintContext = createContext<string | null>(null);
export const AccentContext = createContext<{ color: string; on: string }>({ color: '#2E6BFF', on: '#FFFFFF' });

export const useAccent = () => useContext(AccentContext);

/** Whether something in the enclosing row has focus, so the rest of it can step back. */
const RowFocusContext = createContext<{ focused: boolean; set: (on: boolean) => void } | null>(null);

/** A row or grid whose unfocused cards step back while one of them has focus. */
export function FocusRow(props: { children: React.ReactNode }) {
  const [focused, setFocused] = useState(false);
  // Focus moving card to card blurs one before focusing the next: a moment's grace, so
  // the row doesn't flicker back to full while it does.
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const set = (on: boolean) => {
    if (timer.current) clearTimeout(timer.current);
    if (on) setFocused(true);
    else timer.current = setTimeout(() => setFocused(false), 30);
  };
  useEffect(() => () => { if (timer.current) clearTimeout(timer.current); }, []);
  return <RowFocusContext.Provider value={{ focused, set }}>{props.children}</RowFocusContext.Provider>;
}

/** A value that eases to [target] over 200 ms, as the Fire TV's animate*AsState do. */
function useEased(target: number): Animated.Value {
  const value = useRef(new Animated.Value(target)).current;
  useEffect(() => {
    Animated.timing(value, { toValue: target, duration: 200, easing: Easing.out(Easing.quad), useNativeDriver: true }).start();
  }, [target, value]);
  return value;
}

/**
 * Where the cursor was on one screen, kept while the screen isn't showing (ScreenFocus in
 * the Fire TV app). Coming back to it puts the cursor on the card it was on; arriving
 * somewhere new puts it on the screen's first thing, unless the cursor came along the
 * tabs, which keeps it up there.
 */
export class ScreenMemory {
  /** The key of the last thing focused on the screen. */
  last: string | null = null;
  /** Coming back: the cursor goes to [last]. */
  restoring = false;
  /** Arriving: the cursor goes to the screen's first thing. */
  claim = true;
  /** Told whenever something on the screen takes the cursor. */
  onFocus: () => void = () => undefined;
}

export const ScreenContext = createContext<ScreenMemory | null>(null);

/** Told when anything inside takes the cursor: a row, to bring itself into view. */
export const FocusWithinContext = createContext<(() => void) | null>(null);

/** Whether the thing keyed [key] should take the cursor as it appears. */
export function usePreferred(key: string | undefined, first: boolean | undefined, explicit: boolean | undefined): boolean {
  const screen = useContext(ScreenContext);
  if (explicit !== undefined) return explicit;
  if (!screen) return false;
  if (screen.restoring && screen.last) return key !== undefined && key === screen.last;
  return !!first && screen.claim;
}

export interface PressProps extends Omit<PressableProps, 'style' | 'children' | 'onLongPress'> {
  /** Names it on its screen, so coming back to the screen finds it again. */
  focusKey?: string;
  /** The screen's first thing: where the cursor goes on arriving. */
  first?: boolean;
  onPress?: () => void;
  /** OK held. The press that follows a hold doesn't also count as a press. */
  onHold?: () => void;
  onFocusChange?: (focused: boolean) => void;
  style?: StyleProp<ViewStyle>;
  children: (focused: boolean) => React.ReactNode;
  preferred?: boolean;
}

/** Something the remote can land on and press, telling its content whether it has focus. */
export const Press = React.forwardRef<View, PressProps>(function Press(props, ref) {
  const { onPress, onHold, onFocusChange, style, children, focusKey, first, preferred: _explicit, ...rest } = props;
  const screen = useContext(ScreenContext);
  const within = useContext(FocusWithinContext);
  const preferred = usePreferred(focusKey, first, props.preferred);
  const [focused, setFocused] = useState(false);
  return (
    <Pressable
      ref={ref}
      accessibilityRole="button"
      {...rest}
      hasTVPreferredFocus={preferred}
      // Marked as well, for the browser preview, which has no preferred focus of its own.
      nativeID={preferred ? 'tv-preferred' : rest.nativeID}
      style={style}
      onPress={onPress}
      onLongPress={onHold}
      delayLongPress={HOLD_MS}
      onFocus={() => {
        setFocused(true);
        if (screen) {
          if (focusKey !== undefined) screen.last = focusKey;
          screen.restoring = false;
          screen.onFocus();
        }
        within?.();
        onFocusChange?.(true);
      }}
      onBlur={() => {
        setFocused(false);
        onFocusChange?.(false);
      }}
    >
      {children(focused)}
    </Pressable>
  );
});

/**
 * A card's outermost layout: the lift, and stepping back while a neighbour in its row has
 * focus. [children] is drawn inside.
 */
export function CardLift(props: { focused: boolean; style?: StyleProp<ViewStyle>; children: React.ReactNode }) {
  const row = useContext(RowFocusContext);
  useEffect(() => {
    if (props.focused) row?.set(true);
    else row?.set(false);
  }, [props.focused]);
  const stepped = !props.focused && (row?.focused ?? false);
  const scale = useEased(props.focused ? LIFT : 1);
  const opacity = useEased(stepped ? STEPPED_BACK : 1);
  return (
    <Animated.View style={[props.style, { transform: [{ scale }], opacity, zIndex: props.focused ? 1 : 0 }]}>
      {props.children}
    </Animated.View>
  );
}

/**
 * Around a card's artwork: the glow behind it and the ring around it, both outside its
 * bounds. The glow is a few stacked outlines, each wider and fainter, as the Fire TV
 * draws it.
 */
export function CardRing(props: { focused: boolean; corner: number; round?: boolean; style?: StyleProp<ViewStyle>; children: React.ReactNode }) {
  const accent = useAccent();
  const tint = useContext(TintContext) ?? accent.color;
  const on = useEased(props.focused ? 1 : 0);
  const { corner } = props;
  return (
    <View style={props.style}>
      {props.focused && !props.round
        ? Array.from({ length: GLOW_STEPS }, (_, n) => {
            const i = n + 1;
            const spread = (GLOW * i) / GLOW_STEPS;
            return (
              <View
                key={i}
                pointerEvents="none"
                style={{
                  position: 'absolute',
                  left: -spread,
                  right: -spread,
                  top: -spread * 0.6,
                  bottom: -spread,
                  borderRadius: corner + spread,
                  backgroundColor: tint,
                  opacity: GLOW_ALPHA * (1 - i / (GLOW_STEPS + 1)),
                }}
              />
            );
          })
        : null}
      <View style={{ flex: 1, borderRadius: props.round ? 9999 : corner, overflow: 'hidden', backgroundColor: color.surfaceHigh }}>
        {props.children}
      </View>
      <Animated.View
        pointerEvents="none"
        style={{
          position: 'absolute',
          left: -RING,
          right: -RING,
          top: -RING,
          bottom: -RING,
          borderRadius: props.round ? 9999 : corner + RING,
          borderWidth: RING,
          borderColor: color.chalk,
          opacity: on,
        }}
      />
    </View>
  );
}

/** A pill's fill and text for each state: focused white, emphasised in the accent, selected brighter, else faint. */
export function pillColors(focused: boolean, accent: string, selected = false, emphasised = false): { fill: string; text: string } {
  if (focused) return { fill: color.chalk, text: color.ink };
  if (emphasised) return { fill: withAlphaHex(accent, 0.26), text: color.chalk };
  if (selected) return { fill: 'rgba(242, 244, 247, 0.16)', text: color.chalk };
  return { fill: 'rgba(242, 244, 247, 0.08)', text: color.muted };
}

function withAlphaHex(hex: string, alpha: number): string {
  const [r, g, b] = [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16));
  return `rgba(${r}, ${g}, ${b}, ${alpha})`;
}

/**
 * The cursor onto [target], trying for a few frames until it's there to take it: asked
 * in the same pass that draws it, a single request is lost (Focus.kt's requestWhenReady).
 */
export function focusWhenReady(target: React.RefObject<View | null>, attempts = 16) {
  let left = attempts;
  const attempt = () => {
    const el = target.current;
    if (el) {
      try {
        FocusManager.focus(el as unknown as Parameters<typeof FocusManager.focus>[0]);
        return;
      } catch {
        // Not laid out yet.
      }
    }
    if (--left > 0) setTimeout(attempt, 32);
  };
  attempt();
}
