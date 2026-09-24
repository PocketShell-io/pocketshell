import { describe, expect, it } from 'vitest';
import { TerminalGeometryReporter, terminalGeometryChanged } from '../../src/terminalGeometry';

describe('terminal geometry resize reporting', () => {
  it('coalesces repeated unchanged fits while retaining the first usable grid', () => {
    const reporter = new TerminalGeometryReporter();
    const geometry = { cols: 80, rows: 24 };

    const initial = reporter.request(geometry);
    expect(initial).toMatchObject(geometry);
    expect(initial?.requestId).toEqual(expect.any(Number));
    expect(reporter.request({ ...geometry })).toBeNull();
    expect(reporter.request({ ...geometry })).toBeNull();
  });

  it('reports changed geometry, including when returning to a previous size', () => {
    const reporter = new TerminalGeometryReporter();
    const desktop = { cols: 80, rows: 24 };
    const keyboard = { cols: 37, rows: 5 };

    const first = reporter.request(desktop);
    const second = reporter.request(keyboard);
    const third = reporter.request(desktop);

    expect(first).toMatchObject(desktop);
    expect(second).toMatchObject(keyboard);
    expect(third).toMatchObject(desktop);
    expect(first!.requestId).toBeLessThan(second!.requestId);
    expect(second!.requestId).toBeLessThan(third!.requestId);
  });

  it('releases a rejected unchanged live grid so a later fit can retry it', () => {
    const reporter = new TerminalGeometryReporter();
    const geometry = { cols: 80, rows: 24 };
    const failed = reporter.request(geometry);

    expect(failed).not.toBeNull();
    reporter.reject(failed!.requestId);

    const retry = reporter.request(geometry);
    expect(retry).toMatchObject(geometry);
    expect(retry!.requestId).toBeGreaterThan(failed!.requestId);
    expect(reporter.request(geometry)).toBeNull();
  });

  it('does not let an old failure reopen a newer geometry request', () => {
    const reporter = new TerminalGeometryReporter();
    const oldRequest = reporter.request({ cols: 80, rows: 24 });
    const current = reporter.request({ cols: 37, rows: 5 });

    reporter.reject(oldRequest!.requestId);

    expect(current).toMatchObject({ cols: 37, rows: 5 });
    expect(current!.requestId).not.toBe(oldRequest!.requestId);
    expect(reporter.request({ cols: 37, rows: 5 })).toBeNull();
  });

  it('does not report unmeasurable geometry', () => {
    const reporter = new TerminalGeometryReporter();

    expect(terminalGeometryChanged(null, { cols: 0, rows: 24 })).toBe(false);
    expect(terminalGeometryChanged(null, { cols: 80, rows: 0 })).toBe(false);
    expect(terminalGeometryChanged(null, { cols: 80.5, rows: 24 })).toBe(false);
    expect(reporter.request({ cols: 0, rows: 24 })).toBeNull();
  });
});
