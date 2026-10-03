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

/** The fetch in use: the browser's, or a test's. */
export let fetcher: typeof fetch = (input, init) => fetch(input, init);

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
