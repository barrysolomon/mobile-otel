const {getDefaultConfig, mergeConfig} = require('@react-native/metro-config');
const path = require('path');

// Monorepo-style setup: watch the local @dash0/mobile-react-native package so
// edits in ../../../packages/react-native/src propagate without publishing.
const workspaceRoot = path.resolve(__dirname, '../../..');
const packageRoot = path.resolve(workspaceRoot, 'packages/react-native');
const appNodeModules = path.resolve(__dirname, 'node_modules');

// The bridge package carries its OWN nested node_modules/react-native (a stale
// version pinned for its build/codegen). Because we watch packageRoot and used
// to add its node_modules to the resolver, Metro could resolve `react-native`
// (and `react`) from that nested copy instead of the app's. When the two RN
// versions differ, the JS bundle's core module specs (e.g. PlatformConstants)
// no longer match the native binary built from the app's RN, and the app dies
// at launch with `TurboModuleRegistry.getEnforcing('PlatformConstants') could
// not be found`. Force the singletons to the app's single copy and forbid
// resolving the bridge's nested react-native/react.
const config = {
  watchFolders: [packageRoot],
  resolver: {
    // Resolve from the APP's node_modules only — not the bridge's nested copy.
    nodeModulesPaths: [appNodeModules],
    extraNodeModules: {
      '@dash0/mobile-react-native': packageRoot,
      // Pin the framework singletons so any require from the bridge src
      // resolves to the app's react-native / react.
      'react-native': path.resolve(appNodeModules, 'react-native'),
      react: path.resolve(appNodeModules, 'react'),
    },
    // Belt-and-suspenders: never let Metro pull the bridge's nested copies.
    blockList: [
      new RegExp(`^${path.resolve(packageRoot, 'node_modules/react-native')}/.*$`),
      new RegExp(`^${path.resolve(packageRoot, 'node_modules/react')}/.*$`),
    ],
  },
};

module.exports = mergeConfig(getDefaultConfig(__dirname), config);
