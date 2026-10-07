import { shellFetch } from "./bridge";
import { isVega } from "./platform";

/**
 * Asking servers things, with a time limit: a server that never answers mustn't leave a
 * screen waiting for ever. Errors come out as sentences somebody can read.
 */
export class HttpError extends Error {
  constructor(message: string, readonly status: number) {
    super(message);
  }
}

export interface Ask {
  method?: "GET" | "POST" | "PUT" | "DELETE";
  headers?: Record<string, string>;
  body?: string | URLSearchParams;
  timeoutMs?: number;
  /** What to say when the answer is a refusal; the status is kept on the error. */
  failure?: string;
}

/*
 * On Vega, the page is opened from the app's own files, and a server that doesn't allow
 * other sites (an IPTV provider's panel, often) refuses it: the browser says only that it
 * couldn't connect. The shell asks instead, which it can; and having had to once for a
 * server, it asks for it from then on. A request cancelled for taking too long isn't asked
 * again.
 */
const viaShell = new Set<string>();

function originOf(url: string): string {
  try {
    return new URL(url).origin;
  } catch {
    return url;
  }
}

/** Whether [url]'s server is one the shell asks, having refused the page. */
export const askedByShell = (url: string) => viaShell.has(originOf(url));
/** [url]'s server refused the page: the shell asks it from now on. */
export const askViaShell = (url: string) => void viaShell.add(originOf(url));

export const vegaFetch = (local: typeof fetch, remote: typeof shellFetch): typeof fetch => async (input, init) => {
  const url = typeof input === "string" ? input : input instanceof URL ? input.href : input.url;
  const origin = originOf(url);
  if (viaShell.has(origin)) return remote(url, init);
  try {
    return await local(input, init);
  } catch (error) {
    if (init?.signal?.aborted) throw error;
    const answer = await remote(url, init);
    viaShell.add(origin);
    return answer;
  }
};

/** Servers the shell is asking for, for tests. */
export const forgetShellServers = () => viaShell.clear();

/** The fetch in use: the browser's (with the shell behind it on Vega), or a test's. */
const onVega = vegaFetch((input, init) => fetch(input, init), shellFetch);
export let fetcher: typeof fetch = (input, init) => (isVega() ? onVega(input, init) : fetch(input, init));

export function useFetcher(next: typeof fetch) {
  fetcher = next;
}

export async function ask(url: string, options: Ask = {}): Promise<Response> {
  const controller = typeof AbortController !== "undefined" ? new AbortController() : undefined;
  const timer = controller ? setTimeout(() => controller.abort(), options.timeoutMs ?? 20_000) : undefined;
  try {
    const response = await fetcher(url, {
      method: options.method ?? "GET",
      headers: options.headers,
      body: options.body,
      signal: controller?.signal,
    });
    if (!response.ok) throw new HttpError(options.failure ?? "That didn't work. Try again.", response.status);
    return response;
  } catch (error) {
    if (error instanceof HttpError) throw error;
    throw new HttpError(options.failure ?? "Couldn't connect. Check the network and try again.", 0);
  } finally {
    if (timer) clearTimeout(timer);
  }
}

export async function askJson<T = any>(url: string, options: Ask = {}): Promise<T> {
  const response = await ask(url, options);
  const text = await response.text();
  try {
    return JSON.parse(text) as T;
  } catch {
    throw new HttpError(options.failure ?? "The answer didn't make sense. Try again.", response.status);
  }
}

/** A sentence for whatever went wrong. */
export function readable(error: unknown): string {
  if (error instanceof Error && error.message) return error.message;
  return "Something went wrong. Try again.";
}
