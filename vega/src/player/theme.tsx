import { useLayoutEffect } from 'react';
import { AudioPlayer } from '@amazon-devices/react-native-w3cmedia';
import { THEME_LEVELS } from '../app/store';
import { letGoOf } from './release';

/*
 * A show's theme, quietly, while its page is up (DetailScreen's theme music on the Fire
 * TV): Amazon's audio player, no picture. It stops when the page goes, and starts again
 * when another show's page comes; an episode opening waits for it to have let go.
 */
export function ThemeMusic(props: { url: string; level: number }) {
  const volume = THEME_LEVELS[props.level]?.[1] ?? 0;
  // A layout effect, so going is said as the page goes, before an episode's player asks.
  useLayoutEffect(() => {
    let gone = false;
    const player = new AudioPlayer();
    const ready = player.initialize().then(() => {
      if (gone) return;
      player.volume = volume;
      player.src = props.url;
      void player.play();
    }).catch(() => undefined);
    return () => {
      gone = true;
      // Counted as being let go, so an episode opening now waits for the sound output
      // to be free (release.ts).
      void letGoOf(ready.then(() => {
        try {
          player.pause();
        } catch {
          // Not started.
        }
        return player.deinitialize();
      }));
    };
  }, [props.url, volume]);
  return null;
}
