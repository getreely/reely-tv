// react-native, as react-native-web gives it, with Back from Escape (the remote's Back).
export * from 'react-native-web';
import { BackHandler as WebBackHandler } from 'react-native-web';

type Listener = () => boolean | null | undefined;
const handlers: Listener[] = [];

if (typeof window !== 'undefined') {
  window.addEventListener('keydown', (e) => {
    if (e.key !== 'Escape' && e.key !== 'Backspace') return;
    if ((e.target as HTMLElement).tagName === 'INPUT' && e.key === 'Backspace') return;
    for (let i = handlers.length - 1; i >= 0; i--) if (handlers[i]()) return;
    (window as unknown as { __exited?: boolean }).__exited = true;
  });
}

export const BackHandler = {
  ...WebBackHandler,
  addEventListener(_event: string, handler: Listener) {
    handlers.push(handler);
    return { remove: () => { const at = handlers.indexOf(handler); if (at >= 0) handlers.splice(at, 1); } };
  },
  exitApp() {
    (window as unknown as { __exited?: boolean }).__exited = true;
  },
};
