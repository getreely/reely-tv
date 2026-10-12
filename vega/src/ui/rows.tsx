import React, { createContext, useContext, useRef } from 'react';
import { ScrollView, View, type StyleProp, type ViewStyle } from 'react-native';
import { TVFocusGuideView } from '@amazon-devices/react-native-kepler';
import { FocusRow, FocusWithinContext } from './focus';
import { Text } from './text';
import { dp, EDGE, type } from './theme';

/*
 * Rows of cards, as Home and a library's home have them (HomeScreen.kt's PosterRow and
 * rememberRowSnap): a heading over cards running off to the right. A row coming into
 * focus snaps its heading to the top of the rows, and each row remembers the card it was
 * on, going back to it rather than whichever is nearest.
 */

/** Between a row's heading and its cards: room for a focused card's lift and ring. */
export const HEADING_GAP = dp(16);
export const ROW_GAP = dp(14);

const SnapContext = createContext<(y: number) => void>(() => undefined);

/** A column of rows that keeps the focused row's heading at its top. */
export function Rows(props: { children: React.ReactNode; style?: StyleProp<ViewStyle>; header?: React.ReactNode }) {
  const scroll = useRef<ScrollView>(null);
  const snap = (y: number) => scroll.current?.scrollTo({ y, animated: true });
  return (
    <SnapContext.Provider value={snap}>
      <ScrollView ref={scroll} style={props.style} showsVerticalScrollIndicator={false} contentContainerStyle={{ gap: ROW_GAP, paddingBottom: dp(160) }}>
        {props.header}
        {props.children}
      </ScrollView>
    </SnapContext.Provider>
  );
}

/** A row's heading and its cards, which step back while one of them has focus. */
export function PosterRow(props: { title: string; children: React.ReactNode }) {
  const snap = useContext(SnapContext);
  const y = useRef(0);
  return (
    <View onLayout={(e) => { y.current = e.nativeEvent.layout.y; }} style={{ gap: HEADING_GAP }}>
      <Text style={[type.rowTitle, { paddingHorizontal: EDGE }]}>{props.title}</Text>
      <FocusRow>
        <FocusWithinContext.Provider value={() => snap(y.current)}>
          <TVFocusGuideView autoFocus>
            <ScrollView horizontal showsHorizontalScrollIndicator={false}
              contentContainerStyle={{ paddingHorizontal: dp(36), gap: dp(14), paddingVertical: dp(14) }}
              style={{ marginVertical: -dp(14) }}>
              {props.children}
            </ScrollView>
          </TVFocusGuideView>
        </FocusWithinContext.Provider>
      </FocusRow>
    </View>
  );
}
