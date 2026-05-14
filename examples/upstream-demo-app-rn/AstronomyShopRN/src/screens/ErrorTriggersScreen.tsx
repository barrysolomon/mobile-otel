import React from 'react';
import {
  ScrollView,
  StyleSheet,
  Text,
  TouchableOpacity,
  View,
} from 'react-native';
import {Dash0Mobile} from '@dash0/mobile-react-native';

/**
 * Demo screen surfacing every flavor of error/crash the RN bridge + native
 * SDKs are supposed to capture. Each button below produces a distinct OTel
 * signal — pair the names here with the rows in
 * `mobile-otel/docs/reference/TELEMETRY_SIGNALS.md`.
 *
 * Cross-platform parity: the Android `ErrorTriggersScreen.kt` and iOS
 * `ErrorTriggersView.swift` provide the same buttons with the same labels
 * and behavior. See `mobile-otel/docs/RN_ANDROID_IOS_PARITY.md`.
 */
export function ErrorTriggersScreen(): React.ReactElement {
  const emitHandledError = (message: string, error?: unknown): void => {
    Dash0Mobile.log('app.error', {
      'event.name': 'app.error',
      'exception.message': message,
      'exception.type':
        error instanceof Error ? error.name : typeof error === 'string' ? 'string' : 'unknown',
    }, /* severity = ERROR */ 17);
  };

  return (
    <ScrollView contentContainerStyle={styles.container}>
      <Text style={styles.header}>Error Triggers</Text>
      <Text style={styles.subtitle}>
        Each button emits a different OTel signal. Tap one, then check Dash0
        (filtered by service.name = otel-rn-astronomy-shop) for the resulting
        telemetry.
      </Text>

      <SectionHeader>Handled errors — process keeps running</SectionHeader>

      <Trigger
        label="Log a handled error"
        description="Emits app.error log record. severity=ERROR."
        testID="trigger.handled_error"
        onPress={() => emitHandledError('manual button: Log a handled error')}
      />

      <Trigger
        label="Catch a divide-by-zero"
        description="In JS, 10/0 = Infinity. We treat that as a recordable anomaly via app.error."
        testID="trigger.divide_by_zero_handled"
        onPress={() => {
          const zero = Date.now() - Date.now();
          const result = 10 / zero;
          if (!Number.isFinite(result)) {
            emitHandledError(`10 / 0 = ${result} (non-finite — divide-by-zero handled)`);
          }
        }}
      />

      <Trigger
        label="Trigger HTTP 500"
        description="fetch() httpbin.org/status/500. The JS XHR auto-instrumentation emits http.error → matches the http-error-detector policy → flushes the buffer."
        testID="trigger.http_500"
        onPress={() => {
          fetch('https://httpbin.org/status/500')
            .then(r => console.log('HTTP 500 trigger returned status=', r.status))
            .catch(err => emitHandledError(`HTTP 500 trigger failed: ${String(err)}`, err));
        }}
      />

      <SectionHeader>Unhandled — fires the JS error global handler</SectionHeader>

      <Trigger
        label="Throw uncaught Error"
        description="Unhandled JS throw flows through ErrorUtils global handler → app.error log record with severity=FATAL (isFatal=true)."
        testID="trigger.crash_throw"
        isDanger
        onPress={() => {
          setTimeout(() => {
            throw new Error('Demo Error Triggers: uncaught JS Error');
          }, 0);
        }}
      />

      <Trigger
        label="Throw unhandled promise rejection"
        description="Unhandled promise rejection — captured by the unhandledRejection instrumentation."
        testID="trigger.unhandled_rejection"
        isDanger
        onPress={() => {
          void Promise.reject(new Error('Demo Error Triggers: unhandled rejection'));
        }}
      />

      <View style={styles.spacer} />
    </ScrollView>
  );
}

function SectionHeader({children}: {children: React.ReactNode}): React.ReactElement {
  return (
    <View style={styles.sectionHeaderWrap}>
      <View style={styles.divider} />
      <Text style={styles.sectionHeader}>{children}</Text>
    </View>
  );
}

function Trigger({
  label,
  description,
  testID,
  isDanger,
  onPress,
}: {
  label: string;
  description: string;
  testID: string;
  isDanger?: boolean;
  onPress: () => void;
}): React.ReactElement {
  return (
    <TouchableOpacity
      testID={testID}
      accessibilityLabel={testID}
      onPress={onPress}
      style={[styles.button, isDanger ? styles.dangerButton : styles.primaryButton]}>
      <Text style={styles.buttonLabel}>{label}</Text>
      <Text style={styles.buttonDescription}>{description}</Text>
    </TouchableOpacity>
  );
}

const styles = StyleSheet.create({
  container: {padding: 16, gap: 12},
  header: {fontSize: 24, fontWeight: '700'},
  subtitle: {fontSize: 12, color: '#666'},
  sectionHeaderWrap: {marginTop: 8},
  divider: {height: 1, backgroundColor: '#ddd', marginBottom: 8},
  sectionHeader: {fontSize: 16, fontWeight: '600'},
  button: {padding: 12, borderRadius: 8},
  primaryButton: {backgroundColor: '#007AFF'},
  dangerButton: {backgroundColor: '#B00020'},
  buttonLabel: {color: '#fff', fontSize: 15, fontWeight: '700'},
  buttonDescription: {color: '#fff', fontSize: 12, marginTop: 4},
  spacer: {height: 24},
});
