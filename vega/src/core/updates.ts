import { askJson } from "./http";

/*
 * Whether there's a newer Reely for Vega (Updater.kt, for Vega): the newest vega-v…
 * release on GitHub that isn't a test build. Vega gives an app no way to install a new
 * version of itself, so this only finds it; installing is done from a computer.
 */

export const RELEASES = "https://api.github.com/repos/getreely/reely-tv/releases?per_page=40";

export interface VegaUpdate {
  version: string;
  /** The release's page, where the packages are. */
  url: string;
  /** The Fire TV stick's package, when it says how big it is. */
  sizeBytes: number | null;
}

/** 0.58.0 against 0.57.30, a part at a time. */
export function newer(a: string, b: string): boolean {
  const pa = a.split(".").map((n) => parseInt(n, 10) || 0);
  const pb = b.split(".").map((n) => parseInt(n, 10) || 0);
  for (let i = 0; i < Math.max(pa.length, pb.length); i++) {
    if ((pa[i] ?? 0) !== (pb[i] ?? 0)) return (pa[i] ?? 0) > (pb[i] ?? 0);
  }
  return false;
}

interface Release { tag_name?: string; prerelease?: boolean; draft?: boolean; html_url?: string; assets?: Array<{ name?: string; size?: number }> }

/** The newest Vega release in [releases], or null when there's none. */
export function latestVega(releases: Release[]): VegaUpdate | null {
  let best: VegaUpdate | null = null;
  for (const r of releases) {
    const m = /^vega-v(\d+(?:\.\d+)*)$/.exec(r.tag_name ?? "");
    if (!m || r.prerelease || r.draft) continue;
    if (best && !newer(m[1], best.version)) continue;
    const pkg = r.assets?.find((a) => a.name === "reely-vega-armv7.vpkg");
    best = { version: m[1], url: r.html_url ?? `https://github.com/getreely/reely-tv/releases/tag/${r.tag_name}`, sizeBytes: pkg?.size ?? null };
  }
  return best;
}

export async function checkVega(): Promise<VegaUpdate | null> {
  const releases = await askJson<Release[]>(RELEASES, { headers: { Accept: "application/vnd.github+json" }, failure: "GitHub didn't answer. Try again in a moment." });
  return Array.isArray(releases) ? latestVega(releases) : null;
}
