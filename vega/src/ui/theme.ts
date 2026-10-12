import { StyleSheet, type TextStyle } from 'react-native';

/*
 * The Fire TV app's look (Theme.kt), at Vega's size. Vega lays the screen out in 1920 by
 * 1080; the Fire TV's Compose screens are 960 by 540 dp. So every size here is the Fire
 * TV's, doubled: `dp(n)` is n Fire TV dp.
 */

export const dp = (n: number) => n * 2;

export const color = {
  ink: '#08090B',
  surfaceRaised: '#15171B',
  surfaceHigh: '#1F2329',
  line: '#2B3038',
  chalk: '#F2F4F7',
  muted: '#B3BAC4',
  faint: '#7F8792',
  danger: '#FF6B6B',
  good: '#8CBE6E',
  warn: '#E9A343',
  glass: 'rgba(26, 29, 34, 0.55)',
  glassEdge: 'rgba(242, 244, 247, 0.14)',
};

/** The colors Settings offers for the accent, and what's legible on each. */
export const ACCENTS: Record<string, { color: string; on: string }> = {
  blue: { color: '#2E6BFF', on: '#FFFFFF' },
  red: { color: '#FF5E69', on: '#08090B' },
  purple: { color: '#8B5CF6', on: '#FFFFFF' },
  pink: { color: '#EC4899', on: '#FFFFFF' },
  orange: { color: '#FF8A3D', on: '#08090B' },
  gold: { color: '#F5C542', on: '#08090B' },
  teal: { color: '#14B8A6', on: '#08090B' },
};

export const accentOf = (id: string) => ACCENTS[id] ?? ACCENTS.blue;

/** [hex] at [alpha], for a glow or a wash. */
export function withAlpha(hex: string, alpha: number): string {
  const [r, g, b] = [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16));
  return `rgba(${r}, ${g}, ${b}, ${alpha})`;
}

const FONT = 'Geist-Regular';

const text = (size: number, line: number, weight: TextStyle['fontWeight'], spacing = 0): TextStyle => ({
  fontFamily: FONT,
  fontSize: dp(size),
  lineHeight: dp(line),
  fontWeight: weight,
  letterSpacing: dp(spacing),
  color: color.chalk,
});

/** The type scale (ReelyType). */
export const type = StyleSheet.create({
  display: text(36, 42, '700', -0.6),
  headline: text(24, 30, '600', -0.2),
  rowTitle: text(20, 26, '600', -0.1),
  body: text(18, 26, '400'),
  meta: { ...text(16, 22, '500'), color: color.muted },
  label: text(14, 18, '500'),
  /** Small capitals over a section, as menus and the guide have them. */
  section: { ...text(13, 16, '600', 1.2), color: color.faint, textTransform: 'uppercase' },
});

/** The side margin screens keep, inside the TV's safe area. */
export const EDGE = dp(40);
/** Rounded corners: posters and pills, and the wide ones (tiles, panels). */
export const CORNER = dp(10);
export const WIDE_CORNER = dp(14);
