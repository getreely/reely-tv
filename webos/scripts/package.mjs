// Builds reely-lg.ipk from dist/: LG's own packager, then the name the downloads use.
import { execFileSync } from "node:child_process";
import { readFileSync, renameSync, mkdirSync, readdirSync, rmSync } from "node:fs";

const info = JSON.parse(readFileSync("dist/appinfo.json", "utf8"));
const pkg = JSON.parse(readFileSync("package.json", "utf8"));
if (info.version !== pkg.version) {
  console.error(`appinfo.json says ${info.version} but package.json says ${pkg.version}: make them match.`);
  process.exit(1);
}
rmSync("out", { recursive: true, force: true });
mkdirSync("out");
execFileSync("npx", ["ares-package", "--no-minify", "dist", "--outdir", "out"], { stdio: "inherit" });
const built = readdirSync("out").find((f) => f.endsWith(".ipk"));
if (!built) throw new Error("ares-package made no .ipk");
renameSync(`out/${built}`, "out/reely-lg.ipk");
console.log(`out/reely-lg.ipk (${info.id} ${info.version})`);
