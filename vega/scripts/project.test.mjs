// The project's own files agree with each other, as the Vega build needs them to.
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync, existsSync } from "node:fs";

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

test("React Native 0.83's runtime, and the media services the player needs", () => {
  const manifest = read("manifest.toml");
  assert.ok(manifest.includes('runtime-module = "/com.amazon.kepler.runtime.react_native_kepler_4@IReactNativeKepler_0"'));
  for (const id of ["com.amazon.media.server", "com.amazon.mediabuffer.service", "com.amazon.mediatransform.service", "com.amazon.audio.stream", "com.amazon.inputmethod.service"]) {
    assert.ok(manifest.includes(`id = "${id}"`), id);
  }
});

test("Geist, a file per weight named for its family", () => {
  for (const weight of ["Regular", "Medium", "SemiBold", "Bold"]) assert.ok(existsSync(new URL(`../assets/fonts/Geist-${weight}.ttf`, import.meta.url)), weight);
});

test("Amazon's hls.js is checked against the release it was written for", () => {
  const script = read("scripts/hlsjs.sh");
  assert.match(script, /SHA256="[0-9a-f]{64}"/);
  assert.match(read(".gitignore"), /^src\/hls\/$/m);
});

test("the version the app shows is package.json's", () => {
  const pkg = JSON.parse(read("package.json"));
  assert.ok(read("src/app/version.ts").includes(`'${pkg.version}'`));
});
