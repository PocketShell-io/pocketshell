export interface TerminalGeometry {
  cols: number;
  rows: number;
}

export interface TerminalResizeRequest extends TerminalGeometry {
  requestId?: number;
}

export interface ReportedTerminalResizeRequest extends TerminalResizeRequest {
  requestId: number;
}

let latestTerminalResizeRequestId = 0;

/** Allocate one app-wide ID so delayed bridge replies cannot match a remounted viewport. */
export function allocateTerminalResizeRequestId(): number {
  latestTerminalResizeRequestId += 1;
  return latestTerminalResizeRequestId;
}

export function terminalGeometryChanged(
  previous: TerminalGeometry | null,
  next: TerminalGeometry,
): boolean {
  if (!Number.isInteger(next.cols) || next.cols < 1 || !Number.isInteger(next.rows) || next.rows < 1) return false;
  return previous === null || previous.cols !== next.cols || previous.rows !== next.rows;
}

/** Coalesces identical fit observations and releases a rejected request for a later retry. */
export class TerminalGeometryReporter {
  private lastRequest: ReportedTerminalResizeRequest | null = null;

  request(geometry: TerminalGeometry, force = false): ReportedTerminalResizeRequest | null {
    if (!force && !terminalGeometryChanged(this.lastRequest, geometry)) return null;
    if (!terminalGeometryChanged(null, geometry)) return null;
    const request = { ...geometry, requestId: allocateTerminalResizeRequestId() };
    this.lastRequest = request;
    return request;
  }

  reject(requestId: number): void {
    if (this.lastRequest?.requestId === requestId) this.lastRequest = null;
  }

  reset(): void {
    this.lastRequest = null;
  }
}
