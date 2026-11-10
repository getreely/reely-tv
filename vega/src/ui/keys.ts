import { useCallback, useRef } from 'react';
import { useTVEventHandler } from '@amazon-devices/react-native-kepler';

type KeyEvent = Parameters<Parameters<typeof useTVEventHandler>[0]>[0];

/**
 * The remote's presses, told to [handler] as the screen stands now. Amazon's own hook
 * listens with whatever handler it was last given, and is given the new one only after
 * the screen has been drawn and settled: a press close behind a change was still told to
 * the old one, which went by how things were. With a channel's controls just brought up,
 * Down was taken as pressed with them away, and raised the guide over them. The hook also
 * took its listener off the stick and put it back on every drawing, twice a second under
 * a player.
 *
 * Here the stick is listened to once, and the handler asked for at the moment of each press.
 */
export function useKeys(handler: (event: KeyEvent) => void) {
  const latest = useRef(handler);
  latest.current = handler;
  const told = useCallback((event: KeyEvent) => latest.current(event), []);
  useTVEventHandler(told);
}
