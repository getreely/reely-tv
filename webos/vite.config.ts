import { defineConfig, type Plugin } from "vite";
import preact from "@preact/preset-vite";

/*
 * An installed webOS app is opened from file://, where Chromium won't load a module
 * script. One classic script, deferred, is what every TV runs.
 */
const classicScript = (): Plugin => ({
  name: "classic-script",
  apply: "build",
  enforce: "post",
  transformIndexHtml(html) {
    return html.replace(/<script type="module" crossorigin/g, "<script defer").replace(/ crossorigin/g, "");
  },
});

// LG TVs from 2020 on (webOS 5) run Chromium 68: what's written here is built down to that.
export default defineConfig({
  plugins: [preact(), classicScript()],
  base: "./",
  build: {
    target: "chrome68",
    outDir: "dist",
    assetsInlineLimit: 0,
    modulePreload: false,
    rollupOptions: { output: { format: "iife", inlineDynamicImports: true } },
  },
});
