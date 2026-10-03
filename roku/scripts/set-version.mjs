// Gives the Roku app the release's version, in both places Roku and npm read it.
import { readFileSync, writeFileSync } from "node:fs";

const version = process.argv[2];
const parts = /^(\d+)\.(\d+)\.(\d+)$/.exec(version ?? "");
if (!parts) {
  console.error(`A version like 0.51.0, not "${version}".`);
  process.exit(1);
}
let manifest = readFileSync("manifest", "utf8");
["major_version", "minor_version", "build_version"].forEach((key, i) => {
  manifest = manifest.replace(new RegExp(`^${key}=\\d+$`, "m"), `${key}=${parts[i + 1]}`);
});
writeFileSync("manifest", manifest);
const pkg = JSON.parse(readFileSync("package.json", "utf8"));
pkg.version = version;
writeFileSync("package.json", JSON.stringify(pkg, null, 2) + "\n");
console.log(`Roku app is ${version}.`);
