import React from 'react';
import Svg, { Circle, G, Line, Path, Rect } from '@amazon-devices/react-native-svg';
import { dp } from './theme';

/*
 * The Fire TV app's glyphs (Icons.kt), drawn the same way: each on a 100-unit square, so
 * the Fire TV's fractions of its size are hundredths here. Sizes are Fire TV dp.
 */

interface GlyphProps {
  color: string;
  /** Fire TV dp. */
  size?: number;
}

const Box = (props: { size?: number; children: React.ReactNode }) => {
  const s = dp(props.size ?? 18);
  return (
    <Svg width={s} height={s} viewBox="0 0 100 100">
      {props.children}
    </Svg>
  );
};

/** An arc as Compose draws one: degrees clockwise from three o'clock, in the box given. */
function arc(left: number, top: number, width: number, height: number, start: number, sweep: number): string {
  const rx = width / 2;
  const ry = height / 2;
  const cx = left + rx;
  const cy = top + ry;
  const at = (deg: number) => {
    const r = (deg * Math.PI) / 180;
    return [cx + rx * Math.cos(r), cy + ry * Math.sin(r)];
  };
  const [x1, y1] = at(start);
  const [x2, y2] = at(start + sweep);
  const large = Math.abs(sweep) > 180 ? 1 : 0;
  const clockwise = sweep > 0 ? 1 : 0;
  return `M ${x1} ${y1} A ${rx} ${ry} 0 ${large} ${clockwise} ${x2} ${y2}`;
}

const round = { strokeLinecap: 'round' as const, strokeLinejoin: 'round' as const };

export const PlayGlyph = ({ color, size }: GlyphProps) => (
  <Box size={size}>
    <Path d="M26 16 L84 50 L26 84 Z" fill={color} />
  </Box>
);

export const CheckGlyph = ({ color, size }: GlyphProps) => (
  <Box size={size}>
    <Path d="M20 52 L42 74 L80 26" stroke={color} strokeWidth={14} fill="none" {...round} />
  </Box>
);

export const InfoGlyph = ({ color, size }: GlyphProps) => (
  <Box size={size}>
    <Circle cx={50} cy={50} r={42} stroke={color} strokeWidth={10} fill="none" />
    <Circle cx={50} cy={31} r={5.5} fill={color} />
    <Line x1={50} y1={47} x2={50} y2={72} stroke={color} strokeWidth={11} {...round} />
  </Box>
);

export const BookmarkGlyph = ({ color, size, filled }: GlyphProps & { filled: boolean }) => (
  <Box size={size}>
    <Path d="M26 14 L74 14 L74 88 L50 68 L26 88 Z" fill={filled ? color : 'none'} stroke={filled ? 'none' : color} strokeWidth={10} strokeLinejoin="round" />
  </Box>
);

export const HeartGlyph = ({ color, size }: GlyphProps) => (
  <Box size={size}>
    <Path d="M50 88 C12 62 2 42 10 26 C20 8 42 8 50 26 C58 8 80 8 90 26 C98 42 88 62 50 88 Z" fill={color} />
  </Box>
);

/**
 * The moon's circle less the circle bitten out of it, as one outline: round the moon from
 * one crossing of the two to the other, then back round the inside of the bite.
 */
const MOON = (() => {
  const [x1, y1, r1] = [50, 50, 36];
  const [x2, y2, r2] = [70, 36, 34];
  const d = Math.hypot(x2 - x1, y2 - y1);
  const a = (r1 * r1 - r2 * r2 + d * d) / (2 * d);
  const h = Math.sqrt(r1 * r1 - a * a);
  const mx = x1 + (a * (x2 - x1)) / d;
  const my = y1 + (a * (y2 - y1)) / d;
  const px = (h * (y2 - y1)) / d;
  const py = (h * (x2 - x1)) / d;
  const [ax, ay] = [mx + px, my - py];
  const [bx, by] = [mx - px, my + py];
  return `M ${ax} ${ay} A ${r1} ${r1} 0 1 0 ${bx} ${by} A ${r2} ${r2} 0 0 1 ${ax} ${ay} Z`;
})();

export const MoonGlyph = ({ color, size }: GlyphProps) => (
  <Box size={size}>
    <Path d={MOON} fill={color} />
  </Box>
);

export const ChaptersGlyph = ({ color, size }: GlyphProps) => (
  <Box size={size}>
    {[25, 50, 75].map((y) => (
      <G key={y}>
        <Circle cx={16} cy={y} r={6.5} fill={color} />
        <Line x1={34} y1={y} x2={88} y2={y} stroke={color} strokeWidth={10} {...round} />
      </G>
    ))}
  </Box>
);

export const TrailerGlyph = ({ color, size }: GlyphProps) => (
  <Box size={size}>
    <Rect x={10} y={20} width={80} height={60} rx={12} stroke={color} strokeWidth={10} fill="none" />
    <Path d="M40 36 L66 50 L40 64 Z" fill={color} />
  </Box>
);

export const SearchGlyph = ({ color, size }: GlyphProps) => (
  <Box size={size}>
    <Circle cx={42} cy={42} r={30} stroke={color} strokeWidth={12} fill="none" />
    <Line x1={63} y1={63} x2={86} y2={86} stroke={color} strokeWidth={12} {...round} />
  </Box>
);

