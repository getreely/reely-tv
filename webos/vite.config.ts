import { defineConfig } from "vite";
import preact from "@preact/preset-vite";

// LG TVs from 2020 on (webOS 5) run Chromium 68: what's written here is built down to that.
export default defineConfig({
  plugins: [preact()],
  base: "./",
  build: {
    target: "chrome68",
    outDir: "dist",
    assetsInlineLimit: 0,
  },
});
