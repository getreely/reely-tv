import React, { useState } from 'react';
import { Animated, Image, TextInput, View, type StyleProp, type ViewStyle } from 'react-native';
import { Text } from './text';
import { Press, pillColors, useAccent } from './focus';
import { color, dp, type } from './theme';

/*
 * The Fire TV app's controls (TvControls.kt): a button, a chip, a list row, a text field,
 * and the small notes and headings between them. One look for focus everywhere: a white
 * pill with dark text.
 */

export function ActionButton(props: { label: string; onPress: () => void; emphasised?: boolean; preferred?: boolean; first?: boolean; focusKey?: string; style?: StyleProp<ViewStyle>; onFocus?: () => void; pressRef?: React.Ref<View> }) {
  const accent = useAccent();
  const [focused, setFocused] = useState(false);
  const c = pillColors(focused, accent.color, false, props.emphasised);
  return (
    <Press ref={props.pressRef} onPress={props.onPress} preferred={props.preferred} first={props.first} focusKey={props.focusKey} onFocusChange={(on) => { setFocused(on); if (on) props.onFocus?.(); }}
      style={[{ borderRadius: 999, backgroundColor: c.fill, paddingHorizontal: dp(22), paddingVertical: dp(12), alignItems: 'center', justifyContent: 'center', transform: [{ scale: focused ? 1.04 : 1 }] }, props.style]}>
      {() => <Text numberOfLines={1} style={{ color: c.text, fontSize: dp(16), lineHeight: dp(20), fontWeight: focused ? '600' : '500' }}>{props.label}</Text>}
    </Press>
  );
}

export function Chip(props: { label: string; selected?: boolean; onPress: () => void; onHold?: () => void; preferred?: boolean; first?: boolean; focusKey?: string; onFocus?: () => void; style?: StyleProp<ViewStyle>; pressRef?: React.Ref<View> }) {
  const accent = useAccent();
  const [focused, setFocused] = useState(false);
  const c = pillColors(focused, accent.color, props.selected);
  return (
    <Press ref={props.pressRef} onPress={props.onPress} onHold={props.onHold} preferred={props.preferred} first={props.first} focusKey={props.focusKey} onFocusChange={(on) => { setFocused(on); if (on) props.onFocus?.(); }}
      style={[{ borderRadius: 999, backgroundColor: c.fill, paddingHorizontal: dp(18), paddingVertical: dp(9) }, props.style]}>
      {() => <Text numberOfLines={1} style={{ color: c.text, fontSize: dp(16), lineHeight: dp(20), fontWeight: props.selected || focused ? '600' : '500' }}>{props.label}</Text>}
    </Press>
  );
}

/** A list row, for categories and channels where a poster grid would waste the screen. */
export function ListRow(props: { title: string; subtitle?: string | null; image?: string | null; selected?: boolean; onPress: () => void; onFocus?: () => void; preferred?: boolean; first?: boolean; focusKey?: string }) {
  const accent = useAccent();
  const [focused, setFocused] = useState(false);
  const c = pillColors(focused, accent.color, props.selected);
  return (
    <Press onPress={props.onPress} preferred={props.preferred} first={props.first} focusKey={props.focusKey} onFocusChange={(on) => { setFocused(on); if (on) props.onFocus?.(); }}
      style={{ flexDirection: 'row', alignItems: 'center', borderRadius: dp(12), backgroundColor: focused || props.selected ? c.fill : 'transparent', paddingHorizontal: dp(16), paddingVertical: dp(10), gap: dp(12) }}>
      {() => (
        <>
          {props.image ? <Image source={{ uri: props.image }} resizeMode="contain" style={{ width: dp(48), height: dp(36), borderRadius: dp(4) }} /> : null}
          <View style={{ flex: 1 }}>
            <Text numberOfLines={1} style={[type.meta, { color: c.text }]}>{props.title}</Text>
            {props.subtitle ? <Text numberOfLines={1} style={[type.label, { color: focused ? 'rgba(8, 9, 11, 0.7)' : color.faint }]}>{props.subtitle}</Text> : null}
          </View>
          {props.selected ? <View style={{ width: dp(8), height: dp(8), borderRadius: dp(4), backgroundColor: focused ? color.ink : accent.color }} /> : null}
        </>
      )}
    </Press>
  );
}

