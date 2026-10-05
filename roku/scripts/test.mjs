// The Roku app's logic, run in a BrightScript interpreter: each tests/*.brs with the
// libraries it tests, a line per failure, and a count at the end.
import { execFileSync } from "node:child_process";
import { mkdirSync, readFileSync, readdirSync, writeFileSync } from "node:fs";

const libs = readdirSync("source/lib").filter((f) => f.endsWith(".brs")).map((f) => readFileSync(`source/lib/${f}`, "utf8"));
const harness = readFileSync("tests/harness.brs", "utf8");
mkdirSync("build", { recursive: true });
let failed = 0;
let passed = 0;
for (const file of readdirSync("tests").filter((f) => f.endsWith(".test.brs"))) {
  const body = readFileSync(`tests/${file}`, "utf8");
  writeFileSync("build/run.brs", [harness, ...libs, body].join("\n"));
  let out;
  try {
    out = execFileSync("npx", ["brs-cli", "build/run.brs"], { encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] });
  } catch (error) {
    console.error(`${file}: didn't run\n${error.stdout ?? ""}${error.stderr ?? ""}`);
    failed++;
    continue;
  }
  for (const line of out.split("\n")) {
    if (line.startsWith("FAIL")) { failed++; console.error(`${file}: ${line}`); }
    else if (line.startsWith("PASS")) passed++;
    // The interpreter reports a script error and carries on; that's a failure too.
    else if (/error|crash|syntax|unexpected/i.test(line) && !line.includes("Finished")) { failed++; console.error(`${file}: ${line}`); }
  }
  if (!out.includes("Finished")) { failed++; console.error(`${file}: didn't finish\n${out}`); }
}
// What a Roku won't make on the scene's thread (the simulator will): roUrlTransfer, in any
// script a component other than a Task runs. Made there, it comes back invalid, after a long
// wait each time.
const offThread = /CreateObject\("(roUrlTransfer|roSocketAddress|roStreamSocket|roDataGramSocket)"\)/;
const walk = (dir) => readdirSync(dir, { withFileTypes: true }).flatMap((e) => e.isDirectory() ? walk(`${dir}/${e.name}`) : [`${dir}/${e.name}`]);
for (const xml of walk("components").filter((f) => f.endsWith(".xml"))) {
  const text = readFileSync(xml, "utf8");
  if (/extends="Task"/.test(text)) continue;
  for (const [, uri] of text.matchAll(/<script [^>]*uri="pkg:\/([^"]+)"/g)) {
    const source = readFileSync(uri, "utf8").split("\n").filter((l) => !l.trim().startsWith("'")).join("\n");
    if (offThread.test(source)) { failed++; console.error(`${xml}: ${uri} makes ${source.match(offThread)[1]}, which a Roku won't on the scene's thread`); }
    else passed++;
  }
}
console.log(`${passed} passed, ${failed} failed`);
process.exit(failed ? 1 : 0);
