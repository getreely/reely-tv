import React, { useEffect, useState } from 'react';
import { View } from 'react-native';
import { Press } from './focus';
import { Text } from './text';
import { color, dp } from './theme';

/*
 * A summary held to three lines (Summary.kt's ExpandableSummary). When there's more of it,
 * the cursor can land on it, and OK opens the rest, smaller where it's long, so it fits.
 */

const SUMMARY_LINES = 3;
/** About three lines at the reading width: more than this, and there's more to open. */
const CUT_AT = 240;

export function ExpandableSummary(props: { text: string; maxWidth?: number }) {
  const [open, setOpen] = useState(false);
  const [focused, setFocused] = useState(false);
  // Moving to another episode closes it again.
  useEffect(() => setOpen(false), [props.text]);
  const long = props.text.length > CUT_AT;
  const size = !open ? 15 : props.text.length > 900 ? 12 : props.text.length > 600 ? 13 : 14;
  const words = (
    <Text numberOfLines={open ? undefined : SUMMARY_LINES}
      style={{ color: focused ? color.chalk : color.muted, fontSize: dp(size), lineHeight: dp(size * 1.4), minHeight: open ? undefined : dp(15 * 1.4 * SUMMARY_LINES) }}>
      {props.text}
    </Text>
  );
  const width = { maxWidth: dp(props.maxWidth ?? 640) };
  if (!long) return <View style={width}>{words}</View>;
  return (
    <Press onPress={() => setOpen(!open)} onFocusChange={setFocused}
      style={[width, {
        // A soft panel drawn out past the text, so the words don't move when focus arrives.
        marginHorizontal: -dp(14), marginVertical: -dp(8), paddingHorizontal: dp(14), paddingVertical: dp(8), borderRadius: dp(14),
        borderWidth: dp(2), borderColor: focused ? 'rgba(242, 244, 247, 0.55)' : 'transparent',
        backgroundColor: focused ? 'rgba(242, 244, 247, 0.08)' : 'transparent',
      }]}>
      {() => words}
    </Press>
  );
}
