import { spawn, type ChildProcess } from 'node:child_process';
import type { RunConfig } from '../types.js';

export interface SpawnHandle {
  /** Logical run key — usually the device serial, or 'default' for non-device runs. */
  key: string;
  child: ChildProcess;
  /** Each new chunk of stdout/stderr from the child, line-buffered. */
  lines: string[];
  /** Resolved when the child exits. */
  done: Promise<number>;
  done_resolve?: (code: number) => void;
  exited: boolean;
  exitCode?: number;
}

/**
 * Resolve the mobile-otel repo root from the TUI's cwd. The TUI lives at
 * tools/dcc-tui under the repo, so cwd ending in /tools/dcc-tui means trim
 * two segments. If invoked from elsewhere we fall back to the current dir
 * — scenarios will fail with clear "file not found" output, which is the
 * right signal.
 */
export function resolveRepoRoot(): string {
  const cwd = process.cwd();
  return cwd.replace(/\/tools\/dcc-tui\/?$/, '');
}

/**
 * Run `command` once per selected device (or once total if no devices selected).
 * Each child gets a SERIAL env var so existing bash helpers (find_emulator)
 * can target it, plus a DCC_RUN_KEY for logging.
 *
 * Callers receive an array of SpawnHandle so they can render per-device
 * output in side-by-side or stacked layouts.
 *
 * `cwd` should be the mobile-otel repo root so bash paths like
 * `scripts/test/demo-control-center.sh` resolve. Use resolveRepoRoot().
 */
export function fanOut(command: string, args: string[], config: RunConfig, cwd: string): SpawnHandle[] {
  const keys = config.devices.length > 0 ? config.devices : ['default'];
  const runners = config.parallel ? keys : keys.slice(0, 1); // serialised mode runs the first only — caller can iterate

  return runners.map((key) => makeChild(key, command, args, cwd, config));
}

/**
 * Serial mode: returns a single SpawnHandle at a time. Caller awaits .done
 * before calling next() to pick up the following device. Used when
 * config.parallel === false.
 */
export function* iterateSerial(command: string, args: string[], config: RunConfig, cwd: string): Generator<SpawnHandle> {
  const keys = config.devices.length > 0 ? config.devices : ['default'];
  for (const key of keys) {
    yield makeChild(key, command, args, cwd, config);
  }
}

function makeChild(key: string, command: string, args: string[], cwd: string, config: RunConfig): SpawnHandle {
  // For booted devices (key is a serial like 'emulator-5554'), pass it as SERIAL.
  // For 'avd:Pixel_7'-style keys we still pass the raw string — bash scenarios
  // can decide whether to boot it. For 'default' we pass nothing.
  const env: NodeJS.ProcessEnv = { ...process.env, DCC_RUN_KEY: key };
  if (key !== 'default' && !key.startsWith('avd:')) {
    env.SERIAL = key;
  } else if (key.startsWith('avd:')) {
    env.AVD = key.slice('avd:'.length);
  }
  // Surface the rest of the run config to the shell so scenarios can react.
  env.DCC_MODE = config.mode;
  env.DCC_TARGET = config.target;
  env.DCC_PLATFORM = config.platform;
  env.DCC_KEEP_APP = config.keepApp ? '1' : '0';
  if (config.target === 'custom' && config.customEndpoint) {
    env.DCC_CUSTOM_ENDPOINT = config.customEndpoint;
  }

  const child = spawn(command, args, { cwd, env });
  const handle: SpawnHandle = {
    key,
    child,
    lines: [],
    exited: false,
    done: new Promise<number>(() => {}),
  };
  handle.done = new Promise<number>((resolve) => { handle.done_resolve = resolve; });

  const onChunk = (buf: Buffer) => {
    const text = buf.toString();
    for (const line of text.split('\n')) {
      if (line === '' && handle.lines.length > 0 && handle.lines[handle.lines.length - 1] === '') continue;
      handle.lines.push(line);
      // Cap buffer so we don't OOM on a chatty scenario
      if (handle.lines.length > 500) handle.lines.shift();
    }
  };

  child.stdout?.on('data', onChunk);
  child.stderr?.on('data', onChunk);
  child.on('exit', (code) => {
    handle.exited = true;
    handle.exitCode = code ?? -1;
    handle.done_resolve?.(handle.exitCode);
  });
  return handle;
}

/** Kill every running child in the pool. Used when the user navigates away. */
export function killAll(handles: SpawnHandle[]) {
  for (const h of handles) {
    if (!h.exited) {
      try { h.child.kill('SIGTERM'); } catch { /* noop */ }
    }
  }
}