/**
 * A text field. The system keyboard opens when it's chosen, as Vega's own fields do; the
 * outline says where the cursor is, white while typing.
 */
export function TextField(props: {
  label: string; value: string; onChange: (v: string) => void; placeholder?: string; password?: boolean;
  keyboard?: 'default' | 'url' | 'numeric' | 'email-address'; onSubmit?: () => void; preferred?: boolean; first?: boolean; focusKey?: string; style?: StyleProp<ViewStyle>;
}) {
  const accent = useAccent();
  const [focused, setFocused] = useState(false);
  return (
    <View style={props.style}>
      <Text style={{ color: color.muted, fontSize: dp(14), marginBottom: dp(6) }}>{props.label}</Text>
      <TextInput
        value={props.value}
        onChangeText={props.onChange}
        placeholder={props.placeholder}
        placeholderTextColor={color.faint}
        secureTextEntry={props.password}
        keyboardType={props.keyboard ?? 'default'}
        autoCapitalize="none"
        autoCorrect={false}
        hasTVPreferredFocus={props.preferred}
        onSubmitEditing={props.onSubmit}
        onFocus={() => setFocused(true)}
        onBlur={() => setFocused(false)}
        style={{
          color: color.chalk,
          fontSize: dp(16),
          borderRadius: dp(8),
          backgroundColor: color.surfaceRaised,
          borderWidth: dp(2),
          borderColor: focused ? accent.color : color.line,
          paddingHorizontal: dp(14),
          paddingVertical: dp(12),
        }}
      />
    </View>
  );
}

export const SectionHeading = (props: { text: string; style?: StyleProp<ViewStyle> }) => (
  <Text style={[{ color: color.faint, fontSize: dp(14), letterSpacing: dp(1.6), fontWeight: '500', textTransform: 'uppercase' }, props.style as object]}>{props.text}</Text>
);

export function ErrorNote(props: { message: string; onDismiss?: () => void; style?: StyleProp<ViewStyle> }) {
  return (
    <View style={[{ flexDirection: 'row', alignItems: 'center', gap: dp(14), borderRadius: dp(8), backgroundColor: 'rgba(255, 107, 107, 0.14)', borderWidth: dp(1), borderColor: 'rgba(255, 107, 107, 0.45)', padding: dp(14) }, props.style]}>
      <Text style={{ flex: 1, color: color.chalk, fontSize: dp(14) }}>{props.message}</Text>
      {props.onDismiss ? <ActionButton label="Dismiss" onPress={props.onDismiss} /> : null}
    </View>
  );
}

export const EmptyNote = (props: { text: string; style?: StyleProp<ViewStyle> }) => (
  <Text style={[{ color: color.faint, fontSize: dp(14) }, props.style as object]}>{props.text}</Text>
);

/** A "Label: value" line. */
export const FactLine = (props: { label: string; value: string }) => (
  <Text style={{ fontSize: dp(14) }}>
    <Text style={{ color: color.faint }}>{`${props.label}  `}</Text>
    <Text style={{ color: color.chalk }}>{props.value}</Text>
  </Text>
);

export const HintBar = (props: { text: string; style?: StyleProp<ViewStyle> }) => (
  <Text style={[{ color: color.faint, fontSize: dp(14) }, props.style as object]}>{props.text}</Text>
);

/** A spinner of the Fire TV's kind: a ring turning. */
export function Spinner(props: { size?: number }) {
  const turn = React.useRef(new Animated.Value(0)).current;
  React.useEffect(() => {
    const loop = Animated.loop(Animated.timing(turn, { toValue: 1, duration: 900, useNativeDriver: true }));
    loop.start();
    return () => loop.stop();
  }, [turn]);
  const s = dp(props.size ?? 28);
  const rotate = turn.interpolate({ inputRange: [0, 1], outputRange: ['0deg', '360deg'] });
  return (
    <Animated.View style={{ width: s, height: s, borderRadius: s / 2, borderWidth: dp(3), borderColor: 'rgba(242, 244, 247, 0.18)', borderTopColor: color.chalk, transform: [{ rotate }] }} />
  );
}
