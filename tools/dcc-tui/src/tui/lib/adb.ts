import { execFile } from 'node:child_process';
import type { Device } from '../types.js';

function run(cmd: string, args: string[], timeoutMs = 4000): Promise<{ ok: boolean; out: string }> {
  return new Promise((resolve) => {
    const child = execFile(cmd, args, { timeout: timeoutMs }, (err, stdout) => {
      resolve({ ok: !err, out: (stdout ?? '').trim() });
    });
    setTimeout(() => child.kill('SIGKILL'), timeoutMs + 250).unref?.();
  });
}

/** Parse `adb devices` lines into `{serial, status}` rows. Skips the header. */
async function listAdbDevices(): Promise<Array<{ serial: string; status: string }>> {
  const r = await run('adb', ['devices']);
  if (!r.ok) return [];
  return r.out
    .split('\n')
    .slice(1)
    .map((l) => l.trim())
    .filter(Boolean)
    .map((line) => {
      const [serial = '', status = ''] = line.split(/\s+/);
      return { serial, status };
    });
}

/** Read getprop for a serial. Tolerates missing or non-booted devices. */
async function getProp(serial: string, prop: string): Promise<string> {
  const r = await run('adb', ['-s', serial, 'shell', 'getprop', prop], 2000);
  return r.out.replace(/\r/g, '').trim();
}

/** List AVDs known to the local SDK (offline metadata only — fast). */
async function listAvds(): Promise<string[]> {
  const r = await run('emulator', ['-list-avds'], 3000);
  if (!r.ok) return [];
  return r.out.split('\n').map((l) => l.trim()).filter(Boolean);
}

/**
 * Enumerate every device the user could plausibly target:
 *   - All running adb serials (status === 'device') with model + API + booted flag
 *   - All AVDs not currently booted (prefixed `avd:<name>`)
 *
 * The TUI's DeviceScreen treats both as selectable. Scenarios coerce `avd:`
 * entries into "boot this AVD first, then proceed".
 */
export async function enumerateDevices(): Promise<Device[]> {
  const [adbList, avds] = await Promise.all([listAdbDevices(), listAvds()]);

  // Build entries for booted/online serials. Probe model/api in parallel.
  const boots = await Promise.all(
    adbList
      .filter((d) => d.status === 'device')
      .map(async (d): Promise<Device> => {
        const [model, api, bootCompleted, avdName] = await Promise.all([
          getProp(d.serial, 'ro.product.model'),
          getProp(d.serial, 'ro.build.version.sdk'),
          getProp(d.serial, 'sys.boot_completed'),
          getProp(d.serial, 'ro.kernel.qemu.avd_name'),
        ]);
        return {
          serial: d.serial,
          avd: avdName,
          model: model || '(unknown)',
          api: api || '?',
          booted: bootCompleted === '1',
        };
      }),
  );

  // Build entries for AVDs that aren't currently booted under any serial.
  const bootedAvdNames = new Set(boots.map((b) => b.avd).filter(Boolean));
  const offlineAvds: Device[] = avds
    .filter((avd) => !bootedAvdNames.has(avd))
    .map((avd) => ({ serial: '', avd, model: '(not booted)', api: '?', booted: false }));

  return [...boots, ...offlineAvds];
}

/**
 * Identity key for a Device in the AppState.runConfig.devices set.
 * Booted devices are keyed by serial; offline AVDs by `avd:<name>`.
 */
export function deviceKey(d: Device): string {
  return d.serial !== '' ? d.serial : `avd:${d.avd}`;
}

/** Pretty-print a device row for list screens. */
export function deviceRow(d: Device): string {
  if (d.serial) {
    return `${d.serial.padEnd(16)} ${d.model.padEnd(20)} API ${d.api.padEnd(3)} ${d.avd ? `(${d.avd})` : ''}`;
  }
  return `${('avd:' + d.avd).padEnd(16)} ${'(not booted)'.padEnd(20)} ${' '.repeat(7)} `;
}
