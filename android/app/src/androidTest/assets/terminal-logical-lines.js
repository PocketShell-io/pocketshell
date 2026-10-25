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
 * Runtime callers supply occupied DOM cell widths as well. Remote emoji
 * width can differ from the local renderer; an exact full row in either
 * model is eligible, while a short row in both models cannot continue.
 *
 * xterm's own wrap flag (IBufferLine.isWrapped) is not reachable from the
 * WebView: the shared TerminalView keeps its Terminal private and the DOM
 * renderer's rows carry no wrap marker. When no explicit column count is
 * supplied, the widest logical row remains the fallback width.
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
    for (var k = 0; k < rows.length; k++) width = Math.max(width, logicalCells(rows[k]) || 0);
  }
  function logicalCells(text) {
    // The remote terminal may use Unicode emoji widths while xterm's default
    // Unicode provider paints the same emoji as one DOM cell. Count grapheme
    // clusters, not UTF-16 units: combining marks never manufacture a wrap.
    if (typeof Intl.Segmenter !== 'function') return null;
    var cells = 0;
    var segments = new Intl.Segmenter('en', { granularity: 'grapheme' }).segment(text);
    for (var entry of segments) {
      var cluster = entry.segment;
      if (/^[\p{Mark}\p{Cf}\p{Cc}]+$/u.test(cluster)) continue;
      var code = cluster.codePointAt(0);
      var wide = code >= 0x1100 && (code <= 0x115f || code === 0x2329 || code === 0x232a
        || (code >= 0x2e80 && code <= 0xa4cf && code !== 0x303f)
        || (code >= 0xac00 && code <= 0xd7a3) || (code >= 0xf900 && code <= 0xfaff)
        || (code >= 0xfe10 && code <= 0xfe19) || (code >= 0xfe30 && code <= 0xfe6f)
        || (code >= 0xff00 && code <= 0xff60) || (code >= 0xffe0 && code <= 0xffe6)
        || (code >= 0x20000 && code <= 0x3fffd));
      var emoji = /\p{Emoji_Presentation}|\p{Regional_Indicator}/u.test(cluster)
        || (cluster.includes('\ufe0f') && /\p{Extended_Pictographic}/u.test(cluster))
        || cluster.includes('\u20e3');
      cells += wide || emoji ? 2 : 1;
    }
    return cells;
  }
  function fillsRow(index) {
    var logical = logicalCells(rows[index]);
    if (!occupiedColumns) return logical === width;
    var occupied = occupiedColumns[index];
    if (!Number.isFinite(occupied) || occupied > width + 0.15) return false;
    // Both are exact full-width models, not a tolerance for a short row.
    return Math.abs(occupied - width) < 0.15 || logical === width;
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
