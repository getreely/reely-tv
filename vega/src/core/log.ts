/*
 * What the app has said about playing things lately, kept in memory for Settings to
 * show: the lines it already writes to the stick's log ("[reely] player 3 opens …"),
 * which otherwise need a computer and Amazon's tools to read. On the TV they can be read,
 * or photographed, right after something goes wrong. Nothing is sent anywhere, and
 * they're gone when the app closes.
 */

export interface LogLine {
  /** Epoch milliseconds. */
  at: number;
  text: string;
}

const KEEP = 400;
const lines: LogLine[] = [];
let listening = false;

function textOf(args: unknown[]): string {
  return args.map((a) => (typeof a === "string" ? a : a instanceof Error ? `${a.name}: ${a.message}` : safe(a))).join(" ");
}

function safe(value: unknown): string {
  try {
    return JSON.stringify(value) ?? String(value);
  } catch {
    return String(value);
  }
}

/** The app's own warnings kept as they're written; everything still goes to the stick's log as before. */
export function keepLog() {
  if (listening) return;
  listening = true;
  const warn = console.warn.bind(console);
  console.warn = (...args: unknown[]) => {
    try {
      if (typeof args[0] === "string" && args[0].startsWith("[reely]")) {
        lines.push({ at: Date.now(), text: textOf(args).slice("[reely]".length).trim().slice(0, 300) });
        if (lines.length > KEEP) lines.splice(0, lines.length - KEEP);
      }
    } catch {
      // The log's own trouble isn't the app's.
    }
    warn(...args);
  };
}

/** How many lines are kept. */
export const logLength = () => lines.length;

/** [count] lines ending [back] lines before the newest, oldest first. */
export function logPage(count: number, back = 0): LogLine[] {
  const end = Math.max(0, lines.length - back);
  return lines.slice(Math.max(0, end - count), end);
}

/** 14:03:27, by the TV's clock. */
export function logTime(at: number): string {
  const d = new Date(at);
  const two = (n: number) => String(n).padStart(2, "0");
  return `${two(d.getHours())}:${two(d.getMinutes())}:${two(d.getSeconds())}`;
}
