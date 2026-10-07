// The page the shell shows: the LG app, built, copied into assets/web where the WebView
// opens it (file:///pkg/assets/web/index.html). Run before every Vega build.
import { cpSync, existsSync, rmSync } from "node:fs";
import { execSync } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const webos = join(here, "..", "..", "webos");
const out = join(here, "..", "assets", "web");

if (!existsSync(join(webos, "node_modules"))) execSync("npm ci", { cwd: webos, stdio: "inherit" });
execSync("npm run build", { cwd: webos, stdio: "inherit" });
rmSync(out, { recursive: true, force: true });
cpSync(join(webos, "dist"), out, { recursive: true });
console.log(`The page is in ${out}.`);
