// Gives the LG app the release's version, in both places webOS and npm read it.
import { readFileSync, writeFileSync } from "node:fs";

const version = process.argv[2];
if (!/^\d+\.\d+\.\d+$/.test(version ?? "")) {
  console.error(`A version like 0.51.0, not "${version}".`);
  process.exit(1);
}
for (const file of ["package.json", "public/appinfo.json"]) {
  const json = JSON.parse(readFileSync(file, "utf8"));
  json.version = version;
  writeFileSync(file, JSON.stringify(json, null, 2) + "\n");
}
console.log(`LG app is ${version}.`);
