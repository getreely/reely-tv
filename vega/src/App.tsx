import React, { useEffect, useState } from 'react';
import { View } from 'react-native';
import { App as Reely } from './app/store';
import { watchForProblems } from './core/crash';
import { PersistedStorage } from './core/persist';
import { Store } from './core/storage';
import { Root } from './ui/root';

/*
 * Reely on Vega: its own React Native app, drawn and behaving as the Fire TV app does.
 * What's kept on the stick is read in first, then the app starts where it was left.
 */
export const App = () => {
  const [app, setApp] = useState<Reely | null>(null);
  useEffect(() => {
    void PersistedStorage.load().then((storage) => {
      const store = new Store('reely:', storage);
      // What goes wrong, kept for Settings' problem report.
      watchForProblems(store);
      const reely = new Reely(store);
      setApp(reely);
      void reely.start();
    });
  }, []);
  if (!app) return <View style={{ flex: 1, backgroundColor: '#08090B' }} />;
  return <Root app={app} />;
};
