import React, { useCallback, useEffect, useMemo, useRef } from 'react';
import { AppState, BackHandler, StyleSheet, View } from 'react-native';
import { WebView } from '@amazon-devices/webview';
import type { WebViewMessageEvent, WebViewMethods } from '@amazon-devices/webview/dist/types/WebViewTypes';
import { useHideSplashScreenCallback, usePreventHideSplashScreen } from '@amazon-devices/react-native-kepler';
import { useReportFullyDrawn } from '@amazon-devices/kepler-performance-api';
import { BACK_PRESS, BEFORE_PAGE, answerParts, appState, deliver, failedPart, parseMessage } from './answer';

/*
 * Reely on Vega: the LG app's page (built into assets/web) in a WebView. The page does
 * everything it does on an LG TV; the shell does the three things it can't — closing the
 * app, asking a server that doesn't answer other sites, and hearing Back — and says when
 * the app goes away and comes back.
 */
export const App = () => {
  const web = useRef<WebViewMethods>(null);
  const source = useMemo(() => ({ uri: 'file:///pkg/assets/web/index.html' }), []);
  usePreventHideSplashScreen();
  const hideSplashScreen = useHideSplashScreenCallback();
  const reportFullyDrawn = useReportFullyDrawn();
  const appStateNow = useRef(AppState.currentState ?? 'active');

  const inject = useCallback((script: string) => web.current?.injectJavaScript(script), []);

  // Back is the page's: its remote handling goes back a screen, and at Home it asks to leave.
  useEffect(() => {
    const subscription = BackHandler.addEventListener('hardwareBackPress', () => {
      inject(BACK_PRESS);
      return true;
    });
    return () => subscription.remove();
  }, [inject]);

  // Away and back: the page pauses its video, and picks up again (a channel joined afresh).
  useEffect(() => {
    const subscription = AppState.addEventListener('change', (next) => {
      const was = appStateNow.current;
      appStateNow.current = next;
      if (next === 'background' && was !== 'background') inject(appState(true));
      if (next === 'active' && was !== 'active') {
        inject(appState(false));
        reportFullyDrawn();
      }
    });
    return () => subscription.remove();
  }, [inject, reportFullyDrawn]);

  const onMessage = useCallback(
    async (event: WebViewMessageEvent) => {
      const message = parseMessage(event.nativeEvent.data);
      if (!message) return;
      if (message.type === 'exit') {
        BackHandler.exitApp();
        return;
      }
      try {
        const response = await fetch(message.url, { method: message.method ?? 'GET', headers: message.headers, body: message.body });
        const text = message.binary ? await base64Of(response) : await response.text();
        const headers: Record<string, string> = {};
        response.headers.forEach((value: string, key: string) => {
          headers[key] = value;
        });
        for (const part of answerParts(message.id, text, { status: response.status, statusText: response.statusText, headers })) inject(deliver(part));
      } catch (error) {
        inject(deliver(failedPart(message.id, error instanceof Error ? error.message : 'Network request failed')));
      }
    },
    [inject],
  );

  const onLoad = useCallback(() => {
    hideSplashScreen();
    reportFullyDrawn();
  }, [hideSplashScreen, reportFullyDrawn]);

  // Kept mounted whatever happens: unmounting the WebView loses the page and all it holds.
  // The splash goes all the same, or a page that didn't load leaves it up for good.
  const onError = useCallback(() => hideSplashScreen(), [hideSplashScreen]);

  return (
    <View style={styles.container}>
      <WebView
        ref={web}
        source={source}
        style={styles.web}
        hasTVPreferredFocus={true}
        javaScriptEnabled={true}
        domStorageEnabled={true}
        allowFileAccess={true}
        mixedContentMode="always"
        mediaPlaybackRequiresUserAction={false}
        thirdPartyCookiesEnabled={true}
        allowsDefaultMediaControl={true}
        injectedJavaScriptBeforeContentLoaded={BEFORE_PAGE}
        onMessage={onMessage}
        onLoad={onLoad}
        onError={onError}
        onCloseWindow={BackHandler.exitApp}
      />
    </View>
  );
};

/** A response's bytes as base64: React Native's fetch reads a blob as a data URL, not as bytes. */
function base64Of(response: Response): Promise<string> {
  return response.blob().then(
    (blob) =>
      new Promise<string>((resolve, reject) => {
        const reader = new FileReader();
        reader.onload = () => {
          const url = String(reader.result ?? '');
          resolve(url.slice(url.indexOf(',') + 1));
        };
        reader.onerror = () => reject(reader.error ?? new Error('Unreadable answer'));
        reader.readAsDataURL(blob);
      }),
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#000' },
  web: { flex: 1, backgroundColor: '#000' },
});
