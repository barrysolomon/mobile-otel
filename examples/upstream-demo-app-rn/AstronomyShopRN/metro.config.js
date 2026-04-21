const {getDefaultConfig, mergeConfig} = require('@react-native/metro-config');
const path = require('path');

// Monorepo-style setup: watch the local @dash0/mobile-react-native package so
// edits in ../../../packages/react-native/src propagate without publishing.
const workspaceRoot = path.resolve(__dirname, '../../..');
const packageRoot = path.resolve(workspaceRoot, 'packages/react-native');

const config = {
  watchFolders: [packageRoot],
  resolver: {
    nodeModulesPaths: [
      path.resolve(__dirname, 'node_modules'),
      path.resolve(packageRoot, 'node_modules'),
    ],
    extraNodeModules: {
      '@dash0/mobile-react-native': packageRoot,
    },
  },
};

module.exports = mergeConfig(getDefaultConfig(__dirname), config);
