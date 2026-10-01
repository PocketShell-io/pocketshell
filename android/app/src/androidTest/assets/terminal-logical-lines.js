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
 * mode: 'equals' (trimmed line === expected) or 'endsWith'.
 *
 * Shared, byte for byte, by SharedAppDockerJourneyTest (read from the test
 * APK's assets) and tests/unit/terminalLogicalLines.test.ts (replays captured
 * failure texts), so the journey's matcher is the unit-tested one.
 */
(function terminalHasLine(text, expected, mode) {
  var rows = String(text).split('\n');
  var width = 0;
  for (var k = 0; k < rows.length; k++) width = Math.max(width, rows[k].length);
  function matches(candidate) {
    var line = candidate.trim();
    return mode === 'endsWith' ? line.endsWith(expected) : line === expected;
  }
  for (var i = 0; i < rows.length; i++) {
    var joined = rows[i];
    if (matches(joined)) return true;
    for (var j = i + 1; j < rows.length && rows[j - 1].length === width; j++) {
      joined += rows[j];
      if (matches(joined)) return true;
    }
  }
  return false;
})
