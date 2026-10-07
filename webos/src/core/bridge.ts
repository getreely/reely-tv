import { shell } from "./platform";

/*
 * Talking to the Vega shell. The page posts it a message; it answers by calling
 * window.__reelyShell with JSON. A long answer comes in parts, each under a quarter of a
 * million characters: the shell hands strings over by injecting script, and one of tens of
 * megabytes (a provider's whole catalogue) stalled the picture.
 */

export interface ShellAnswer {
  id: number;
  /** Which part this is, from 0, of how many. */
  part: number;
  of: number;
  data: string;
  status?: number;
  statusText?: string;
  headers?: Record<string, string>;
  error?: string;
}

interface Waiting {
  parts: string[];
  got: number;
  head: Omit<ShellAnswer, "part" | "of" | "data"> | null;
  resolve: (answer: { head: Omit<ShellAnswer, "part" | "of" | "data">; body: string }) => void;
  reject: (error: Error) => void;
}

let next = 1;
const waiting = new Map<number, Waiting>();

/** What the shell calls with each answer, or part of one. */
export function receive(raw: string | ShellAnswer) {
  let answer: ShellAnswer;
  try {
    answer = typeof raw === "string" ? (JSON.parse(raw) as ShellAnswer) : raw;
  } catch {
    return;
  }
  const w = waiting.get(answer.id);
  if (!w) return;
  if (answer.error) {
    waiting.delete(answer.id);
    w.reject(new TypeError(answer.error));
    return;
  }
  if (answer.part === 0 || !w.head) {
    const { part: _p, of: _o, data: _d, ...head } = answer;
    w.head = head;
  }
  if (w.parts[answer.part] === undefined) {
    w.parts[answer.part] = answer.data ?? "";
    w.got++;
  }
  if (w.got >= Math.max(1, answer.of)) {
    waiting.delete(answer.id);
    w.resolve({ head: w.head!, body: w.parts.join("") });
  }
}

if (typeof window !== "undefined") {
  (window as unknown as { __reelyShell?: typeof receive }).__reelyShell = receive;
}

/** Something to tell the shell, with no answer: leaving the app, say. */
export function tell(message: Record<string, unknown>): boolean {
  const to = shell();
  if (!to) return false;
  to.postMessage(JSON.stringify(message));
  return true;
}

/**
 * A request made by the shell rather than the page: the shell isn't held to the
 * cross-origin rules a page opened from the app's own files is, and a provider or server
 * that doesn't allow other sites still answers it. Comes back as a Response, as fetch's do.
 */
export function shellFetch(url: string, init: RequestInit = {}, binary = false): Promise<Response> {
  const to = shell();
  if (!to) return Promise.reject(new TypeError("No shell to ask."));
  const id = next++;
  const headers: Record<string, string> = {};
  new Headers(init.headers ?? {}).forEach((value, key) => {
    headers[key] = value;
  });
  let body: string | undefined;
  if (typeof init.body === "string") body = init.body;
  else if (init.body instanceof URLSearchParams) {
    body = init.body.toString();
    if (!headers["content-type"]) headers["content-type"] = "application/x-www-form-urlencoded;charset=UTF-8";
  }
  return new Promise<Response>((resolve, reject) => {
    const signal = init.signal;
    const aborted = () => {
      if (!waiting.delete(id)) return;
      reject(new DOMException("Aborted", "AbortError"));
    };
    if (signal?.aborted) return aborted();
    signal?.addEventListener("abort", aborted, { once: true });
    waiting.set(id, {
      parts: [],
      got: 0,
      head: null,
      resolve: ({ head, body: text }) => {
        signal?.removeEventListener("abort", aborted);
        // A Response can't be made with a status outside 200–599, nor with a body for 204 and 304.
        const status = Math.min(599, Math.max(200, head.status ?? 200));
        const empty = status === 204 || status === 205 || status === 304;
        // Bytes (a piece of video) come as base64, the bridge carrying only text.
        const body = empty ? null : binary ? fromBase64(text) : text;
        resolve(new Response(body, { status, statusText: head.statusText ?? "", headers: head.headers ?? {} }));
      },
      reject: (error) => {
        signal?.removeEventListener("abort", aborted);
        reject(error);
      },
    });
    to.postMessage(JSON.stringify({ type: "fetch", id, url, method: init.method ?? "GET", headers, body, binary }));
  });
}

function fromBase64(text: string): ArrayBuffer {
  const raw = atob(text);
  const bytes = new Uint8Array(raw.length);
  for (let i = 0; i < raw.length; i++) bytes[i] = raw.charCodeAt(i);
  return bytes.buffer;
}

/** Requests waiting on the shell, for tests. */
export const waitingCount = () => waiting.size;
