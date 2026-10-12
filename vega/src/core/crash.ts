import type { Store } from "./storage";

/*
 * What went wrong, kept on the TV for Settings to show, as the Fire TV keeps its problem
 * report. Only the last one; nothing is sent anywhere.
 */

export interface Problem {
  /** Epoch milliseconds. */
  at: number;
  message: string;
  detail: string | null;
}

const KEY = "problem";

export function recordProblem(store: Store, error: unknown, now = Date.now()) {
  const e = error instanceof Error ? error : null;
  const message = e?.message || (typeof error === "string" ? error : "Something went wrong");
  const problem: Problem = { at: now, message: message.slice(0, 500), detail: e?.stack?.slice(0, 4000) ?? null };
  store.setJson(KEY, problem);
  return problem;
}

export const lastProblem = (store: Store) => store.json<Problem | null>(KEY, null);
export const clearProblem = (store: Store) => store.remove(KEY);

/** Everything the app didn't catch itself, kept: React Native's last-chance handler, passed on. */
export function watchForProblems(store: Store) {
  const handlers = (globalThis as unknown as { ErrorUtils?: { getGlobalHandler(): (e: unknown, fatal?: boolean) => void; setGlobalHandler(h: (e: unknown, fatal?: boolean) => void): void } }).ErrorUtils;
  if (!handlers) return;
  const previous = handlers.getGlobalHandler();
  handlers.setGlobalHandler((error, fatal) => {
    recordProblem(store, error);
    previous(error, fatal);
  });
}
