import { useEffect } from 'react';
import { AudioPlayer } from '@amazon-devices/react-native-w3cmedia';
import { THEME_LEVELS } from '../app/store';

/*
 * A show's theme, quietly, while its page is up (DetailScreen's theme music on the Fire
 * TV): Amazon's audio player, no picture. It stops when the page goes, and starts again
 * when another show's page comes.
 */
export function ThemeMusic(props: { url: string; level: number }) {
  const volume = THEME_LEVELS[props.level]?.[1] ?? 0;
  useEffect(() => {
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
      void ready.then(() => {
        try {
          player.pause();
        } catch {
          // Not started.
        }
        return player.deinitialize();
      }).catch(() => undefined);
    };
  }, [props.url, volume]);
  return null;
}
