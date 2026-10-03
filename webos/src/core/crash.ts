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

/** Everything the app didn't catch itself, kept. */
export function watchForProblems(store: Store, target: Pick<Window, "addEventListener"> = window) {
  target.addEventListener("error", (event) => recordProblem(store, (event as ErrorEvent).error ?? (event as ErrorEvent).message));
  target.addEventListener("unhandledrejection", (event) => recordProblem(store, (event as PromiseRejectionEvent).reason));
}