export const PauseGlyph = ({ color, size }: GlyphProps) => (
  <Box size={size}>
    <Rect x={27} y={18} width={17} height={64} rx={6.8} fill={color} />
    <Rect x={56} y={18} width={17} height={64} rx={6.8} fill={color} />
  </Box>
);

export const SkipGlyph = ({ color, size, forward }: GlyphProps & { forward: boolean }) => (
  <Box size={size}>
    <Path d={forward ? 'M16 20 L62 50 L16 80 Z' : 'M84 20 L38 50 L84 80 Z'} fill={color} />
    <Rect x={forward ? 68 : 21} y={20} width={11} height={60} rx={4.4} fill={color} />
  </Box>
);

export const SpeakerGlyph = ({ color, size }: GlyphProps) => (
  <Box size={size}>
    <Path d="M10 36 L26 36 L46 18 L46 82 L26 64 L10 64 Z" fill={color} />
    <Path d={arc(34, 26, 34, 48, -55, 110)} stroke={color} strokeWidth={8} fill="none" {...round} />
    <Path d={arc(40, 14, 50, 72, -55, 110)} stroke={color} strokeWidth={8} fill="none" {...round} />
  </Box>
);

export const SubtitleGlyph = ({ color, size }: GlyphProps) => (
  <Box size={size}>
    <Rect x={10} y={18} width={80} height={54} rx={16} stroke={color} strokeWidth={9} fill="none" />
    <Path d="M28 70 L28 90 L48 70 Z" fill={color} />
    <Line x1={24} y1={38} x2={62} y2={38} stroke={color} strokeWidth={8.5} {...round} />
    <Line x1={24} y1={53} x2={76} y2={53} stroke={color} strokeWidth={8.5} {...round} />
  </Box>
);

export const RestartGlyph = ({ color, size }: GlyphProps) => (
  <Box size={size}>
    <Path d={arc(16, 16, 68, 68, 160, 300)} stroke={color} strokeWidth={12} fill="none" {...round} />
    <Path d="M18 8 L18 44 L48 26 Z" fill={color} />
  </Box>
);

export const GearGlyph = ({ color, size }: GlyphProps) => (
  <Box size={size}>
    <Circle cx={50} cy={50} r={27} stroke={color} strokeWidth={13} fill="none" />
    {Array.from({ length: 8 }, (_, i) => {
      const a = (i * Math.PI) / 4;
      return (
        <Line key={i} x1={50 + Math.cos(a) * 27} y1={50 + Math.sin(a) * 27} x2={50 + Math.cos(a) * 45} y2={50 + Math.sin(a) * 45}
          stroke={color} strokeWidth={12} {...round} />
      );
    })}
  </Box>
);

export const PlusGlyph = ({ color, size }: GlyphProps) => (
  <Box size={size}>
    <Line x1={20} y1={50} x2={80} y2={50} stroke={color} strokeWidth={12} {...round} />
    <Line x1={50} y1={20} x2={50} y2={80} stroke={color} strokeWidth={12} {...round} />
  </Box>
);

export const MinusGlyph = ({ color, size }: GlyphProps) => (
  <Box size={size}>
    <Line x1={20} y1={50} x2={80} y2={50} stroke={color} strokeWidth={12} {...round} />
  </Box>
);

export const CrossGlyph = ({ color, size }: GlyphProps) => (
  <Box size={size}>
    <Line x1={24} y1={24} x2={76} y2={76} stroke={color} strokeWidth={12} {...round} />
    <Line x1={76} y1={24} x2={24} y2={76} stroke={color} strokeWidth={12} {...round} />
  </Box>
);

export const ClockGlyph = ({ color, size }: GlyphProps) => (
  <Box size={size}>
    <Circle cx={50} cy={50} r={38} stroke={color} strokeWidth={10} fill="none" />
    <Line x1={50} y1={50} x2={50} y2={28} stroke={color} strokeWidth={10} {...round} />
    <Line x1={50} y1={50} x2={66} y2={58} stroke={color} strokeWidth={10} {...round} />
  </Box>
);

export const TilesGlyph = ({ color, size }: GlyphProps) => {
  const cell = 34;
  const gap = 10;
  const start = (100 - cell * 2 - gap) / 2;
  return (
    <Box size={size}>
      {[0, 1].flatMap((r) => [0, 1].map((c) => (
        <Rect key={`${r}${c}`} x={start + c * (cell + gap)} y={start + r * (cell + gap)} width={cell} height={cell} rx={6}
          stroke={color} strokeWidth={9} fill="none" />
      )))}
    </Box>
  );
};

export const ArrowGlyph = ({ color, size, left }: GlyphProps & { left: boolean }) => {
  const dir = left ? -1 : 1;
  const tip = 50 + dir * 30;
  return (
    <Box size={size}>
      <Line x1={50 - dir * 30} y1={50} x2={tip} y2={50} stroke={color} strokeWidth={10} {...round} />
      <Line x1={tip} y1={50} x2={tip - dir * 20} y2={30} stroke={color} strokeWidth={10} {...round} />
      <Line x1={tip} y1={50} x2={tip - dir * 20} y2={70} stroke={color} strokeWidth={10} {...round} />
    </Box>
  );
};
