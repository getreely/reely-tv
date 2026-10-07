/*
 * The shell's half of the page's bridge (webos/src/core/bridge.ts): a request the page
 * asks the shell to make, and the answer cut into parts for handing back. Kept free of
 * React Native so it can be tested on its own.
 */

/** What the page asks the shell to fetch. */
export interface ShellRequest {
  type: 'fetch';
  id: number;
  url: string;
  method?: string;
  headers?: Record<string, string>;
  body?: string;
  /** Bytes wanted (a piece of video): the answer is base64, the bridge carrying only text. */
  binary?: boolean;
}

/** One part of an answer, as the page's window.__reelyShell takes it. */
export interface AnswerPart {
  id: number;
  part: number;
  of: number;
  data: string;
  status?: number;
  statusText?: string;
  headers?: Record<string, string>;
  error?: string;
}

/**
 * How long a part may be. Each is handed over by injecting script into the page, and one
 * string of tens of megabytes (a provider's whole catalogue) stalled the picture.
 */
export const PART_CHARS = 256 * 1024;

/** [text] in parts for [id], the status and headers on the first. */
export function answerParts(
  id: number,
  text: string,
  head: { status: number; statusText?: string; headers?: Record<string, string> },
  size = PART_CHARS,
): AnswerPart[] {
  const of = Math.max(1, Math.ceil(text.length / size));
  const parts: AnswerPart[] = [];
  for (let part = 0; part < of; part++) {
    const data = text.slice(part * size, (part + 1) * size);
    parts.push(part === 0 ? { id, part, of, data, ...head } : { id, part, of, data });
  }
  return parts;
}

/** The answer when the request couldn't be made at all. */
export const failedPart = (id: number, error: string): AnswerPart => ({ id, part: 0, of: 1, data: '', error });

/** Script that hands [part] to the page. Injected scripts must end with `true;`. */
export const deliver = (part: AnswerPart) => `window.__reelyShell && window.__reelyShell(${JSON.stringify(JSON.stringify(part))}); true;`;

/** The message as sent, if it's one the shell knows. */
export function parseMessage(raw: string): ShellRequest | { type: 'exit' } | null {
  try {
    const message = JSON.parse(raw);
    if (message && message.type === 'exit') return { type: 'exit' };
    if (message && message.type === 'fetch' && typeof message.id === 'number' && typeof message.url === 'string') return message as ShellRequest;
  } catch {
    // Not one of the page's.
  }
  return null;
}

/** Script that tells the page Back was pressed: its remote handling takes it from there. */
export const BACK_PRESS = `(function () {
  var down = new KeyboardEvent('keydown', { key: 'GoBack', bubbles: true, cancelable: true });
  var up = new KeyboardEvent('keyup', { key: 'GoBack', bubbles: true, cancelable: true });
  document.dispatchEvent(down);
  document.dispatchEvent(up);
})(); true;`;

/** Script that tells the page the app went away or came back, should the WebView not say so itself. */
export const appState = (away: boolean) =>
  `document.dispatchEvent(new CustomEvent('reely-app-state', { detail: ${away ? '"away"' : '"back"'} })); true;`;
