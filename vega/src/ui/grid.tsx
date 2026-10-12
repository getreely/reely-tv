import React from 'react';
import { FlatList, View } from 'react-native';
import type { PlexItem } from '../api/plex';
import * as plex from '../api/plex';
import { sourceTag } from '../api/vod';
import { PosterCard, PosterPlaceholder } from './cards';
import { FocusRow } from './focus';
import { dp } from './theme';

/*
 * Posters six across under a heading (the Fire TV's LazyVerticalGrid of 140 dp columns),
 * for an actor's titles and a playlist.
 */

export const GRID_COLUMNS = 6;
export const GRID_MARGIN = dp(36);
export const GRID_COLUMN_GAP = dp(8);
export const GRID_ROW_GAP = dp(12);
/** Fire TV dp: six across in the 888 between the margins. */
export const GRID_CARD_WIDTH = (960 - 72 - 5 * 8) / GRID_COLUMNS;

export function PosterGrid(props: {
  items: PlexItem[];
  /** Shapes of posters while they're on their way. */
  placeholders?: number;
  header: React.ReactElement;
  title?: (item: PlexItem) => string;
  subtitle?: (item: PlexItem) => string | null;
  image: (item: PlexItem) => string | null;
  onPress: (item: PlexItem) => void;
  onHold?: (item: PlexItem) => (() => void) | undefined;
  onFocus?: (item: PlexItem) => void;
  /** The first poster takes the cursor on arriving. */
  claim?: boolean;
}) {
  const cells: Array<PlexItem | null> = props.placeholders ? Array.from({ length: props.placeholders }, () => null) : props.items;
  const rows: Array<Array<PlexItem | null>> = [];
  for (let i = 0; i < cells.length; i += GRID_COLUMNS) rows.push(cells.slice(i, i + GRID_COLUMNS));
  return (
    <FocusRow>
      <FlatList
        data={rows}
        keyExtractor={(row, i) => (row[0] ? plex.listKey(row[0]) : `placeholder:${i}`)}
        ListHeaderComponent={props.header}
        contentContainerStyle={{ paddingHorizontal: GRID_MARGIN, paddingTop: dp(18), paddingBottom: dp(160) }}
        showsVerticalScrollIndicator={false}
        initialNumToRender={4}
        windowSize={7}
        renderItem={({ item: row, index: r }) => (
          <View style={{ flexDirection: 'row', columnGap: GRID_COLUMN_GAP, marginBottom: GRID_ROW_GAP }}>
            {row.map((item, c) => {
              if (!item) return <PosterPlaceholder key={c} width={GRID_CARD_WIDTH} />;
              const key = plex.listKey(item);
              return (
                <PosterCard key={key} focusKey={key} first={props.claim !== false && r === 0 && c === 0} width={GRID_CARD_WIDTH}
                  title={props.title?.(item) ?? item.title} subtitle={props.subtitle ? props.subtitle(item) : plex.caption(item)}
                  image={props.image(item)} tag={sourceTag(item)} progress={plex.resumeFraction(item)} watched={plex.isWatched(item)}
                  onFocus={() => props.onFocus?.(item)} onPress={() => props.onPress(item)} onHold={props.onHold?.(item)} />
              );
            })}
          </View>
        )}
      />
    </FocusRow>
  );
}
