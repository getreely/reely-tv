import React from 'react';
import { StyleSheet, Text as RNText, type TextProps, type TextStyle } from 'react-native';

/*
 * Text in Geist, as the Fire TV app sets it. Vega takes a font by its family name, one
 * file per weight (assets/fonts/Geist-Medium.ttf and so on), so a weight is a family here.
 */

const FAMILY: Record<string, string> = {
  '100': 'Geist-Regular',
  '200': 'Geist-Regular',
  '300': 'Geist-Regular',
  '400': 'Geist-Regular',
  normal: 'Geist-Regular',
  '500': 'Geist-Medium',
  '600': 'Geist-SemiBold',
  '700': 'Geist-Bold',
  bold: 'Geist-Bold',
  '800': 'Geist-Bold',
  '900': 'Geist-Bold',
};

export const Text = React.forwardRef<RNText, TextProps>(function Text(props, ref) {
  const flat = (StyleSheet.flatten(props.style) ?? {}) as TextStyle;
  const { fontWeight, fontFamily, ...rest } = flat;
  const family = fontFamily && !fontFamily.startsWith('Geist') ? fontFamily : FAMILY[String(fontWeight ?? '400')] ?? 'Geist-Regular';
  return <RNText ref={ref} {...props} style={[rest, { fontFamily: family }]} />;
});
