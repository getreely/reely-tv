// The project's own files agree with each other, as the Vega build needs them to.
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

const read = (f) => readFileSync(new URL(`../${f}`, import.meta.url), "utf8");

test("app.json names the manifest's interactive component", () => {
  const app = JSON.parse(read("app.json"));
  const manifest = read("manifest.toml");
  assert.match(manifest, new RegExp(`\\[\\[components\\.interactive\\]\\]\\s*\\nid = "${app.name.replace(/\./g, "\\.")}"`));
  assert.match(manifest, new RegExp(`id = "${app.name.replace(/\.main$/, "").replace(/\./g, "\\.")}"`));
});

test("the version is the same in package.json and the manifest", () => {
  const pkg = JSON.parse(read("package.json"));
  assert.match(read("manifest.toml"), new RegExp(`version = "${pkg.version.replace(/\./g, "\\.")}"`));
});

test("the WebView's services are all wanted", () => {
  const manifest = read("manifest.toml");
  for (const id of ["com.amazon.webview.renderer_service", "com.amazon.media.server", "com.amazon.inputmethod.service", "com.amazon.audio.stream"]) {
    assert.ok(manifest.includes(`id = "${id}"`), id);
  }
});

test("the shell opens the page bundle-web puts in assets", () => {
  assert.ok(read("src/App.tsx").includes("file:///pkg/assets/web/index.html"));
  assert.ok(read("scripts/bundle-web.mjs").includes(`"assets", "web"`));
});
