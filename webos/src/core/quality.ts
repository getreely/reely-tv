/** The badges that say what a file is: resolution, HDR, sound. As the Android app has them. */
export function qualityBadges(
  resolution: string | null | undefined,
  audioChannels: number,
  dolbyVision = false,
  transfer: string | null = null,
): string[] {
  const r = resolution?.toLowerCase();
  const size = r === "4k" || r === "2160" ? "4K" : r === "1080" || r === "720" ? "HD" : r === "sd" || r === "576" || r === "480" ? "SD" : null;
  const range = dolbyVision ? "Dolby Vision" : eq(transfer, "smpte2084") ? "HDR10" : eq(transfer, "arib-std-b67") ? "HLG" : null;
  const sound = audioChannels >= 8 ? "7.1" : audioChannels >= 6 ? "5.1" : audioChannels === 2 ? "Stereo" : audioChannels === 1 ? "Mono" : null;
  return [size, range, sound].filter((x): x is string => x !== null);
}

/** "4K Dolby Vision", "1080p": one of several copies of a title. */
export function versionLabel(resolution: string | null | undefined, dolbyVision = false, transfer: string | null = null): string {
  const r = resolution?.toLowerCase();
  const size = !r ? "Other" : r === "4k" || r === "2160" ? "4K" : r === "sd" ? "SD" : /^\d+$/.test(r) ? `${r}p` : r.toUpperCase();
  const range = dolbyVision ? "Dolby Vision" : eq(transfer, "smpte2084") ? "HDR10" : eq(transfer, "arib-std-b67") ? "HLG" : null;
  return [size, range].filter(Boolean).join(" ");
}

/** "HEVC · TrueHD 7.1 · 42 Mbps · 58.1 GB". */
export function versionDetail(
  videoCodec: string | null | undefined,
  audioCodec: string | null | undefined,
  audioChannels: number,
  bitrateKbps: number,
  sizeBytes: number,
): string | null {
  const v = videoCodec?.toLowerCase();
  const video = !v ? null : ({ hevc: "HEVC", h265: "HEVC", h264: "H.264", mpeg2video: "MPEG-2", mpeg4: "MPEG-4", vc1: "VC-1" } as Record<string, string>)[v] ?? videoCodec!.toUpperCase();
  const a = audioCodec?.toLowerCase();
  const name = !a
    ? null
    : ({ truehd: "TrueHD", eac3: "Dolby Digital Plus", ac3: "Dolby Digital", dca: "DTS", dts: "DTS", "dca-ma": "DTS-HD MA", "dts-hd ma": "DTS-HD MA", aac: "AAC", flac: "FLAC", opus: "Opus", mp3: "MP3" } as Record<string, string>)[a] ?? audioCodec!.toUpperCase();
  const layout = audioChannels >= 8 ? "7.1" : audioChannels >= 6 ? "5.1" : audioChannels === 2 ? "Stereo" : null;
  const sound = name ? [name, layout].filter(Boolean).join(" ") : null;
  const rate = bitrateKbps > 0 ? (bitrateKbps >= 1000 ? `${Math.floor((bitrateKbps + 500) / 1000)} Mbps` : `${bitrateKbps} kbps`) : null;
  const size = sizeBytes > 0 ? (sizeBytes >= 1e9 ? `${(sizeBytes / 1e9).toFixed(1)} GB` : `${Math.floor(sizeBytes / 1e6)} MB`) : null;
  const text = [video, sound, rate, size].filter(Boolean).join(" · ");
  return text || null;
}

function eq(a: string | null | undefined, b: string) {
  return !!a && a.toLowerCase() === b;
}

/** "2h 18m", "42m". */
export function formatDuration(ms: number): string {
  if (ms <= 0) return "";
  const total = Math.floor(ms / 60_000);
  const hours = Math.floor(total / 60);
  const minutes = total % 60;
  return hours > 0 ? `${hours}h ${minutes}m` : `${minutes}m`;
}

/** "Sep 16, 2008" from "2008-09-16"; null for anything that isn't a whole date. */
export function formatAirDate(date: string | null | undefined, locale?: string): string | null {
  const text = date?.slice(0, 10);
  if (!text || !/^\d{4}-\d{2}-\d{2}$/.test(text)) return null;
  const [y, m, d] = text.split("-").map(Number);
  const when = new Date(Date.UTC(y, m - 1, d));
  if (isNaN(when.getTime()) || when.getUTCDate() !== d) return null;
  return when.toLocaleDateString(locale, { year: "numeric", month: "short", day: "numeric", timeZone: "UTC" });
}
