import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { fileURLToPath } from 'node:url';

const here = (p: string) => fileURLToPath(new URL(p, import.meta.url));

// Vega's own screens (../src), with react-native as react-native-web and Amazon's native
// pieces stood in for (shims/).
export default defineConfig({
  root: here('.'),
  plugins: [react()],
  define: { __DEV__: 'false', 'process.env.NODE_ENV': '"production"', global: 'globalThis' },
  resolve: {
    extensions: ['.web.tsx', '.web.ts', '.tsx', '.ts', '.web.js', '.js'],
    alias: [
      { find: /^react-native$/, replacement: here('shims/rn.ts') },
      { find: /^@amazon-devices\/react-native-kepler$/, replacement: here('shims/kepler.tsx') },
      { find: '@amazon-devices/react-native-svg', replacement: here('node_modules/react-native-svg') },
      { find: '@amazon-devices/react-native-async-storage__async-storage', replacement: here('shims/storage.ts') },
      // The player's engine itself, rather than Amazon's media pieces under it.
      { find: /^(\.\.?\/)+(player\/)?engine$/, replacement: here('shims/engine.tsx') },
      { find: 'react-native-url-polyfill/auto', replacement: here('shims/empty.ts') },
      { find: /^react$/, replacement: here('node_modules/react') },
      { find: /^react-dom$/, replacement: here('node_modules/react-dom') },
    ],
  },
  server: { port: 5199, fs: { allow: [here('..')] } },
  // The libraries' web builds (their .web.js files), as the app's own .web files are.
  optimizeDeps: { esbuildOptions: { loader: { '.js': 'jsx' }, resolveExtensions: ['.web.tsx', '.web.ts', '.web.js', '.tsx', '.ts', '.js', '.jsx', '.json'] } },
});
