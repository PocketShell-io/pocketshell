/*
 * terminalHasLine(text, expected, mode): does the visible terminal show
 * `expected` as one LOGICAL line, however xterm soft-wrapped it?
 *
 * `text` is the visible `.xterm-rows` innerText: one entry per screen row.
 * A row only continues into the next when the row is full width (a soft wrap
 * fills its row by definition), so a candidate is a run of consecutive rows
 * i..j whose rows i..j-1 are all full width. Single rows (j = i) are always
 * candidates, which keeps an exactly-full-width line that is NOT wrapped
 * matchable: it is not glued to the prompt row after it.
 *
 * xterm's own wrap flag (IBufferLine.isWrapped) is not reachable from the
 * WebView: the shared TerminalView keeps its Terminal private and the DOM
 * renderer's rows carry no wrap marker. "Full width" is the widest visible
 * row, which is the terminal width whenever any row wrapped.
 *
 * mode: 'equals' (trimmed line === expected), 'endsWith', or 'includes'.
 * Callers with runtime geometry pass `columns` to avoid inferring a wrap
 * from an arbitrary short widest row. Cursor-positioned aplexer redraws
 * also split lines this way, without setting xterm's isWrapped flag.
 *
 * Shared, byte for byte, by SharedAppDockerJourneyTest (read from the test
 * APK's assets) and tests/unit/terminalLogicalLines.test.ts (replays captured
 * failure texts), so the journey's matcher is the unit-tested one.
 */
(function terminalHasLine(text, expected, mode, columns, occupiedColumns) {
  var rows = String(text).split('\n');
  var width = columns || 0;
  if (!columns) {
    for (var k = 0; k < rows.length; k++) width = Math.max(width, rows[k].length);
  }
  function fillsRow(index) {
    // DOM glyph widths are authoritative when available: UTF-16 length is
    // not terminal cell width (emoji, combining marks, renderer padding).
    var occupied = occupiedColumns ? occupiedColumns[index] : rows[index].length;
    return Number.isFinite(occupied) && Math.abs(occupied - width) < 0.15;
  }
  function matches(candidate) {
    var line = candidate.trim();
    if (mode === 'includes') return line.includes(expected);
    return mode === 'endsWith' ? line.endsWith(expected) : line === expected;
  }
  for (var i = 0; i < rows.length; i++) {
    var joined = rows[i];
    if (matches(joined)) return true;
    for (var j = i + 1; j < rows.length && fillsRow(j - 1); j++) {
      joined += rows[j];
      if (matches(joined)) return true;
    }
  }
  return false;
})
