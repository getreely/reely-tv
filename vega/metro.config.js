const { getDefaultConfig, mergeConfig } = require('@react-native/metro-config');

// The page (assets/web) is the LG app's build, not Metro's: Metro bundles only the shell.
module.exports = mergeConfig(getDefaultConfig(__dirname), {});
