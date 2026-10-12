const { getDefaultConfig, mergeConfig } = require('@react-native/metro-config');

const defaults = getDefaultConfig(__dirname);

// Amazon's hls.js comes as an ES module file (.mjs): read as source, like the app's own.
module.exports = mergeConfig(defaults, {
  resolver: { sourceExts: [...defaults.resolver.sourceExts, 'mjs'] },
});
