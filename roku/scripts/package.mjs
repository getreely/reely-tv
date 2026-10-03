// reely-roku.zip: the app as Roku's developer mode installs it (manifest at the root).
import { execFileSync } from "node:child_process";
import { mkdirSync, readFileSync, rmSync } from "node:fs";

const manifest = readFileSync("manifest", "utf8");
const version = (key) => manifest.match(new RegExp(`^${key}=(\\d+)`, "m"))?.[1];
const pkg = JSON.parse(readFileSync("package.json", "utf8"));
const said = `${version("major_version")}.${version("minor_version")}.${version("build_version")}`;
if (said !== pkg.version) {
  console.error(`manifest says ${said} but package.json says ${pkg.version}: make them match.`);
  process.exit(1);
}
execFileSync("npx", ["bsc", "--project", "bsconfig.json"], { stdio: "inherit" });
rmSync("out", { recursive: true, force: true });
mkdirSync("out");
execFileSync("zip", ["-qr", "../../out/reely-roku.zip", "."], { cwd: "build/staging", stdio: "inherit" });
console.log(`out/reely-roku.zip (${pkg.version})`);
