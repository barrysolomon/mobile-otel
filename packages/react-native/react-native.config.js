// Autolinking config for @dash0/mobile-react-native.
// Consumed by `react-native config` during build of host apps.

module.exports = {
  dependency: {
    platforms: {
      ios: {
        podspecPath: require('path').join(__dirname, 'Dash0Mobile.podspec'),
      },
      android: {
        sourceDir: './android',
        packageImportPath: 'import com.dash0.mobile.reactnative.Dash0MobilePackage;',
        packageInstance: 'new Dash0MobilePackage()',
      },
    },
  },
};
