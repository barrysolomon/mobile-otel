import { useEffect, useState } from 'react';

/**
 * Keeps the alt-screen-sized layout fresh by listening to stdout resize events.
 * Ink doesn't propagate process resizes into render scope, so we read columns/
 * rows off process.stdout and re-render on the `resize` signal.
 */
export function useTerminalSize() {
  const [size, setSize] = useState({
    columns: process.stdout.columns ?? 80,
    rows: process.stdout.rows ?? 24,
  });
  useEffect(() => {
    const handler = () => {
      setSize({
        columns: process.stdout.columns ?? 80,
        rows: process.stdout.rows ?? 24,
      });
    };
    process.stdout.on('resize', handler);
    return () => {
      process.stdout.off('resize', handler);
    };
  }, []);
  return size;
}
